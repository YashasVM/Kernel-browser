package com.kernel.browser

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.Environment
import android.app.AlertDialog
import android.app.DownloadManager
import android.graphics.Bitmap
import android.content.res.ColorStateList
import android.content.ClipData
import android.content.ClipboardManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.text.Editable
import android.text.TextWatcher
import android.content.Context
import android.content.Intent
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import android.widget.Switch
import android.widget.Toast
import com.kernel.browser.databinding.ActivityMainBinding
import com.kernel.browser.extensions.ExtensionActionRegistry
import com.kernel.browser.extensions.ExtensionManager
import com.kernel.browser.extensions.ExtensionPreferences
import com.kernel.browser.tabs.BrowserTab
import com.kernel.browser.tabs.BrowserTabs
import com.kernel.browser.tabs.TabMode
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.MediaSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import org.mozilla.geckoview.WebResponse
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var runtime: GeckoRuntime
    private lateinit var tabs: BrowserTabs
    private lateinit var extensionPreferences: ExtensionPreferences
    private lateinit var extensionManager: ExtensionManager
    private lateinit var extensionActionRegistry: ExtensionActionRegistry
    private lateinit var extensionsUi: ExtensionsDialogController
    private lateinit var historyStore: BrowserHistoryStore
    private lateinit var bookmarksStore: BookmarksStore
    private lateinit var downloadStore: DownloadStore
    private lateinit var loginStore: LoginStore
    private lateinit var sessionStore: SessionStore
    private lateinit var browserPreferences: BrowserPreferences
    private val tabThumbnails = mutableMapOf<Long, Bitmap>()
    private var bottomInset = 0
    private var chromeHidden = false
    private var addressBarEditing = false
    private var suggestionsVisible = false
    private var suggestionRequestId = 0
    private val searchRecommendationCache = object : LinkedHashMap<String, List<String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > 64
    }
    private val chromeHandler = Handler(Looper.getMainLooper())
    private val chromeInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val hideChromeRunnable = Runnable { setChromeVisible(false, auto = true) }
    private var pendingAndroidPermissionCallback: GeckoSession.PermissionDelegate.Callback? = null
    private var pendingFilePrompt: GeckoSession.PromptDelegate.FilePrompt? = null
    private var pendingFileResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? = null
    private var lastClosedNormalTab: ClosedTab? = null
    private var fullScreenSession: GeckoSession? = null
    private val playingSessions = mutableSetOf<GeckoSession>()
    private val saveSessionRunnable = Runnable { saveNormalSession() }
    private val suggestionExecutor = Executors.newSingleThreadExecutor()
    private var pendingSuggestionQuery: Runnable? = null
    private val pendingDownloads = mutableListOf<WebResponse>()
    private var extensionActiveSession: GeckoSession? = null

    private data class AddressSuggestion(
        val title: String,
        val subtitle: String,
        val value: String,
        val kind: SuggestionKind
    )

    private data class ClosedTab(
        val title: String,
        val url: String
    )

    private enum class SuggestionKind {
        HISTORY,
        SEARCH,
        NAVIGATE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureSystemBars()

        browserPreferences = BrowserPreferences(this)
        historyStore = BrowserHistoryStore(this)
        bookmarksStore = BookmarksStore(this)
        downloadStore = DownloadStore(this)
        loginStore = LoginStore(this)
        sessionStore = SessionStore(this)
        runtime = BrowserRuntime.get(this)
        runtime.autocompleteStorageDelegate = loginStore
        applyRuntimePreferences()
        extensionPreferences = ExtensionPreferences(this)
        extensionActionRegistry = ExtensionActionRegistry(this, runtime) {
            runOnUiThread {
                updateUi()
                if (::extensionsUi.isInitialized) extensionsUi.onExtensionsChanged()
            }
        }
        extensionManager = ExtensionManager(runtime, extensionPreferences, extensionActionRegistry)
        extensionsUi = ExtensionsDialogController(
            context = this,
            manager = extensionManager,
            actionRegistry = extensionActionRegistry,
            openUrl = ::openInNewTab,
            pickExtensionFile = ::pickExtensionFile
        )
        extensionManager.host = extensionHost
        extensionManager.start()

        tabs = BrowserTabs(runtime, ::configureSession)
        restoreTabsOrCreateHome(intent)

        configureToolbar()
        configureInsets()
        registerBackCallback()
        updateUi()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The activity handles rotation itself, so the floating toolbar must be resized here;
        // otherwise it keeps the width it had in the previous orientation.
        binding.root.post { applyChromeWidth() }
    }

    private fun applyChromeWidth() {
        val params = binding.bottomChrome.layoutParams as FrameLayout.LayoutParams
        params.width = chromeWidth()
        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.marginStart = 0
        params.marginEnd = 0
        binding.bottomChrome.layoutParams = params
        updateSuggestionPanelPosition()
        binding.bottomChrome.post { updateGeckoChromeInsets() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openIntentUri(intent)
    }

    override fun onPause() {
        chromeHandler.removeCallbacks(saveSessionRunnable)
        saveNormalSession()
        super.onPause()
    }

    override fun onDestroy() {
        chromeHandler.removeCallbacksAndMessages(null)
        if (isFinishing) {
            saveNormalSession()
            tabs.closePrivateTabs()
        }
        extensionsUi.cancelPendingPrompts()
        extensionManager.host = null
        suggestionExecutor.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Kept for Android 12 and older; newer versions use the OnBackInvokedCallback below.")
    override fun onBackPressed() {
        if (!handleBack()) super.onBackPressed()
    }

    /**
     * On Android 13+ (and always on Android 16 for targetSdk 36) the system no longer calls
     * onBackPressed, so Back is routed through an OnBackInvokedCallback instead.
     */
    private fun registerBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            // Nothing left to go back to: leave like the platform does for a root activity.
            if (!handleBack()) moveTaskToBack(true)
        }
    }

    /** Returns true when Back was consumed inside the browser. */
    private fun handleBack(): Boolean {
        val tab = tabs.activeTab
        if (fullScreenSession != null) {
            fullScreenSession?.exitFullScreen()
        } else if (binding.addressBar.hasFocus()) {
            binding.addressBar.clearFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(binding.addressBar.windowToken, 0)
        } else if (tab?.canGoBack == true) {
            tab.session.goBack()
        } else {
            return false
        }
        return true
    }

    private fun restoreTabsOrCreateHome(intent: Intent?) {
        if (openIntentUri(intent)) return

        val restoredTabs = sessionStore.restore().ifEmpty {
            listOf(SessionStore.Entry("Home", StartPage.SESSION_URL, ""))
        }
        restoredTabs.forEachIndexed { index, entry ->
            val tab = tabs.create(TabMode.NORMAL)
            tab.title = entry.title
            tab.url = entry.url.takeUnless { it == StartPage.SESSION_URL }.orEmpty()
            if (index == restoredTabs.lastIndex) {
                attachTab(tab)
            }
            if (entry.state.isNotBlank()) {
                runCatching {
                    tab.sessionState = entry.state
                    val state = GeckoSession.SessionState.fromString(entry.state)
                        ?: error("Invalid session state")
                    tab.session.restoreState(state)
                }.getOrElse {
                    tab.session.loadUri(entry.url.ifBlank { browserPreferences.homepageUrl })
                }
            } else if (entry.url == StartPage.SESSION_URL) {
                openHome(tab)
            } else {
                tab.session.loadUri(entry.url.ifBlank { browserPreferences.homepageUrl })
            }
        }
    }

    private fun openIntentUri(intent: Intent?): Boolean {
        val uri = intent?.dataString?.takeIf {
            intent.action == Intent.ACTION_VIEW && (it.startsWith("http://") || it.startsWith("https://"))
        } ?: return false

        val tab = tabs.create(TabMode.NORMAL)
        attachTab(tab)
        tab.session.loadUri(uri)
        setChromeVisible(true)
        return true
    }

    private fun configureToolbar() {
        binding.bottomChrome.alpha = 0f
        binding.bottomChrome.post {
            updateGeckoChromeInsets()
            binding.bottomChrome.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300L)
                .setInterpolator(chromeInterpolator)
                .withEndAction { scheduleChromeAutoHide() }
                .start()
        }
        binding.backButton.setOnClickListener { tabs.activeTab?.session?.goBack() }
        binding.forwardButton.setOnClickListener { tabs.activeTab?.session?.goForward() }
        binding.reloadButton.setOnClickListener {
            val tab = tabs.activeTab ?: return@setOnClickListener
            if (tab.isLoading) {
                tab.session.stop()
            } else {
                tab.session.reload()
            }
        }
        binding.homeButton.setOnClickListener {
            tabs.activeTab?.let(::openHome)
        }
        binding.tabsButton.setOnClickListener {
            setChromeVisible(true)
            showTabsDialog()
        }
        binding.extensionsButton.setOnClickListener {
            setChromeVisible(true)
            extensionsUi.show()
        }
        binding.settingsButton.setOnClickListener {
            setChromeVisible(true)
            showBrowserMenu()
        }
        binding.geckoView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN && chromeHidden) {
                setChromeVisible(true)
            }
            false
        }

        binding.addressBar.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER
            when {
                // Hardware keyboards deliver Enter as a down/up pair; act on down, swallow up.
                isEnter && event?.action == KeyEvent.ACTION_UP -> true
                actionId == EditorInfo.IME_ACTION_GO || isEnter -> {
                    loadFromAddressBar()
                    true
                }
                else -> false
            }
        }
        binding.addressBar.setOnFocusChangeListener { _, hasFocus ->
            addressBarEditing = hasFocus
            if (hasFocus) {
                cancelChromeAutoHide()
                setChromeVisible(true)
                updateAddressSuggestions()
            } else {
                hideAddressSuggestions()
                scheduleChromeAutoHide()
            }
        }
        binding.addressBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (binding.addressBar.hasFocus()) {
                    updateAddressSuggestions()
                }
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })
        addressBarEditing = false
        binding.addressBar.clearFocus()
    }

    private fun configureSystemBars() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true)
        }
        window.statusBarColor = getColor(R.color.kernel_background)
        window.navigationBarColor = getColor(R.color.kernel_background)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (night) {
            0
        } else {
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private fun configureInsets() {
        binding.root.setOnApplyWindowInsetsListener { _, insets ->
            val topInset = insets.systemWindowInsetTop
            bottomInset = insets.systemWindowInsetBottom

            val geckoParams = binding.geckoView.layoutParams as FrameLayout.LayoutParams
            geckoParams.topMargin = topInset
            binding.geckoView.layoutParams = geckoParams

            val progressParams = binding.progressBar.layoutParams as FrameLayout.LayoutParams
            progressParams.topMargin = topInset
            binding.progressBar.layoutParams = progressParams

            val params = binding.bottomChrome.layoutParams as FrameLayout.LayoutParams
            params.bottomMargin = bottomInset + ChromeSheet.dp(this, 12)
            params.width = chromeWidth()
            params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            params.marginStart = 0
            params.marginEnd = 0
            binding.bottomChrome.layoutParams = params
            binding.bottomChrome.post {
                updateSuggestionPanelPosition()
                updateGeckoChromeInsets()
            }
            insets
        }
        binding.root.requestApplyInsets()
    }

    private fun chromeWidth(): Int {
        val metrics = resources.displayMetrics
        val horizontalInset = ChromeSheet.dp(this, 12) * 2
        val maxWidth = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            ChromeSheet.dp(this, 620)
        } else {
            ChromeSheet.dp(this, 720)
        }
        return (metrics.widthPixels - horizontalInset).coerceAtMost(maxWidth)
    }

    private fun configureSession(tab: BrowserTab) {
        applyDesktopMode(tab)
        var lastScrollYPx = 0f
        tab.session.setCompositorScrollDelegate(object : GeckoSession.CompositorScrollDelegate {
            override fun onScrollChanged(
                session: GeckoSession,
                update: GeckoSession.ScrollPositionUpdate
            ) {
                if (session != tabs.activeTab?.session) return
                if (update.source != GeckoSession.ScrollPositionUpdate.SOURCE_USER_INTERACTION) {
                    lastScrollYPx = update.scrollY * update.zoom
                    return
                }
                if (binding.addressBar.hasFocus()) {
                    lastScrollYPx = update.scrollY * update.zoom
                    return
                }
                val scrollYPx = update.scrollY * update.zoom
                val delta = scrollYPx - lastScrollYPx
                when {
                    scrollYPx <= ChromeSheet.dp(this@MainActivity, 12) -> setChromeVisible(true)
                    delta > ChromeSheet.dp(this@MainActivity, 10) -> setChromeVisible(false)
                    delta < -ChromeSheet.dp(this@MainActivity, 8) -> setChromeVisible(true)
                }
                lastScrollYPx = scrollYPx
            }
        })

        tab.session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                if (session != tab.session) return
                tab.title = title.orEmpty()
                recordHistory(tab)
                updateUi()
            }

            override fun onCloseRequest(session: GeckoSession) {
                if (fullScreenSession == session) onFullScreenChanged(session, false)
                rememberClosedTab(tab)
                tabThumbnails.remove(tab.id)?.recycle()
                tabs.close(tab.id)
                attachCurrentOrCreate()
                saveNormalSession()
            }

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                onFullScreenChanged(session, fullScreen)
            }

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                if (isExtensionPackage(response.uri, response.headers["Content-Type"] ?: response.headers["content-type"])) {
                    extensionManager.install(response.uri)
                } else {
                    startDownload(response)
                }
            }

            override fun onCrash(session: GeckoSession) {
                recoverSession(tab, "This page crashed and was reloaded")
            }

            override fun onKill(session: GeckoSession) {
                recoverSession(tab, null)
            }
        }

        tab.session.mediaSessionDelegate = object : MediaSession.Delegate {
            override fun onPlay(session: GeckoSession, mediaSession: MediaSession) {
                playingSessions += session
                updateKeepScreenOn()
            }

            override fun onPause(session: GeckoSession, mediaSession: MediaSession) {
                playingSessions -= session
                updateKeepScreenOn()
            }

            override fun onStop(session: GeckoSession, mediaSession: MediaSession) {
                playingSessions -= session
                updateKeepScreenOn()
            }

            override fun onDeactivated(session: GeckoSession, mediaSession: MediaSession) {
                playingSessions -= session
                updateKeepScreenOn()
            }

            override fun onFullscreen(
                session: GeckoSession,
                mediaSession: MediaSession,
                enabled: Boolean,
                meta: MediaSession.ElementMetadata?
            ) {
                // Rotate landscape videos into landscape while they are fullscreen, like a video app.
                requestedOrientation = if (enabled && meta != null && meta.width > meta.height) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }

        tab.session.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onAndroidPermissionsRequest(
                session: GeckoSession,
                permissions: Array<String>?,
                callback: GeckoSession.PermissionDelegate.Callback
            ) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                    callback.grant()
                    return
                }
                pendingAndroidPermissionCallback = callback
                requestPermissions(permissions ?: emptyArray(), REQUEST_ANDROID_PERMISSIONS)
            }

            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission
            ): GeckoResult<Int> {
                autoplayDecision(perm.permission)?.let { return GeckoResult.fromValue(it) }
                val result = GeckoResult<Int>()
                val name = contentPermissionName(perm.permission)
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Allow $name?")
                    .setMessage("${perm.uri} wants to use $name.")
                    .setPositiveButton("Allow") { _, _ -> result.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW) }
                    .setNegativeButton("Block") { _, _ -> result.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY) }
                    .setOnCancelListener { result.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY) }
                    .show()
                return result
            }

            override fun onMediaPermissionRequest(
                session: GeckoSession,
                uri: String,
                video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
                audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
                callback: GeckoSession.PermissionDelegate.MediaCallback
            ) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Allow camera or microphone?")
                    .setMessage("$uri wants to use media devices.")
                    .setPositiveButton("Allow") { _, _ ->
                        callback.grant(video?.firstOrNull(), audio?.firstOrNull())
                    }
                    .setNegativeButton("Block") { _, _ -> callback.reject() }
                    .setOnCancelListener { callback.reject() }
                    .show()
            }
        }

        tab.session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onFilePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.FilePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                pendingFilePrompt = prompt
                pendingFileResult = result
                openFilePicker(prompt)
                return result
            }

            override fun onLoginSave(
                session: GeckoSession,
                request: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.LoginSaveOption>
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                val option = request.options.firstOrNull()
                val login = option?.value
                if (option == null || login == null || !browserPreferences.loginAutofill) {
                    result.complete(request.dismiss())
                    return result
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Save password?")
                    .setMessage("Save login for ${login.username} on ${login.origin}?")
                    .setPositiveButton("Save") { _, _ ->
                        loginStore.save(login)
                        result.complete(request.confirm(option))
                    }
                    .setNegativeButton("Not now") { _, _ -> result.complete(request.dismiss()) }
                    .setOnCancelListener { result.complete(request.dismiss()) }
                    .show()
                return result
            }

            override fun onLoginSelect(
                session: GeckoSession,
                request: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.LoginSelectOption>
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                val options = request.options
                if (options.isEmpty() || !browserPreferences.loginAutofill) {
                    result.complete(request.dismiss())
                    return result
                }
                val labels = options.map { option ->
                    option.value.username.ifBlank { option.value.origin }
                }.toTypedArray()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Use saved login?")
                    .setItems(labels) { _, index -> result.complete(request.confirm(options[index])) }
                    .setNegativeButton("Cancel") { _, _ -> result.complete(request.dismiss()) }
                    .setOnCancelListener { result.complete(request.dismiss()) }
                    .show()
                return result
            }
        }

        tab.session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                if (session != tab.session) return
                tab.url = StartPage.displayUrl(url)
                if (!binding.addressBar.hasFocus()) {
                    binding.addressBar.setText(tab.url)
                }
                recordHistory(tab)
                updateUi()
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                if (session != tab.session) return
                tab.canGoBack = canGoBack
                updateUi()
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                if (session != tab.session) return
                tab.canGoForward = canGoForward
                updateUi()
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                if (isExtensionPackage(request.uri, null)) {
                    // Pages can't pop install prompts on their own (redirects, scripted loads).
                    if (request.hasUserGesture || request.isDirectNavigation) extensionManager.install(request.uri)
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
                val newTab = tabs.create(tab.mode, open = false)
                attachTab(newTab)
                newTab.url = uri
                updateUi()
                return GeckoResult.fromValue(newTab.session)
            }
        }

        tab.session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                if (session != tab.session) return
                if (tab == tabs.activeTab) markExtensionActiveTab(session)
                tab.isLoading = true
                tab.url = StartPage.displayUrl(url)
                setChromeVisible(true)
                updateUi()
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (session != tab.session) return
                tab.isLoading = false
                tab.progress = 0
                captureThumbnail(tab)
                recordHistory(tab)
                scheduleSessionSave()
                updateUi()
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                if (session != tab.session) return
                tab.progress = progress
                updateUi()
            }

            override fun onSecurityChange(
                session: GeckoSession,
                securityInfo: GeckoSession.ProgressDelegate.SecurityInformation
            ) {
                if (session != tab.session) return
                tab.isSecure = securityInfo.isSecure
                tab.securityHost = securityInfo.host.orEmpty()
                updateUi()
            }

            override fun onSessionStateChange(
                session: GeckoSession,
                sessionState: GeckoSession.SessionState
            ) {
                if (session != tab.session) return
                tab.sessionState = sessionState.toString()
                // Fires on every scroll; batch the writes instead of hitting disk each time.
                if (!tab.isPrivate) scheduleSessionSave()
            }
        }
    }

    private fun loadFromAddressBar() {
        val normalized = UrlNormalizer.normalize(binding.addressBar.text.toString(), browserPreferences.searchEngine)
        tabs.activeTab?.session?.loadUri(normalized)
        addressBarEditing = false
        hideAddressSuggestions()
        binding.addressBar.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.addressBar.windowToken, 0)
        setChromeVisible(true)
    }

    private fun loadSuggestion(suggestion: AddressSuggestion) {
        val url = when (suggestion.kind) {
            SuggestionKind.HISTORY -> suggestion.value
            SuggestionKind.NAVIGATE -> UrlNormalizer.normalize(suggestion.value, browserPreferences.searchEngine)
            SuggestionKind.SEARCH -> UrlNormalizer.normalize(suggestion.value, browserPreferences.searchEngine)
        }
        binding.addressBar.setText(suggestion.value)
        binding.addressBar.setSelection(binding.addressBar.text.length)
        tabs.activeTab?.session?.loadUri(url)
        addressBarEditing = false
        hideAddressSuggestions()
        binding.addressBar.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.addressBar.windowToken, 0)
        setChromeVisible(true)
    }

    private fun updateAddressSuggestions() {
        val query = binding.addressBar.text.toString().trim()
        if (!binding.addressBar.hasFocus()) {
            hideAddressSuggestions()
            return
        }

        val localSuggestions = localAddressSuggestions(query)
        val cachedRecommendations = searchRecommendationCache[query.lowercase()].orEmpty()
        renderAddressSuggestions((localSuggestions + cachedRecommendations.map {
            AddressSuggestion(it, "Search ${browserPreferences.searchEngine.displayName}", it, SuggestionKind.SEARCH)
        }).distinctBy { it.kind.name + it.value }.take(4))

        if (query.length >= 2 && cachedRecommendations.isEmpty()) {
            fetchSearchRecommendations(query)
        }
    }

    private fun localAddressSuggestions(query: String): List<AddressSuggestion> {
        val normalizedQuery = query.lowercase()
        val history = (bookmarksStore.entries() + historyStore.entries()).distinctBy { it.second }
        val matches = if (query.isBlank()) {
            history.take(5)
        } else {
            history.filter { (title, url) ->
                title.lowercase().contains(normalizedQuery) || url.lowercase().contains(normalizedQuery)
            }.take(4)
        }.map { (title, url) ->
            AddressSuggestion(
                title = title.ifBlank { url },
                subtitle = url,
                value = url,
                kind = SuggestionKind.HISTORY
            )
        }

        if (query.isBlank()) return matches

        val primary = if (looksLikeUrl(query)) {
            AddressSuggestion(query, "Open website", query, SuggestionKind.NAVIGATE)
        } else {
            AddressSuggestion(query, "Search ${browserPreferences.searchEngine.displayName}", query, SuggestionKind.SEARCH)
        }

        return listOf(primary) + matches
    }

    private fun fetchSearchRecommendations(query: String) {
        val requestId = ++suggestionRequestId
        pendingSuggestionQuery?.let(chromeHandler::removeCallbacks)
        // Wait for a short typing pause so fast typists don't fire one request per key.
        val request = Runnable { suggestionExecutor.execute { loadSearchRecommendations(query, requestId) } }
        pendingSuggestionQuery = request
        chromeHandler.postDelayed(request, SUGGESTION_DEBOUNCE_MS)
    }

    private fun loadSearchRecommendations(query: String, requestId: Int) {
        if (requestId != suggestionRequestId) return
        run {
            val recommendations = runCatching {
                val suggestionsUrl = browserPreferences.searchEngine.suggestionsUrl
                if (suggestionsUrl == null) {
                    emptyList()
                } else {
                    val encoded = URLEncoder.encode(query, "UTF-8")
                    val connection = URL(suggestionsUrl.format(encoded))
                        .openConnection() as HttpURLConnection
                    connection.connectTimeout = 1_500
                    connection.readTimeout = 1_500
                    connection.setRequestProperty("User-Agent", "KernelBrowser/${BuildConfig.VERSION_NAME}")
                    connection.inputStream.bufferedReader().use { reader ->
                        val payload = reader.readText()
                        val array = JSONArray(payload).getJSONArray(1)
                        List(array.length()) { index -> array.getString(index) }
                    }
                }
            }.getOrElse { emptyList() }

            chromeHandler.post {
                if (requestId != suggestionRequestId) return@post
                searchRecommendationCache[query.lowercase()] = recommendations
                if (binding.addressBar.hasFocus() && binding.addressBar.text.toString().trim() == query) {
                    updateAddressSuggestions()
                }
            }
        }
    }

    private fun renderAddressSuggestions(suggestions: List<AddressSuggestion>) {
        binding.suggestionsPanel.removeAllViews()
        if (suggestions.isEmpty()) {
            hideAddressSuggestions()
            return
        }

        suggestions.forEachIndexed { index, suggestion ->
            binding.suggestionsPanel.addView(suggestionRow(suggestion), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ChromeSheet.dp(this, 58)
            ).apply {
                if (index < suggestions.lastIndex) {
                    bottomMargin = ChromeSheet.dp(this@MainActivity, 4)
                }
            })
        }
        showAddressSuggestions()
    }

    private fun suggestionRow(suggestion: AddressSuggestion): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ChromeSheet.dp(this@MainActivity, 12), 0, ChromeSheet.dp(this@MainActivity, 10), 0)
            background = suggestionRowBackground()
            foreground = selectableItemBackground()
            isClickable = true
            isFocusable = true
            contentDescription = "${suggestion.title}, ${suggestion.subtitle}"
            setOnClickListener { loadSuggestion(suggestion) }

            addView(ImageView(this@MainActivity).apply {
                setImageResource(if (suggestion.kind == SuggestionKind.HISTORY) R.drawable.ic_home else R.drawable.ic_search)
                imageTintList = ColorStateList.valueOf(getColor(R.color.kernel_muted))
                scaleType = ImageView.ScaleType.CENTER
            }, LinearLayout.LayoutParams(ChromeSheet.dp(this@MainActivity, 30), ChromeSheet.dp(this@MainActivity, 30)).apply {
                marginEnd = ChromeSheet.dp(this@MainActivity, 12)
            })

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = suggestion.title
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    textSize = 16f
                    includeFontPadding = false
                    setTextColor(getColor(R.color.kernel_text))
                })
                addView(TextView(this@MainActivity).apply {
                    text = suggestion.subtitle
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    textSize = 12f
                    includeFontPadding = false
                    setPadding(0, ChromeSheet.dp(this@MainActivity, 3), 0, 0)
                    setTextColor(getColor(R.color.kernel_muted))
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun showAddressSuggestions() {
        updateSuggestionPanelPosition()
        if (suggestionsVisible) return
        suggestionsVisible = true
        binding.suggestionsPanel.visibility = View.VISIBLE
        binding.suggestionsPanel.alpha = 0f
        binding.suggestionsPanel.translationY = ChromeSheet.dp(this, 14).toFloat()
        binding.suggestionsPanel.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(210L)
            .setInterpolator(chromeInterpolator)
            .start()
    }

    private fun hideAddressSuggestions() {
        if (!suggestionsVisible && binding.suggestionsPanel.visibility != View.VISIBLE) return
        suggestionsVisible = false
        binding.suggestionsPanel.animate().cancel()
        binding.suggestionsPanel.animate()
            .alpha(0f)
            .translationY(ChromeSheet.dp(this, 10).toFloat())
            .setDuration(150L)
            .setInterpolator(chromeInterpolator)
            .withEndAction {
                if (!suggestionsVisible) {
                    binding.suggestionsPanel.visibility = View.GONE
                    binding.suggestionsPanel.removeAllViews()
                }
            }
            .start()
    }

    private fun updateSuggestionPanelPosition() {
        val params = binding.suggestionsPanel.layoutParams as FrameLayout.LayoutParams
        params.width = chromeWidth()
        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.marginStart = 0
        params.marginEnd = 0
        params.bottomMargin = bottomInset + binding.bottomChrome.height + ChromeSheet.dp(this, 24)
        binding.suggestionsPanel.layoutParams = params
    }

    private fun suggestionRowBackground(): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(getColor(R.color.kernel_surface_alt))
            cornerRadius = ChromeSheet.dp(this@MainActivity, 18).toFloat()
        }
    }

    private fun selectableItemBackground(): android.graphics.drawable.Drawable? {
        val attrs = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        return attrs.getDrawable(0).also { attrs.recycle() }
    }

    private fun looksLikeUrl(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.contains(".") || trimmed.startsWith("localhost", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)
    }

    private fun setChromeVisible(visible: Boolean, auto: Boolean = false) {
        if (fullScreenSession != null) return
        if (chromeHidden == !visible) {
            if (visible) scheduleChromeAutoHide()
            return
        }
        if (!visible || auto) {
            cancelChromeAutoHide()
        }
        if (!visible) {
            hideAddressSuggestions()
        }
        chromeHidden = !visible
        val distance = binding.bottomChrome.height + bottomInset + ChromeSheet.dp(this, 24)
        if (visible) {
            binding.bottomChrome.visibility = View.VISIBLE
        }
        binding.bottomChrome.animate()
            .cancel()
        binding.bottomChrome.animate()
            .translationY(if (visible) 0f else distance.toFloat())
            .alpha(if (visible) 1f else 0f)
            .setDuration(if (visible) 300L else 240L)
            .setInterpolator(chromeInterpolator)
            .setUpdateListener { updateGeckoChromeInsets() }
            .withEndAction {
                if (!visible) {
                    binding.bottomChrome.visibility = View.INVISIBLE
                }
                updateGeckoChromeInsets()
                if (visible) scheduleChromeAutoHide()
            }
            .start()
        updateGeckoChromeInsets()
    }

    private fun scheduleChromeAutoHide() {
        // The toolbar now only hides while scrolling down (see the scroll delegate), like
        // Safari and Firefox; hiding on a timer made controls vanish while reading.
        cancelChromeAutoHide()
    }

    private fun cancelChromeAutoHide() {
        chromeHandler.removeCallbacks(hideChromeRunnable)
    }

    private fun updateGeckoChromeInsets() {
        if (fullScreenSession != null) return
        val chromeHeight = binding.bottomChrome.height
        if (chromeHeight <= 0) return
        val bottomMargin = (binding.bottomChrome.layoutParams as? FrameLayout.LayoutParams)?.bottomMargin ?: bottomInset
        val visibleClipping = chromeHeight + bottomMargin
        val currentClipping = (visibleClipping - binding.bottomChrome.translationY.toInt()).coerceAtLeast(bottomInset)
        binding.geckoView.setDynamicToolbarMaxHeight(visibleClipping)
        binding.geckoView.setVerticalClipping(currentClipping)
    }

    private fun showBrowserMenu() {
        val tab = tabs.activeTab
        val url = tab?.url.orEmpty()
        val bookmarked = bookmarksStore.isBookmarked(url)
        ChromeSheet.show(
            context = this,
            title = tab?.title?.takeIf { it.isNotBlank() } ?: "Menu",
            subtitle = runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.") ?: url.takeIf { it.isNotBlank() }
        ) { dialog ->
            fun run(action: () -> Unit): () -> Unit = {
                dialog.dismiss()
                action()
            }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(quickAction(if (bookmarked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border,
                    if (bookmarked) "Saved" else "Bookmark", run(::toggleBookmark)))
                addView(quickAction(R.drawable.ic_share, "Share", run(::shareCurrentPage)))
                addView(quickAction(R.drawable.ic_search, "Find", run(::showFindSheet)))
                addView(quickAction(R.drawable.ic_add, "New tab", run {
                    val newTab = tabs.create(TabMode.NORMAL)
                    attachTab(newTab)
                    openHome(newTab)
                    binding.addressBar.requestFocus()
                }))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = ChromeSheet.dp(this@MainActivity, 4)
            })

            addView(KernelUi.sectionHeader(this@MainActivity, "This page"))
            addView(KernelUi.group(
                this@MainActivity,
                KernelUi.toggle(this@MainActivity, "Desktop site", icon = R.drawable.ic_desktop, tile = R.color.tile_indigo,
                    checked = browserPreferences.desktopMode) {
                    browserPreferences.desktopMode = it
                    applyDesktopModeToAllTabs(reloadActive = true)
                },
                KernelUi.item(this@MainActivity, "Reader view", icon = R.drawable.ic_reader, tile = R.color.tile_orange,
                    onClick = run(::openReaderView)),
                KernelUi.item(this@MainActivity, "Copy link", icon = R.drawable.ic_link, tile = R.color.tile_blue,
                    onClick = run(::copyCurrentLink)),
                KernelUi.item(this@MainActivity, "Site info", subtitle = securitySummary(tab),
                    icon = if (tab?.isSecure == true) R.drawable.ic_lock else R.drawable.ic_info,
                    tile = if (tab?.isSecure == true) R.color.tile_green else R.color.tile_gray,
                    chevron = true, onClick = run(::showSiteInfo))
            ))

            addView(KernelUi.sectionHeader(this@MainActivity, "Browser"))
            addView(KernelUi.group(
                this@MainActivity,
                KernelUi.item(this@MainActivity, "Bookmarks", icon = R.drawable.ic_bookmark, tile = R.color.tile_orange,
                    value = bookmarksStore.entries().size.takeIf { it > 0 }?.toString(), chevron = true, onClick = run(::showBookmarksSheet)),
                KernelUi.item(this@MainActivity, "Downloads", icon = R.drawable.ic_download, tile = R.color.tile_green,
                    value = downloadStore.entries().size.takeIf { it > 0 }?.toString(), chevron = true, onClick = run(::showDownloadsSheet)),
                KernelUi.item(this@MainActivity, "Extensions", icon = R.drawable.ic_extension, tile = R.color.tile_purple,
                    value = "${extensionManager.enabledCount()} on", chevron = true, onClick = run { extensionsUi.show() }),
                KernelUi.item(this@MainActivity, "Reopen closed tab",
                    subtitle = lastClosedNormalTab?.title?.ifBlank { lastClosedNormalTab?.url },
                    icon = R.drawable.ic_tab_restore, tile = R.color.tile_teal, onClick = run(::undoCloseTab)),
                KernelUi.item(this@MainActivity, "Settings", icon = R.drawable.ic_settings, tile = R.color.tile_gray,
                    chevron = true, onClick = run(::showSettings))
            ))
        }
    }

    private fun quickAction(icon: Int, label: String, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, ChromeSheet.dp(this@MainActivity, 12), 0, ChromeSheet.dp(this@MainActivity, 10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(getColor(R.color.kernel_group))
                cornerRadius = ChromeSheet.dp(this@MainActivity, 16).toFloat()
            }
            foreground = selectableItemBackground()
            isClickable = true
            isFocusable = true
            contentDescription = label
            setOnClickListener { onClick() }
            addView(ImageView(this@MainActivity).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(getColor(R.color.kernel_text))
            }, LinearLayout.LayoutParams(ChromeSheet.dp(this@MainActivity, 24), ChromeSheet.dp(this@MainActivity, 24)))
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                maxLines = 1
                setTextColor(getColor(R.color.kernel_muted))
                setPadding(0, ChromeSheet.dp(this@MainActivity, 6), 0, 0)
            })
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ChromeSheet.dp(this@MainActivity, 4)
                marginEnd = ChromeSheet.dp(this@MainActivity, 4)
            }
        }
    }

    private fun showSettings() {
        SettingsDialogController(
            context = this,
            runtime = runtime,
            browserPreferences = browserPreferences,
            bookmarksStore = bookmarksStore,
            downloadStore = downloadStore,
            loginStore = loginStore,
            extensionManager = extensionManager,
            extensionsUi = extensionsUi,
            downloadStatus = ::downloadStatus,
            openDownload = ::openDownload,
            removeDownload = ::removeDownload,
            resetBrowserState = ::resetBrowserState,
            historyProvider = { historyStore.entries() },
            clearHistory = { historyStore.clear() },
            openUrl = ::openHistoryEntry,
            onPreferencesChanged = {
                searchRecommendationCache.clear()
                applyRuntimePreferences()
                applyDesktopModeToAllTabs(reloadActive = false)
                updateUi()
            }
        ).show()
    }

    private fun toggleBookmark() {
        val tab = tabs.activeTab ?: return
        if (tab.url.isBlank()) return
        val saved = bookmarksStore.toggle(tab.title, tab.url)
        Toast.makeText(this, if (saved) "Bookmark added" else "Bookmark removed", Toast.LENGTH_SHORT).show()
        updateUi()
    }

    private fun showBookmarksSheet() {
        ChromeSheet.show(
            context = this,
            title = "Bookmarks",
            subtitle = "Saved pages"
        ) { dialog ->
            val bookmarks = bookmarksStore.entries()
            if (bookmarks.isEmpty()) {
                addView(ChromeSheet.note(this@MainActivity, "Bookmarked pages will appear here."))
            } else {
                bookmarks.forEach { (title, url) ->
                    addView(ChromeSheet.row(
                        context = this@MainActivity,
                        title = title,
                        subtitle = url,
                        onClick = {
                            dialog.dismiss()
                            tabs.activeTab?.session?.loadUri(url)
                            setChromeVisible(true)
                        }
                    ))
                }
                addView(ChromeSheet.actionButton(this@MainActivity, "Clear bookmarks", danger = true) {
                    bookmarksStore.clear()
                    dialog.dismiss()
                    Toast.makeText(this@MainActivity, "Bookmarks cleared", Toast.LENGTH_SHORT).show()
                })
            }
        }
    }

    private fun showDownloadsSheet() {
        ChromeSheet.show(
            context = this,
            title = "Downloads",
            subtitle = "Recent files sent to Android downloads"
        ) { dialog ->
            val downloads = downloadStore.entries()
            if (downloads.isEmpty()) {
                addView(ChromeSheet.note(this@MainActivity, "Files you download will appear here after they start."))
            } else {
                downloads.forEach { entry ->
                    addView(ChromeSheet.row(
                        context = this@MainActivity,
                        title = entry.title,
                        subtitle = "${downloadStatus(entry)} - ${entry.url}",
                        onClick = { showDownloadActions(entry) }
                    ))
                }
            }
            addView(actionRow(
                ChromeSheet.actionButton(this@MainActivity, "Open Downloads") {
                    openSystemDownloads()
                },
                ChromeSheet.actionButton(this@MainActivity, "Clear", danger = true) {
                    downloadStore.clear()
                    dialog.dismiss()
                    Toast.makeText(this@MainActivity, "Download history cleared", Toast.LENGTH_SHORT).show()
                }
            ))
        }
    }

    private fun showDownloadActions(entry: DownloadStore.Entry) {
        ChromeSheet.show(
            context = this,
            title = entry.title,
            subtitle = downloadStatus(entry),
            scrollable = false
        ) { dialog ->
            addView(actionRow(
                ChromeSheet.actionButton(this@MainActivity, "Open", primary = true) {
                    dialog.dismiss()
                    openDownload(entry)
                },
                ChromeSheet.actionButton(this@MainActivity, "Remove", danger = true) {
                    dialog.dismiss()
                    removeDownload(entry)
                }
            ))
            addView(ChromeSheet.actionButton(this@MainActivity, "Copy source") {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Download source", entry.url))
                Toast.makeText(this@MainActivity, "Source copied", Toast.LENGTH_SHORT).show()
            })
        }
    }

    private fun openDownload(entry: DownloadStore.Entry) {
        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        if (entry.id >= 0) {
            val uri = downloadManager.getUriForDownloadedFile(entry.id)
            val mimeType = downloadManager.getMimeTypeForDownloadedFile(entry.id) ?: "*/*"
            if (uri != null) {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { startActivity(intent) }.getOrElse {
                    Toast.makeText(this, "Could not open download", Toast.LENGTH_SHORT).show()
                }
                return
            }
        }
        openSystemDownloads()
    }

    private fun removeDownload(entry: DownloadStore.Entry) {
        if (entry.id >= 0) {
            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            runCatching { downloadManager.remove(entry.id) }
        }
        downloadStore.remove(entry.id)
        Toast.makeText(this, "Download removed", Toast.LENGTH_SHORT).show()
    }

    private fun downloadStatus(entry: DownloadStore.Entry): String {
        if (entry.id < 0) return "Recorded"
        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = downloadManager.query(DownloadManager.Query().setFilterById(entry.id)) ?: return "Unknown"
        cursor.use {
            if (!it.moveToFirst()) return "Missing"
            val statusIndex = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val reasonIndex = it.getColumnIndex(DownloadManager.COLUMN_REASON)
            val status = if (statusIndex >= 0) it.getInt(statusIndex) else return "Unknown"
            val reason = if (reasonIndex >= 0) it.getInt(reasonIndex) else 0
            return when (status) {
                DownloadManager.STATUS_PENDING -> "Pending"
                DownloadManager.STATUS_RUNNING -> "Downloading"
                DownloadManager.STATUS_PAUSED -> "Paused ($reason)"
                DownloadManager.STATUS_SUCCESSFUL -> "Downloaded"
                DownloadManager.STATUS_FAILED -> "Failed ($reason)"
                else -> "Unknown"
            }
        }
    }

    private fun undoCloseTab() {
        val closed = lastClosedNormalTab
        if (closed?.url.isNullOrBlank()) {
            Toast.makeText(this, "No normal tab to restore", Toast.LENGTH_SHORT).show()
            return
        }
        val tab = tabs.create(TabMode.NORMAL)
        attachTab(tab)
        tab.title = closed.title
        tab.session.loadUri(closed.url)
        lastClosedNormalTab = null
        setChromeVisible(true)
    }

    private fun closeNormalTabs() {
        closeTabs(TabMode.NORMAL)
    }

    private fun closeTabs(mode: TabMode) {
        tabs.all().filter { it.mode == mode }.forEach { tab ->
            rememberClosedTab(tab)
            tabThumbnails.remove(tab.id)
            tabs.close(tab.id)
        }
        if (mode == TabMode.NORMAL) {
            sessionStore.clear()
        }
        val active = tabs.activeTab ?: tabs.create(TabMode.NORMAL)
        attachTab(active)
        if (active.url.isBlank() && active.sessionState.isBlank()) {
            openHome(active)
        }
        saveNormalSession()
        updateUi()
        Toast.makeText(
            this,
            if (mode == TabMode.PRIVATE) "Private tabs closed" else "Normal tabs closed",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun rememberClosedTab(tab: BrowserTab) {
        if (!tab.isPrivate && tab.url.isNotBlank()) {
            lastClosedNormalTab = ClosedTab(tab.title.ifBlank { tab.url }, tab.url)
        }
    }

    private fun openSystemDownloads() {
        val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
        runCatching { startActivity(intent) }.getOrElse {
            Toast.makeText(this, "Could not open Downloads", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareCurrentPage() {
        val tab = tabs.activeTab ?: return
        val text = listOf(tab.title, tab.url).filter { it.isNotBlank() }.joinToString("\n")
        if (text.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, tab.title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Share page"))
    }

    private fun openReaderView() {
        val url = tabs.activeTab?.url.orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(this, "Reader view needs a web page", Toast.LENGTH_SHORT).show()
            return
        }
        val encoded = URLEncoder.encode(url, "UTF-8")
        tabs.activeTab?.session?.loadUri("about:reader?url=$encoded")
        setChromeVisible(true)
    }

    private fun copyCurrentLink() {
        val url = tabs.activeTab?.url.orEmpty()
        if (url.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Page link", url))
        Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
    }

    private fun showFindSheet() {
        val input = EditText(this).apply {
            hint = "Find text"
            setSingleLine(true)
            setTextColor(getColor(R.color.kernel_text))
            setHintTextColor(getColor(R.color.kernel_muted))
        }
        ChromeSheet.show(
            context = this,
            title = "Find in page",
            subtitle = "Search the current page",
            scrollable = false
        ) { dialog ->
            addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ChromeSheet.dp(this@MainActivity, 52)
            ).apply {
                bottomMargin = ChromeSheet.dp(this@MainActivity, 10)
            })
            addView(actionRow(
                ChromeSheet.actionButton(this@MainActivity, "Previous") {
                    findInPage(input.text.toString(), backwards = true)
                },
                ChromeSheet.actionButton(this@MainActivity, "Next", primary = true) {
                    findInPage(input.text.toString(), backwards = false)
                }
            ))
            addView(ChromeSheet.actionButton(this@MainActivity, "Clear") {
                tabs.activeTab?.session?.finder?.clear()
                dialog.dismiss()
            })
        }
        input.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun findInPage(query: String, backwards: Boolean) {
        if (query.isBlank()) return
        val finder = tabs.activeTab?.session?.finder ?: return
        finder.displayFlags = GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL
        val flags = if (backwards) GeckoSession.FINDER_FIND_BACKWARDS else GeckoSession.FINDER_FIND_FORWARD
        finder.find(query, flags)
    }

    private fun showPageZoomSheet() {
        ChromeSheet.show(
            context = this,
            title = "Page zoom",
            subtitle = "Change page text size across tabs",
            scrollable = false
        ) {
            addView(ChromeSheet.row(
                context = this@MainActivity,
                title = "Current zoom",
                subtitle = "${browserPreferences.pageZoomPercent}%",
                trailing = ChromeSheet.statusPill(this@MainActivity, "${browserPreferences.pageZoomPercent}%", true)
            ))
            addView(actionRow(
                ChromeSheet.actionButton(this@MainActivity, "-") {
                    browserPreferences.pageZoomPercent -= 10
                    applyRuntimePreferences()
                    Toast.makeText(this@MainActivity, "Zoom ${browserPreferences.pageZoomPercent}%", Toast.LENGTH_SHORT).show()
                },
                ChromeSheet.actionButton(this@MainActivity, "+", primary = true) {
                    browserPreferences.pageZoomPercent += 10
                    applyRuntimePreferences()
                    Toast.makeText(this@MainActivity, "Zoom ${browserPreferences.pageZoomPercent}%", Toast.LENGTH_SHORT).show()
                }
            ))
            addView(ChromeSheet.actionButton(this@MainActivity, "Reset") {
                browserPreferences.pageZoomPercent = 100
                applyRuntimePreferences()
                Toast.makeText(this@MainActivity, "Zoom reset", Toast.LENGTH_SHORT).show()
            })
        }
    }

    private fun showSiteInfo() {
        val tab = tabs.activeTab
        ChromeSheet.show(
            context = this,
            title = "Site info",
            subtitle = tab?.securityHost?.ifBlank { tab.url }.orEmpty()
        ) {
            addView(ChromeSheet.row(
                context = this@MainActivity,
                title = if (tab?.isSecure == true) "Connection secure" else "Connection not verified",
                subtitle = tab?.url.orEmpty(),
                trailing = ChromeSheet.statusPill(this@MainActivity, if (tab?.isSecure == true) "HTTPS" else "Info", tab?.isSecure == true)
            ))
            addView(ChromeSheet.row(
                context = this@MainActivity,
                title = "Clear site data",
                subtitle = "Clear cookies, cache, permissions, and storage for this site.",
                onClick = { clearCurrentSiteData() }
            ))
        }
    }

    private fun clearCurrentSiteData() {
        val host = currentHost()
        if (host.isNullOrBlank()) {
            Toast.makeText(this, "No site data to clear", Toast.LENGTH_SHORT).show()
            return
        }
        runtime.storageController.clearDataFromBaseDomain(host, org.mozilla.geckoview.StorageController.ClearFlags.ALL).accept(
            { Toast.makeText(this, "Site data cleared for $host", Toast.LENGTH_SHORT).show() },
            {
                SafeLog.warning("Clearing site data failed", it)
                Toast.makeText(this, "Could not clear site data", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun currentHost(): String? {
        val tab = tabs.activeTab ?: return null
        return tab.securityHost.ifBlank {
            runCatching { Uri.parse(tab.url).host.orEmpty() }.getOrDefault("")
        }.takeIf { it.isNotBlank() }
    }

    private fun securitySummary(tab: BrowserTab?): String {
        return when {
            tab?.isSecure == true -> "Secure connection to ${tab.securityHost.ifBlank { "this site" }}."
            tab?.url?.startsWith("https://") == true -> "HTTPS loaded; certificate state is pending."
            tab?.url.isNullOrBlank() -> "No page loaded."
            else -> "Connection is not HTTPS."
        }
    }

    private fun applyDesktopModeToAllTabs(reloadActive: Boolean) {
        tabs.all().forEach(::applyDesktopMode)
        if (reloadActive) {
            tabs.activeTab?.session?.reload()
        }
        updateUi()
    }

    private fun applyRuntimePreferences() {
        runtime.settings
            .setFontSizeFactor(browserPreferences.pageZoomPercent / 100f)
            .setLoginAutofillEnabled(browserPreferences.loginAutofill)
            .setJavaScriptEnabled(browserPreferences.javascriptEnabled)
            .setGlobalPrivacyControl(browserPreferences.trackingProtection)
            .setFingerprintingProtection(browserPreferences.trackingProtection)
        runtime.settings.contentBlocking
            .setAntiTracking(if (browserPreferences.trackingProtection) ContentBlocking.AntiTracking.DEFAULT else ContentBlocking.AntiTracking.NONE)
            .setSafeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
            .setCookieBehavior(
                if (browserPreferences.blockThirdPartyCookies) {
                    ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS
                } else {
                    ContentBlocking.CookieBehavior.ACCEPT_ALL
                }
            )
            .setCookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
            .setCookiePurging(browserPreferences.trackingProtection)
            .setEnhancedTrackingProtectionLevel(
                if (browserPreferences.trackingProtection) {
                    ContentBlocking.EtpLevel.DEFAULT
                } else {
                    ContentBlocking.EtpLevel.NONE
                }
            )
    }

    private fun applyDesktopMode(tab: BrowserTab) {
        val settings = tab.session.settings
        settings.useTrackingProtection = browserPreferences.trackingProtection
        settings.allowJavascript = browserPreferences.javascriptEnabled
        if (browserPreferences.desktopMode) {
            settings.userAgentMode = GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
            settings.viewportMode = GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
        } else {
            settings.userAgentMode = GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            settings.viewportMode = GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        }
    }

    private fun startDownload(response: WebResponse) {
        val uri = response.uri.takeIf { it.isNotBlank() } ?: return
        // Android 9 and older need the storage permission at runtime for public Downloads.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownloads += response
            if (pendingDownloads.size == 1) {
                requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_STORAGE_PERMISSION)
            }
            return
        }
        enqueueDownload(response, publicDownloads = true)
    }

    private fun enqueueDownload(response: WebResponse, publicDownloads: Boolean) {
        val uri = response.uri
        runCatching {
            val parsedUri = Uri.parse(uri)
            val filename = android.webkit.URLUtil.guessFileName(
                uri,
                response.headers["Content-Disposition"] ?: response.headers["content-disposition"],
                response.headers["Content-Type"] ?: response.headers["content-type"]
            ).ifBlank { "kernel-download" }
            val request = DownloadManager.Request(parsedUri)
                .setTitle(filename)
                .setDescription(uri)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .addRequestHeader("User-Agent", "Mozilla/5.0 (Android; Mobile) Gecko Firefox KernelBrowser/${BuildConfig.VERSION_NAME}")
            if (publicDownloads) {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
            } else {
                request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, filename)
            }
            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = downloadManager.enqueue(request)
            downloadStore.record(id, filename, uri, filename)
            Toast.makeText(this, "Downloading $filename", Toast.LENGTH_SHORT).show()
        }.getOrElse {
            SafeLog.warning("Download failed", it)
            Toast.makeText(this, "Could not start download", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFilePicker(prompt: GeckoSession.PromptDelegate.FilePrompt) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = prompt.mimeTypes?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        runCatching {
            startActivityForResult(Intent.createChooser(intent, "Choose file"), REQUEST_FILE_PICKER)
        }.getOrElse {
            pendingFileResult?.complete(prompt.dismiss())
            pendingFilePrompt = null
            pendingFileResult = null
            Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_EXTENSION_FILE) {
            data?.data?.takeIf { resultCode == RESULT_OK }?.let(::installExtensionFromFile)
            return
        }
        if (requestCode == REQUEST_FILE_PICKER) {
            val prompt = pendingFilePrompt
            val result = pendingFileResult
            pendingFilePrompt = null
            pendingFileResult = null
            if (prompt != null && result != null) {
                if (resultCode == RESULT_OK && data != null) {
                    val uris = selectedUris(data)
                    val response = when (uris.size) {
                        0 -> prompt.dismiss()
                        1 -> prompt.confirm(this, uris.first())
                        else -> prompt.confirm(this, uris.toTypedArray())
                    }
                    result.complete(response)
                } else {
                    result.complete(prompt.dismiss())
                }
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun selectedUris(data: Intent): List<Uri> {
        val clipData = data.clipData
        if (clipData != null) {
            return List(clipData.itemCount) { index -> clipData.getItemAt(index).uri }
        }
        return data.data?.let { listOf(it) }.orEmpty()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            val downloads = pendingDownloads.toList()
            pendingDownloads.clear()
            val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            // Without the permission, fall back to the app's own Downloads folder instead of failing.
            downloads.forEach { enqueueDownload(it, publicDownloads = granted) }
            return
        }
        if (requestCode == REQUEST_ANDROID_PERMISSIONS) {
            val callback = pendingAndroidPermissionCallback
            pendingAndroidPermissionCallback = null
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                callback?.grant()
            } else {
                callback?.reject()
            }
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun contentPermissionName(permission: Int): String {
        return when (permission) {
            GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> "location"
            GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> "notifications"
            GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> "persistent storage"
            GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE -> "audible autoplay"
            GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> "inaudible autoplay"
            GeckoSession.PermissionDelegate.PERMISSION_STORAGE_ACCESS -> "storage access"
            GeckoSession.PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS -> "local network access"
            GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> "protected content (DRM) playback"
            else -> "this permission"
        }
    }

    /** Autoplay requests are answered silently from the Autoplay setting instead of a dialog per video. */
    private fun autoplayDecision(permission: Int): Int? {
        val allow = GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
        val deny = GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
        val policy = browserPreferences.autoplay
        return when (permission) {
            GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> if (policy == AutoplayPolicy.BLOCK_ALL) deny else allow
            GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE -> if (policy == AutoplayPolicy.ALLOW_ALL) allow else deny
            else -> null
        }
    }

    private fun scheduleSessionSave() {
        chromeHandler.removeCallbacks(saveSessionRunnable)
        chromeHandler.postDelayed(saveSessionRunnable, SESSION_SAVE_DELAY_MS)
    }

    private fun updateKeepScreenOn() {
        playingSessions.retainAll { it.isOpen }
        val activeSession = tabs.activeTab?.session
        if (activeSession != null && activeSession in playingSessions) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun onFullScreenChanged(session: GeckoSession, fullScreen: Boolean) {
        if (fullScreen) {
            if (session != tabs.activeTab?.session) return
            fullScreenSession = session
            cancelChromeAutoHide()
            hideAddressSuggestions()
            binding.bottomChrome.animate().cancel()
            binding.bottomChrome.visibility = View.GONE
            binding.progressBar.visibility = View.GONE
            binding.geckoView.setVerticalClipping(0)
            binding.geckoView.setDynamicToolbarMaxHeight(0)
            setImmersiveBars(true)
        } else {
            if (fullScreenSession == null) return
            fullScreenSession = null
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            setImmersiveBars(false)
            binding.bottomChrome.visibility = View.VISIBLE
            chromeHidden = true
            setChromeVisible(true)
            binding.root.post { applyChromeWidth() }
            updateUi()
        }
    }

    private fun setImmersiveBars(enabled: Boolean) {
        window.attributes = window.attributes.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = if (enabled) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController ?: return
            if (enabled) {
                controller.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(android.view.WindowInsets.Type.systemBars())
            } else {
                controller.show(android.view.WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (enabled) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            } else {
                0
            }
        }
    }

    private fun recoverSession(tab: BrowserTab, message: String?) {
        playingSessions -= tab.session
        updateKeepScreenOn()
        if (fullScreenSession == tab.session) onFullScreenChanged(tab.session, false)
        if (extensionActiveSession == tab.session) extensionActiveSession = null
        if (!tab.session.isOpen) tab.session.open(runtime)
        if (tab == tabs.activeTab) {
            markExtensionActiveTab(tab.session)
            if (tab.url.isBlank()) openHome(tab) else tab.session.loadUri(tab.url)
            message?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun isExtensionPackage(uri: String?, contentType: String?): Boolean {
        if (contentType?.substringBefore(';')?.trim()?.equals("application/x-xpinstall", ignoreCase = true) == true) return true
        val path = runCatching { Uri.parse(uri).path }.getOrNull() ?: return false
        return (uri?.startsWith("https://") == true) && path.endsWith(".xpi", ignoreCase = true)
    }

    private fun openInNewTab(url: String) {
        val tab = tabs.create(TabMode.NORMAL)
        attachTab(tab)
        tab.session.loadUri(url)
        setChromeVisible(true)
    }

    private fun pickExtensionFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/x-xpinstall", "application/zip", "application/octet-stream"))
        }
        runCatching { startActivityForResult(intent, REQUEST_EXTENSION_FILE) }.getOrElse {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun installExtensionFromFile(uri: Uri) {
        Thread {
            val copied = runCatching {
                val dir = File(cacheDir, "xpi").apply { mkdirs() }
                // Clear leftovers from earlier sessions only; a recent file may belong to an
                // install whose permission prompt is still open.
                dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 60 * 60 * 1000L }?.forEach { it.delete() }
                val target = File(dir, "install-${System.currentTimeMillis()}.xpi")
                contentResolver.openInputStream(uri)!!.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
                target
            }.getOrNull()
            runOnUiThread {
                if (copied == null) {
                    Toast.makeText(this, "Could not read that file", Toast.LENGTH_SHORT).show()
                } else {
                    extensionManager.install(Uri.fromFile(copied).toString(), WebExtensionController.INSTALLATION_METHOD_FROM_FILE)
                }
            }
        }.start()
    }

    private val extensionHost = object : ExtensionManager.Host {
        override fun confirmInstall(
            extension: WebExtension,
            permissions: List<String>,
            origins: List<String>,
            onResult: (granted: Boolean, allowInPrivate: Boolean) -> Unit
        ) {
            if (isFinishing || isDestroyed) {
                onResult(false, false)
                return
            }
            extensionsUi.confirmInstall(extension, permissions, origins, onResult)
        }

        override fun confirmOptionalPermissions(extension: WebExtension, lines: List<String>, onResult: (Boolean) -> Unit) {
            if (isFinishing || isDestroyed) {
                onResult(false)
                return
            }
            extensionsUi.confirmOptionalPermissions(extension, lines, onResult)
        }

        override fun newExtensionTab(url: String?, active: Boolean): GeckoSession {
            val previous = tabs.activeTab
            val tab = tabs.create(TabMode.NORMAL, open = false)
            tab.url = url.orEmpty()
            if (active || previous == null) {
                attachTab(tab)
                setChromeVisible(true)
            } else {
                tabs.select(previous.id)
            }
            updateUi()
            return tab.session
        }

        override fun openUrlInNewTab(url: String) = openInNewTab(url)

        override fun showMessage(message: String) {
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }

        override fun onExtensionsChanged() {
            updateUi()
            extensionsUi.onExtensionsChanged()
        }
    }

    private fun saveNormalSession() {
        sessionStore.save(tabs.all().filterNot { it.isPrivate }.map { tab ->
            // A blank url means the start page (or a fresh tab): store a marker, not the data: URI state.
            SessionStore.Entry(
                title = tab.title,
                url = tab.url.ifBlank { StartPage.SESSION_URL },
                state = if (tab.url.isBlank()) "" else tab.sessionState
            )
        })
    }

    private fun actionRow(left: View, right: View): LinearLayout {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = ChromeSheet.dp(this@MainActivity, 6)
            })
            addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ChromeSheet.dp(this@MainActivity, 6)
            })
        }
    }

    private fun recordHistory(tab: BrowserTab) {
        if (tab.isPrivate || tab.url.isBlank() || isHomeUrl(tab.url)) return
        val title = tab.title.ifBlank { tab.url }
        historyStore.record(title, tab.url)
    }

    private fun isHomeUrl(url: String): Boolean {
        return url.trimEnd('/') == browserPreferences.homepageUrl.trimEnd('/')
    }

    private fun openHistoryEntry(url: String) {
        tabs.activeTab?.session?.loadUri(url)
        setChromeVisible(true)
    }

    private fun showTabsDialog() {
        cancelChromeAutoHide()
        tabs.activeTab?.let { captureThumbnail(it) }
        TabSwitcherDialogController(
            context = this,
            tabs = tabs,
            thumbnailProvider = { tab -> tabThumbnails[tab.id] },
            selectTab = { tab ->
                attachTab(tab)
                setChromeVisible(true)
            },
            createTab = { mode ->
                val tab = tabs.create(mode)
                attachTab(tab)
                openHome(tab)
                setChromeVisible(true)
            },
            closeTab = { tab ->
                rememberClosedTab(tab)
                tabThumbnails.remove(tab.id)
                tabs.close(tab.id)
                attachCurrentOrCreate()
                saveNormalSession()
                updateUi()
            },
            closeTabs = { mode -> closeTabs(mode) },
            undoCloseTab = ::undoCloseTab
        ).show()
    }

    private fun resetBrowserState() {
        sessionStore.clear()
        tabs.all().map { it.id }.forEach {
            tabThumbnails.remove(it)
            tabs.close(it)
        }
        val tab = tabs.create(TabMode.NORMAL)
        attachTab(tab)
        openHome(tab)
    }

    private fun attachCurrentOrCreate() {
        val active = tabs.activeTab ?: tabs.create(TabMode.NORMAL)
        attachTab(active)
        if (active.url.isBlank() && active.sessionState.isBlank()) {
            openHome(active)
        }
    }

    /** Loads the start page (or the homepage URL when the start page setting is off) into [tab]. */
    private fun openHome(tab: BrowserTab) {
        if (!browserPreferences.newTabPage) {
            tab.session.loadUri(browserPreferences.homepageUrl)
            return
        }
        val html = StartPage.render(
            bookmarks = bookmarksStore.entries(),
            topSites = StartPage.topSites(historyStore.entries()),
            searchEngine = browserPreferences.searchEngine
        )
        tab.url = ""
        if (tab == tabs.activeTab && !binding.addressBar.hasFocus()) {
            binding.addressBar.setText("")
        }
        tab.session.loadUri(StartPage.dataUri(html))
    }

    private fun attachTab(tab: BrowserTab) {
        tabs.activeTab?.let { active ->
            captureThumbnail(active)
            fullScreenSession?.takeIf { it == active.session && active != tab }?.exitFullScreen()
        }
        // Leaving the fullscreen tab (switching, window.open, close, crash) must exit page
        // fullscreen too, or Back can no longer get out of it. tabs.create() has already made the
        // new tab "active" here, so compare against the fullscreen session directly.
        fullScreenSession?.takeIf { it != tab.session }?.let {
            if (it.isOpen) it.exitFullScreen()
            onFullScreenChanged(it, false)
        }
        tabs.select(tab.id)
        markExtensionActiveTab(tab.session)
        binding.geckoView.setSession(tab.session)
        binding.addressBar.setText(tab.url)
        updatePrivateWindowProtection(tab)
        updateKeepScreenOn()
        updateUi()
    }

    /** Extensions resolve "the current tab" (browser-action clicks, tabs.query) from this. */
    private fun markExtensionActiveTab(session: GeckoSession) {
        if (session == extensionActiveSession || !session.isOpen) return
        extensionActiveSession?.takeIf { it.isOpen }?.let { runtime.webExtensionController.setTabActive(it, false) }
        extensionActiveSession = session
        runtime.webExtensionController.setTabActive(session, true)
    }

    private fun updatePrivateWindowProtection(tab: BrowserTab?) {
        if (tab?.isPrivate == true) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun captureThumbnail(tab: BrowserTab) {
        if (tab.session != tabs.activeTab?.session || binding.geckoView.width <= 0 || binding.geckoView.height <= 0) return
        binding.geckoView.capturePixels().accept({ bitmap ->
            val source = bitmap ?: return@accept
            val targetSize = ChromeSheet.dp(this, 240).coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(source, targetSize, targetSize, true)
            tabThumbnails.remove(tab.id)?.recycle()
            tabThumbnails[tab.id] = scaled
            if (source !== scaled) {
                source.recycle()
            }
        }, {
            // Thumbnails are best-effort; browsing should never wait on capture.
        })
    }

    private fun updateUi() {
        val tab = tabs.activeTab
        binding.backButton.isEnabled = tab?.canGoBack == true
        binding.forwardButton.isEnabled = tab?.canGoForward == true
        val loading = tab?.isLoading == true
        binding.reloadButton.text = ""
        binding.reloadButton.setCompoundDrawablesWithIntrinsicBounds(
            0,
            if (loading) R.drawable.ic_close else R.drawable.ic_refresh,
            0,
            0
        )
        binding.tabsButton.text = tabs.count().toString()
        binding.extensionsButton.text = extensionActionRegistry.entries()
            .size
            .takeIf { it > 0 }
            ?.toString()
            .orEmpty()
        binding.backButton.contentDescription = getString(R.string.action_back)
        binding.forwardButton.contentDescription = getString(R.string.action_forward)
        binding.reloadButton.contentDescription = if (loading) {
            getString(R.string.action_stop)
        } else {
            getString(R.string.action_reload)
        }
        binding.homeButton.contentDescription = getString(R.string.action_home)
        binding.tabsButton.contentDescription = "${tabs.count()} tabs"
        binding.extensionsButton.contentDescription = "${extensionActionRegistry.entries().size} extensions"
        binding.settingsButton.contentDescription = getString(R.string.action_settings)
        binding.securityIcon.imageTintList = ColorStateList.valueOf(
            getColor(if (tab?.isSecure == true) R.color.kernel_accent else R.color.kernel_muted)
        )
        binding.securityIcon.contentDescription = securitySummary(tab)
        binding.progressBar.visibility = if (loading && fullScreenSession == null) View.VISIBLE else View.GONE
        binding.progressBar.progress = tab?.progress ?: 0
        title = tab?.title?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name)
    }

    private companion object {
        const val REQUEST_FILE_PICKER = 41
        const val REQUEST_ANDROID_PERMISSIONS = 42
        const val REQUEST_EXTENSION_FILE = 43
        const val REQUEST_STORAGE_PERMISSION = 44
        const val SESSION_SAVE_DELAY_MS = 1_500L
        const val SUGGESTION_DEBOUNCE_MS = 150L
    }
}
