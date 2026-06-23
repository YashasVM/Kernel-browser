package com.kernel.browser

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.app.Activity
import android.content.res.Configuration
import android.widget.FrameLayout
import com.kernel.browser.databinding.ActivityMainBinding
import com.kernel.browser.extensions.ExtensionActionRegistry
import com.kernel.browser.extensions.ExtensionInstaller
import com.kernel.browser.extensions.ExtensionPreferences
import com.kernel.browser.tabs.BrowserTab
import com.kernel.browser.tabs.BrowserTabs
import com.kernel.browser.tabs.TabMode
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession

class MainActivity : Activity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var runtime: GeckoRuntime
    private lateinit var tabs: BrowserTabs
    private lateinit var extensionPreferences: ExtensionPreferences
    private lateinit var extensionInstaller: ExtensionInstaller
    private lateinit var extensionActionRegistry: ExtensionActionRegistry
    private lateinit var historyStore: BrowserHistoryStore
    private val tabThumbnails = mutableMapOf<Long, Bitmap>()
    private var bottomInset = 0
    private var chromeHidden = false
    private var addressBarEditing = false
    private val chromeHandler = Handler(Looper.getMainLooper())
    private val chromeInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val hideChromeRunnable = Runnable { setChromeVisible(false, auto = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureSystemBars()

        historyStore = BrowserHistoryStore(this)
        runtime = BrowserRuntime.get(this)
        extensionPreferences = ExtensionPreferences(this)
        extensionActionRegistry = ExtensionActionRegistry(this, runtime) {
            runOnUiThread { updateUi() }
        }
        extensionInstaller = ExtensionInstaller(runtime, extensionPreferences, extensionActionRegistry)
        extensionInstaller.syncAllowlist()

        tabs = BrowserTabs(runtime, ::configureSession)
        val firstTab = tabs.create(TabMode.NORMAL)
        attachTab(firstTab)
        firstTab.session.loadUri(getString(R.string.home_url))

        configureToolbar()
        configureInsets()
        updateUi()
    }

    override fun onDestroy() {
        if (isFinishing) {
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
            tabs.activeTab?.session?.loadUri(getString(R.string.home_url))
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
            SettingsDialogController(
                context = this,
                runtime = runtime,
                extensionPreferences = extensionPreferences,
                extensionInstaller = extensionInstaller,
                resetBrowserState = ::resetBrowserState,
                historyProvider = { historyStore.entries() },
                openHistoryEntry = ::openHistoryEntry,
                clearHistory = { historyStore.clear() }
            ).show()
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
            } else {
                scheduleChromeAutoHide()
            }
        }
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
            binding.bottomChrome.post { updateGeckoChromeInsets() }
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
        binding.addressBar.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.addressBar.windowToken, 0)
        setChromeVisible(true)
    }

    private fun setChromeVisible(visible: Boolean, auto: Boolean = false) {
        if (chromeHidden == !visible) {
            if (visible) scheduleChromeAutoHide()
            return
        }
        if (!visible || auto) {
            cancelChromeAutoHide()
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
