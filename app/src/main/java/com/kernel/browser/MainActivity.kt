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
import com.kernel.browser.extensions.ExtensionInstaller
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
import org.mozilla.geckoview.WebResponse
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : Activity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var runtime: GeckoRuntime
    private lateinit var tabs: BrowserTabs
    private lateinit var extensionPreferences: ExtensionPreferences
    private lateinit var extensionInstaller: ExtensionInstaller
    private lateinit var extensionActionRegistry: ExtensionActionRegistry
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
    private val searchRecommendationCache = mutableMapOf<String, List<String>>()
    private val chromeHandler = Handler(Looper.getMainLooper())
    private val chromeInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val hideChromeRunnable = Runnable { setChromeVisible(false, auto = true) }
    private var pendingAndroidPermissionCallback: GeckoSession.PermissionDelegate.Callback? = null
    private var pendingFilePrompt: GeckoSession.PromptDelegate.FilePrompt? = null
    private var pendingFileResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? = null
    private var lastClosedNormalTab: ClosedTab? = null

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
            runOnUiThread { updateUi() }
        }
        extensionInstaller = ExtensionInstaller(runtime, extensionPreferences, extensionActionRegistry)
        extensionInstaller.syncAllowlist()

        tabs = BrowserTabs(runtime, ::configureSession)
        restoreTabsOrCreateHome(intent)

        configureToolbar()
        configureInsets()
        updateUi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openIntentUri(intent)
    }

    override fun onPause() {
        saveNormalSession()
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing) {
            saveNormalSession()
            tabs.closePrivateTabs()
        }
        super.onDestroy()
    }

    override fun onBackPressed() {
        val tab = tabs.activeTab
        if (tab?.canGoBack == true) {
            tab.session.goBack()
        } else {
            super.onBackPressed()
        }
    }

    private fun restoreTabsOrCreateHome(intent: Intent?) {
        if (openIntentUri(intent)) return

        val restoredTabs = sessionStore.restore().ifEmpty {
            listOf(SessionStore.Entry("Home", browserPreferences.homepageUrl, ""))
        }
        restoredTabs.forEachIndexed { index, entry ->
            val tab = tabs.create(TabMode.NORMAL)
            tab.title = entry.title
            tab.url = entry.url
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
            tabs.activeTab?.session?.loadUri(browserPreferences.homepageUrl)
        }
        binding.tabsButton.setOnClickListener {
            setChromeVisible(true)
            showTabsDialog()
        }
        binding.extensionsButton.setOnClickListener {
            ExtensionsDialogController(
                context = this,
                actionRegistry = extensionActionRegistry,
                extensionPreferences = extensionPreferences,
                extensionInstaller = extensionInstaller
            ).show()
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
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
            if (actionId == EditorInfo.IME_ACTION_GO || isEnter) {
                loadFromAddressBar()
                true
            } else {
                false
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
        window.decorView.systemUiVisibility = 0
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
                captureThumbnail(tab)
                recordHistory(tab)
                updateUi()
            }

            override fun onCloseRequest(session: GeckoSession) {
                tabs.close(tab.id)
                attachCurrentOrCreate()
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
                tab.url = url.orEmpty()
                if (!binding.addressBar.hasFocus()) {
                    binding.addressBar.setText(tab.url)
                }
                captureThumbnail(tab)
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
                tab.isLoading = true
                tab.url = url
                setChromeVisible(true)
                updateUi()
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (session != tab.session) return
                tab.isLoading = false
                tab.progress = 0
                captureThumbnail(tab)
                recordHistory(tab)
                updateUi()
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                if (session != tab.session) return
                tab.progress = progress
                updateUi()
            }
        }
    }

    private fun loadFromAddressBar() {
        val normalized = UrlNormalizer.normalize(binding.addressBar.text.toString())
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
            SuggestionKind.NAVIGATE -> UrlNormalizer.normalize(suggestion.value)
            SuggestionKind.SEARCH -> UrlNormalizer.normalize(suggestion.value)
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
            AddressSuggestion(it, "Search Google", it, SuggestionKind.SEARCH)
        }).distinctBy { it.kind.name + it.value }.take(4))

        if (query.length >= 2 && cachedRecommendations.isEmpty()) {
            fetchSearchRecommendations(query)
        }
    }

    private fun localAddressSuggestions(query: String): List<AddressSuggestion> {
        val normalizedQuery = query.lowercase()
        val history = historyStore.entries()
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
            AddressSuggestion(query, "Search Google", query, SuggestionKind.SEARCH)
        }

        return listOf(primary) + matches
    }

    private fun fetchSearchRecommendations(query: String) {
        val requestId = ++suggestionRequestId
        Thread {
            val recommendations = runCatching {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val connection = URL("https://suggestqueries.google.com/complete/search?client=firefox&q=$encoded")
                    .openConnection() as HttpURLConnection
                connection.connectTimeout = 1_500
                connection.readTimeout = 1_500
                connection.setRequestProperty("User-Agent", "KernelBrowser/${BuildConfig.VERSION_NAME}")
                connection.inputStream.bufferedReader().use { reader ->
                    val payload = reader.readText()
                    val array = JSONArray(payload).getJSONArray(1)
                    List(array.length()) { index -> array.getString(index) }
                }
            }.getOrElse { emptyList() }

            chromeHandler.post {
                if (requestId != suggestionRequestId) return@post
                searchRecommendationCache[query.lowercase()] = recommendations
                if (binding.addressBar.hasFocus() && binding.addressBar.text.toString().trim() == query) {
                    updateAddressSuggestions()
                }
            }
        }.start()
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
        cancelChromeAutoHide()
        if (addressBarEditing) return
        chromeHandler.postDelayed(hideChromeRunnable, 4_000L)
    }

    private fun cancelChromeAutoHide() {
        chromeHandler.removeCallbacks(hideChromeRunnable)
    }

    private fun updateGeckoChromeInsets() {
        val chromeHeight = binding.bottomChrome.height
        if (chromeHeight <= 0) return
        val bottomMargin = (binding.bottomChrome.layoutParams as? FrameLayout.LayoutParams)?.bottomMargin ?: bottomInset
        val visibleClipping = chromeHeight + bottomMargin
        val currentClipping = (visibleClipping - binding.bottomChrome.translationY.toInt()).coerceAtLeast(bottomInset)
        binding.geckoView.setDynamicToolbarMaxHeight(visibleClipping)
        binding.geckoView.setVerticalClipping(currentClipping)
    }

    private fun recordHistory(tab: BrowserTab) {
        if (tab.isPrivate || tab.url.isBlank() || isHomeUrl(tab.url)) return
        val title = tab.title.ifBlank { tab.url }
        historyStore.record(title, tab.url)
    }

    private fun isHomeUrl(url: String): Boolean {
        return url.trimEnd('/') == getString(R.string.home_url).trimEnd('/')
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
                tab.session.loadUri(getString(R.string.home_url))
                setChromeVisible(true)
            },
            closeTab = { tab ->
                tabThumbnails.remove(tab.id)
                tabs.close(tab.id)
                attachCurrentOrCreate()
                updateUi()
            }
        ).show()
    }

    private fun resetBrowserState() {
        tabs.all().map { it.id }.forEach {
            tabThumbnails.remove(it)
            tabs.close(it)
        }
        val tab = tabs.create(TabMode.NORMAL)
        attachTab(tab)
        tab.session.loadUri(getString(R.string.home_url))
    }

    private fun attachCurrentOrCreate() {
        val active = tabs.activeTab ?: tabs.create(TabMode.NORMAL)
        attachTab(active)
        if (active.url.isBlank()) {
            active.session.loadUri(getString(R.string.home_url))
        }
    }

    private fun attachTab(tab: BrowserTab) {
        tabs.activeTab?.let { captureThumbnail(it) }
        tabs.select(tab.id)
        binding.geckoView.setSession(tab.session)
        binding.addressBar.setText(tab.url)
        updateUi()
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
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.progressBar.progress = tab?.progress ?: 0
        title = tab?.title?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name)
    }
}
