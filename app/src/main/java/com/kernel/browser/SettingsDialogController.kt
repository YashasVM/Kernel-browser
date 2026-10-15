package com.kernel.browser

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.kernel.browser.extensions.ExtensionManager
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.StorageController

/**
 * Full-screen Settings. Sub-pages slide in inside the same window instead of
 * stacking bottom sheets, and every page re-renders when you come back to it so
 * values shown on rows are always current.
 */
class SettingsDialogController(
    private val context: Context,
    private val runtime: GeckoRuntime,
    private val browserPreferences: BrowserPreferences,
    private val bookmarksStore: BookmarksStore,
    private val downloadStore: DownloadStore,
    private val loginStore: LoginStore,
    private val extensionManager: ExtensionManager,
    private val extensionsUi: ExtensionsDialogController,
    private val downloadStatus: (DownloadStore.Entry) -> String,
    private val openDownload: (DownloadStore.Entry) -> Unit,
    private val removeDownload: (DownloadStore.Entry) -> Unit,
    private val resetBrowserState: () -> Unit,
    private val historyStore: BrowserHistoryStore,
    private val openUrl: (String) -> Unit,
    private val onPreferencesChanged: () -> Unit
) {
    private val motion = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val stack = mutableListOf<PageEntry>()
    private lateinit var dialog: Dialog
    private lateinit var host: FrameLayout

    /** Set while the system default-browser screen is open, so its result shows on return. */
    private var awaitingDefaultBrowser = false

    private fun historyProvider(): List<Pair<String, String>> = historyStore.entries()

    private fun clearHistory() = historyStore.clear()

    private class Page(val title: String, val onLeave: () -> Unit = {}, val build: LinearLayout.() -> Unit)

    private class PageEntry(val page: Page, val view: View, val scroll: ScrollView, val content: LinearLayout)

    fun show() {
        dialog = Dialog(context, R.style.KernelFullScreenDialog)
        host = FrameLayout(context).apply {
            setBackgroundColor(context.getColor(R.color.kernel_background))
            fitsSystemWindows = true
        }
        dialog.setContentView(host)
        host.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
            if (hasFocus && awaitingDefaultBrowser) {
                awaitingDefaultBrowser = false
                refresh()
            }
        }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(context.getColor(R.color.kernel_background)))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            statusBarColor = context.getColor(R.color.kernel_background)
            navigationBarColor = context.getColor(R.color.kernel_background)
            setWindowAnimations(android.R.style.Animation_Activity)
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && stack.size > 1) {
                pop()
                true
            } else {
                false
            }
        }
        dialog.setOnDismissListener {
            stack.forEach { it.page.onLeave() }
            stack.clear()
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ (always on 16 with targetSdk 36) skips the key listener above.
            dialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { pop() }
        }
        push(mainPage(), animate = false)
        dialog.show()
    }

    // region navigation

    private fun push(page: Page, animate: Boolean = true) {
        val entry = buildEntry(page)
        val previous = stack.lastOrNull()
        stack += entry
        host.addView(entry.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (!animate || previous == null) return
        val width = host.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        entry.view.translationX = width.toFloat()
        entry.view.animate().translationX(0f).setDuration(280L).setInterpolator(motion).start()
        previous.view.animate().translationX(-width * 0.25f).alpha(0.6f).setDuration(280L).setInterpolator(motion)
            .withEndAction { previous.view.visibility = View.INVISIBLE }
            .start()
    }

    private fun pop() {
        if (stack.size <= 1) {
            dialog.dismiss()
            return
        }
        val top = stack.removeAt(stack.lastIndex)
        top.page.onLeave()
        val revealed = stack.last()
        render(revealed)
        revealed.view.visibility = View.VISIBLE
        revealed.view.animate().translationX(0f).alpha(1f).setDuration(260L).setInterpolator(motion).start()
        top.view.animate().translationX(host.width.toFloat()).setDuration(240L).setInterpolator(motion)
            .withEndAction { host.removeView(top.view) }
            .start()
    }

    private fun refresh() {
        stack.lastOrNull()?.let(::render)
    }

    private fun render(entry: PageEntry) {
        val scrollY = entry.scroll.scrollY
        entry.content.removeAllViews()
        entry.page.build(entry.content)
        entry.scroll.post { entry.scroll.scrollTo(0, scrollY) }
    }

    private fun buildEntry(page: Page): PageEntry {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(KernelUi.dp(context, 16), 0, KernelUi.dp(context, 16), KernelUi.dp(context, 40))
        }
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(context.getColor(R.color.kernel_background))
            isClickable = true
            addView(topBar(page.title))
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val entry = PageEntry(page, root, scroll, content)
        page.build(content)
        return entry
    }

    private fun topBar(title: String): LinearLayout {
        val isRoot = stack.isEmpty()
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = KernelUi.dp(context, 60)
            setPadding(KernelUi.dp(context, 6), KernelUi.dp(context, 4), KernelUi.dp(context, 16), 0)
            addView(ImageButton(context).apply {
                setImageResource(if (isRoot) R.drawable.ic_close else R.drawable.ic_arrow_back)
                imageTintList = ColorStateList.valueOf(context.getColor(R.color.kernel_text))
                background = KernelUi.selectableItemBackground(context)
                contentDescription = if (isRoot) "Close settings" else "Back"
                setOnClickListener { pop() }
            }, LinearLayout.LayoutParams(KernelUi.dp(context, 48), KernelUi.dp(context, 48)))
            addView(TextView(context).apply {
                text = title
                textSize = if (isRoot) 28f else 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(context.getColor(R.color.kernel_text))
                setPadding(KernelUi.dp(context, 8), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    // endregion

    // region main page

    private fun mainPage() = Page("Settings") {
        addView(KernelUi.group(context, defaultBrowserRow()))

        addView(KernelUi.sectionHeader(context, "General"))
        addView(KernelUi.group(
            context,
            KernelUi.item(context, "Search engine", icon = R.drawable.ic_search, tile = R.color.tile_blue,
                value = browserPreferences.searchEngine.displayName, chevron = true) { push(searchEnginePage()) },
            KernelUi.item(context, "Homepage", icon = R.drawable.ic_home, tile = R.color.tile_green,
                value = displayHost(browserPreferences.homepageUrl), chevron = true) { push(homepagePage()) },
            KernelUi.toggle(context, "Start page", subtitle = "Show top sites and bookmarks in new tabs and on Home.",
                icon = R.drawable.ic_tabs, tile = R.color.tile_teal, checked = browserPreferences.newTabPage) {
                browserPreferences.newTabPage = it
                onPreferencesChanged()
            },
            KernelUi.toggle(context, "Desktop site", subtitle = "Request full desktop pages by default.",
                icon = R.drawable.ic_desktop, tile = R.color.tile_indigo, checked = browserPreferences.desktopMode) {
                browserPreferences.desktopMode = it
                onPreferencesChanged()
            },
            KernelUi.item(context, "Text size", icon = R.drawable.ic_text_size, tile = R.color.tile_orange,
                value = "${browserPreferences.pageZoomPercent}%", chevron = true) { push(textSizePage()) },
            KernelUi.item(context, "Autoplay", icon = R.drawable.ic_play, tile = R.color.tile_pink,
                value = browserPreferences.autoplay.displayName, chevron = true) { push(autoplayPage()) },
            KernelUi.item(context, "Website theme", icon = R.drawable.ic_reader, tile = R.color.tile_purple,
                value = browserPreferences.websiteTheme.displayName, chevron = true) { push(websiteThemePage()) },
            KernelUi.toggle(context, "Zoom on all websites", subtitle = "Pinch to zoom even on sites that try to prevent it.",
                icon = R.drawable.ic_search, tile = R.color.tile_gray, checked = browserPreferences.forceEnableZoom) {
                browserPreferences.forceEnableZoom = it
                onPreferencesChanged()
            }
        ))

        addView(KernelUi.sectionHeader(context, "Privacy & security"))
        addView(KernelUi.group(
            context,
            KernelUi.item(context, "Tracking protection", subtitle = "Block trackers, cryptominers, and fingerprinting.",
                icon = R.drawable.ic_shield, tile = R.color.tile_green, value = trackingLevelLabel(), chevron = true) {
                push(trackingProtectionPage())
            },
            KernelUi.item(context, "HTTPS-Only Mode", icon = R.drawable.ic_lock, tile = R.color.tile_indigo,
                value = browserPreferences.httpsOnly.displayName, chevron = true) { push(httpsOnlyPage()) },
            KernelUi.toggle(context, "Isolate cross-site cookies", subtitle = "Stops sites following you around. Sign-ins keep working.",
                icon = R.drawable.ic_cookie, tile = R.color.tile_orange, checked = browserPreferences.blockThirdPartyCookies) {
                browserPreferences.blockThirdPartyCookies = it
                onPreferencesChanged()
            },
            KernelUi.toggle(context, "Reduce cookie banners", subtitle = "Automatically refuse cookie pop-ups on sites that support it.",
                icon = R.drawable.ic_cookie, tile = R.color.tile_teal, checked = browserPreferences.cookieBannerReduction) {
                browserPreferences.cookieBannerReduction = it
                onPreferencesChanged()
            },
            KernelUi.toggle(context, "JavaScript", subtitle = "Most sites need this. Turn off for a strict mode.",
                icon = R.drawable.ic_code, tile = R.color.tile_gray, checked = browserPreferences.javascriptEnabled) {
                browserPreferences.javascriptEnabled = it
                onPreferencesChanged()
            },
            KernelUi.item(context, "Passwords", icon = R.drawable.ic_key, tile = R.color.tile_teal,
                value = loginStore.summaries().size.let { if (it == 0) "None" else "$it saved" }, chevron = true) { push(passwordsPage()) },
            KernelUi.item(context, "Site permissions", icon = R.drawable.ic_lock, tile = R.color.tile_blue, chevron = true) {
                push(sitePermissionsPage())
            },
            KernelUi.item(context, "Clear browsing data", icon = R.drawable.ic_delete, tile = R.color.tile_red,
                value = if (browserPreferences.clearDataOnQuit) "On quit" else null, chevron = true) {
                push(clearDataPage())
            }
        ))

        addView(KernelUi.sectionHeader(context, "Extensions"))
        addView(KernelUi.group(
            context,
            KernelUi.item(context, "Extensions", subtitle = "Ad blockers, password managers, dark mode, and more.",
                icon = R.drawable.ic_extension, tile = R.color.tile_purple,
                value = "${extensionManager.enabledCount()} on", chevron = true) { push(extensionsPage()) },
            KernelUi.item(context, "Browse extensions", icon = R.drawable.ic_store, tile = R.color.tile_orange, chevron = true) {
                dialog.dismiss()
                openUrl(ExtensionsDialogController.AMO_ANDROID_URL)
            }
        ))

        addView(KernelUi.sectionHeader(context, "Library"))
        addView(KernelUi.group(
            context,
            KernelUi.item(context, "History", icon = R.drawable.ic_history, tile = R.color.tile_blue,
                value = historyProvider().size.takeIf { it > 0 }?.toString(), chevron = true) { push(historyPage()) },
            KernelUi.item(context, "Bookmarks", icon = R.drawable.ic_bookmark, tile = R.color.tile_orange,
                value = bookmarksStore.entries().size.takeIf { it > 0 }?.toString(), chevron = true) { push(bookmarksPage()) },
            KernelUi.item(context, "Downloads", icon = R.drawable.ic_download, tile = R.color.tile_green,
                value = downloadStore.entries().size.takeIf { it > 0 }?.toString(), chevron = true) { push(downloadsPage()) }
        ))

        addView(KernelUi.sectionHeader(context, "About"))
        addView(KernelUi.group(
            context,
            KernelUi.item(context, "Kernel Browser", icon = R.drawable.ic_info, tile = R.color.tile_gray,
                value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"),
            KernelUi.item(context, "Browser engine", icon = R.drawable.ic_globe, tile = R.color.tile_gray,
                value = "Gecko ${org.mozilla.geckoview.BuildConfig.MOZ_APP_VERSION}")
        ))
    }

    // endregion

    // region general pages

    private fun searchEnginePage() = Page("Search engine") {
        val custom = browserPreferences.customSearchEngine
        val selected = browserPreferences.searchEngine
        addView(KernelUi.sectionHeader(context, "Default search"))
        addView(KernelUi.group(context, (SearchEngine.all + listOfNotNull(custom)).map { engine ->
            val row = KernelUi.choice(
                context,
                title = engine.displayName,
                subtitle = when {
                    engine.isCustom -> displayHost(engine.searchUrl)
                    engine.suggestionsUrl != null -> "Includes search suggestions"
                    else -> null
                },
                selected = selected == engine
            ) {
                browserPreferences.searchEngineId = engine.id
                onPreferencesChanged()
                refresh()
            }
            if (engine.isCustom) {
                row.addView(removeButton("Remove ${engine.displayName}") {
                    confirm("Remove ${engine.displayName}?", "Search switches back to Google if it was your default.", "Remove") {
                        browserPreferences.removeCustomSearchEngine()
                        onPreferencesChanged()
                        refresh()
                    }
                })
            }
            row
        }))
        addView(KernelUi.sectionHeader(context, ""))
        addView(KernelUi.group(context, KernelUi.item(
            context,
            if (custom == null) "Add search engine" else "Replace custom search engine",
            icon = R.drawable.ic_add,
            tile = R.color.tile_blue,
            accent = true,
            chevron = true
        ) { push(addSearchEnginePage(custom)) }))
        addView(KernelUi.footer(context, "Used for anything you type in the address bar that isn't a web address."))
    }

    private fun addSearchEnginePage(existing: SearchEngine?) = Page("Add search engine") {
        val name = ChromeSheet.textField(context, "Name", existing?.displayName)
        val url = ChromeSheet.textField(context, "https://example.com/search?q=%s", existing?.searchUrl).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val error = KernelUi.footer(context, "").apply {
            setTextColor(context.getColor(R.color.kernel_danger))
            visibility = View.GONE
        }
        fun save() {
            when (val result = CustomSearchEngines.validate(name.text.toString(), url.text.toString())) {
                is CustomSearchEngines.Result.Invalid -> {
                    error.text = result.message
                    error.visibility = View.VISIBLE
                }
                is CustomSearchEngines.Result.Valid -> {
                    browserPreferences.saveCustomSearchEngine(result.engine)
                    onPreferencesChanged()
                    toast("${result.engine.displayName} is now your search engine")
                    pop()
                }
            }
        }
        url.imeOptions = EditorInfo.IME_ACTION_DONE
        url.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { save(); true } else false
        }
        addView(KernelUi.sectionHeader(context, "Search engine"))
        addView(name)
        addView(url)
        addView(error)
        addView(View(context), LinearLayout.LayoutParams(1, KernelUi.dp(context, 8)))
        addView(ChromeSheet.actionButton(context, "Save", primary = true) { save() })
        addView(KernelUi.footer(context, "Search for something on the site, copy the results page address, and replace " +
            "your search words with %s. For example: https://en.wikipedia.org/w/index.php?search=%s"))
    }

    private fun websiteThemePage() = Page("Website theme") {
        addView(KernelUi.sectionHeader(context, "Appearance of websites"))
        addView(KernelUi.group(context, WebsiteTheme.entries.map { theme ->
            KernelUi.choice(context, title = theme.displayName, subtitle = theme.summary, selected = browserPreferences.websiteTheme == theme) {
                browserPreferences.websiteTheme = theme
                onPreferencesChanged()
                refresh()
            }
        }))
        addView(KernelUi.footer(context, "Only affects sites that offer both light and dark designs."))
    }

    private fun defaultBrowserRow(): View {
        val isDefault = DefaultBrowser.isDefault(context)
        return KernelUi.item(
            context,
            if (isDefault) "Kernel is your default browser" else "Set as default browser",
            subtitle = if (isDefault) "Links from other apps open here." else "Open links from other apps in Kernel.",
            icon = R.drawable.ic_globe,
            tile = R.color.tile_blue,
            accent = !isDefault,
            chevron = true
        ) {
            val activity = context as? Activity ?: return@item
            awaitingDefaultBrowser = true
            val opened = if (isDefault) DefaultBrowser.openDefaultAppsSettings(activity) else DefaultBrowser.request(activity)
            if (!opened) {
                awaitingDefaultBrowser = false
                toast("Open your device's Default apps settings to choose a browser")
            }
        }
    }

    private fun homepagePage() = Page("Homepage") {
        val input = EditText(context).apply {
            setText(browserPreferences.homepageUrl)
            setSingleLine(true)
            textSize = 16f
            hint = "https://example.com"
            imeOptions = EditorInfo.IME_ACTION_DONE
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI or android.text.InputType.TYPE_CLASS_TEXT
            setTextColor(context.getColor(R.color.kernel_text))
            setHintTextColor(context.getColor(R.color.kernel_muted))
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.kernel_group))
                cornerRadius = KernelUi.dp(context, 14).toFloat()
            }
            setPadding(KernelUi.dp(context, 16), 0, KernelUi.dp(context, 16), 0)
        }
        fun save() {
            browserPreferences.homepageUrl = input.text.toString()
            onPreferencesChanged()
            Toast.makeText(context, "Homepage saved", Toast.LENGTH_SHORT).show()
            pop()
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { save(); true } else false
        }
        addView(KernelUi.sectionHeader(context, "Web address"))
        addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, KernelUi.dp(context, 54)))
        addView(View(context), LinearLayout.LayoutParams(1, KernelUi.dp(context, 14)))
        addView(ChromeSheet.actionButton(context, "Save", primary = true) { save() })
        addView(KernelUi.sectionHeader(context, "Quick picks"))
        addView(KernelUi.group(context, listOf(
            "Google" to BrowserPreferences.DEFAULT_HOMEPAGE,
            "DuckDuckGo" to "https://duckduckgo.com",
            "YouTube" to "https://m.youtube.com"
        ).map { (name, url) ->
            KernelUi.choice(context, title = name, subtitle = url, selected = browserPreferences.homepageUrl.trimEnd('/') == url) {
                browserPreferences.homepageUrl = url
                onPreferencesChanged()
                refresh()
            }
        }))
    }

    private fun textSizePage() = Page("Text size") {
        addView(KernelUi.sectionHeader(context, "Page text"))
        addView(KernelUi.group(context, listOf(80, 90, 100, 110, 125, 150).map { percent ->
            KernelUi.choice(
                context,
                title = if (percent == 100) "100% (default)" else "$percent%",
                selected = browserPreferences.pageZoomPercent == percent
            ) {
                browserPreferences.pageZoomPercent = percent
                onPreferencesChanged()
                refresh()
            }
        }))
        addView(TextView(context).apply {
            text = "The quick brown fox jumps over the lazy dog."
            textSize = 16f * browserPreferences.pageZoomPercent / 100f
            setTextColor(context.getColor(R.color.kernel_text))
            setPadding(KernelUi.dp(context, 18), KernelUi.dp(context, 18), KernelUi.dp(context, 18), 0)
        })
    }

    private fun autoplayPage() = Page("Autoplay") {
        addView(KernelUi.sectionHeader(context, "When a site tries to play media"))
        addView(KernelUi.group(context, AutoplayPolicy.entries.map { policy ->
            KernelUi.choice(context, title = policy.displayName, subtitle = policy.summary, selected = browserPreferences.autoplay == policy) {
                browserPreferences.autoplay = policy
                onPreferencesChanged()
                refresh()
            }
        }))
        addView(KernelUi.footer(context, "Tapping play always works. This only affects media that starts on its own."))
    }

    // endregion

    // region privacy pages

    private fun trackingLevelLabel(): String = when {
        !browserPreferences.trackingProtection -> "Off"
        browserPreferences.strictTrackingProtection -> "Strict"
        else -> "Standard"
    }

    private fun trackingProtectionPage() = Page("Tracking protection") {
        fun select(enabled: Boolean, strict: Boolean) {
            browserPreferences.trackingProtection = enabled
            browserPreferences.strictTrackingProtection = strict
            onPreferencesChanged()
            refresh()
        }
        val level = trackingLevelLabel()
        addView(KernelUi.sectionHeader(context, "Enhanced Tracking Protection"))
        addView(KernelUi.group(
            context,
            KernelUi.choice(context, "Standard", subtitle = "Blocks social trackers, cross-site cookies, cryptominers, " +
                "and fingerprinters. Pages load normally.", selected = level == "Standard") { select(enabled = true, strict = false) },
            KernelUi.choice(context, "Strict", subtitle = "Also blocks tracking content such as embedded ads and videos. " +
                "Stronger protection, but some sites may break.", selected = level == "Strict") { select(enabled = true, strict = true) },
            KernelUi.choice(context, "Off", subtitle = "Trackers are not blocked.", selected = level == "Off") {
                select(enabled = false, strict = false)
            }
        ))
        addView(KernelUi.footer(context, "If a site doesn't work right, try Standard. Private tabs always isolate cross-site cookies."))
    }

    private fun httpsOnlyPage() = Page("HTTPS-Only Mode") {
        addView(KernelUi.sectionHeader(context, "Always use secure connections"))
        addView(KernelUi.group(context, HttpsOnlyMode.entries.map { mode ->
            KernelUi.choice(context, title = mode.displayName, subtitle = mode.summary, selected = browserPreferences.httpsOnly == mode) {
                browserPreferences.httpsOnly = mode
                onPreferencesChanged()
                refresh()
            }
        }))
        addView(KernelUi.footer(context, "HTTPS keeps what you send and see private from others on the network. " +
            "Most sites support it; a few older ones only work over HTTP."))
    }

    private fun passwordsPage() = Page("Passwords") {
        addView(KernelUi.group(context, KernelUi.toggle(
            context,
            "Offer to save passwords",
            subtitle = "Fill saved sign-ins automatically.",
            icon = R.drawable.ic_key,
            tile = R.color.tile_teal,
            checked = browserPreferences.loginAutofill
        ) {
            browserPreferences.loginAutofill = it
            onPreferencesChanged()
        }))
        val logins = loginStore.summaries()
        addView(KernelUi.sectionHeader(context, "Saved sign-ins"))
        if (logins.isEmpty()) {
            addView(KernelUi.group(context, KernelUi.item(context, "No saved passwords", subtitle = "Accept a save prompt after signing in to keep it here.")))
        } else {
            addView(KernelUi.group(context, logins.map { login ->
                KernelUi.item(context, login.username.ifBlank { "(no username)" }, subtitle = login.origin, icon = R.drawable.ic_globe, tile = R.color.tile_gray)
            }))
            addView(KernelUi.sectionHeader(context, ""))
            addView(KernelUi.group(context, KernelUi.item(context, "Delete all saved passwords", danger = true) {
                confirm("Delete all passwords?", "This can't be undone.", "Delete") {
                    loginStore.clear()
                    refresh()
                }
            }))
        }
        addView(KernelUi.footer(context, "Passwords stay on this device and are never shown here."))
    }

    private fun sitePermissionsPage() = Page("Site permissions") {
        val container = this
        addView(KernelUi.footer(context, "Loading..."))
        runtime.storageController.allPermissions.accept({ permissions ->
            container.removeAllViews()
            val saved = permissions.orEmpty().filter { it.value != GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT }
            if (saved.isEmpty()) {
                container.addView(KernelUi.sectionHeader(context, "Saved decisions"))
                container.addView(KernelUi.group(context, KernelUi.item(context, "Nothing saved yet",
                    subtitle = "When you allow or block something for a site, it shows up here.")))
                return@accept
            }
            saved.groupBy { displayHost(it.uri) }.toSortedMap().forEach { (host, entries) ->
                container.addView(KernelUi.sectionHeader(context, host))
                container.addView(KernelUi.group(context, entries.map { permission ->
                    val label = SitePermissions.label(permission.permission)
                    KernelUi.item(
                        context,
                        title = label,
                        subtitle = if (permission.privateMode) "Private tabs" else null,
                        value = SitePermissions.valueLabel(permission.value),
                        trailing = KernelUi.pill(context, "Reset", R.color.kernel_danger).apply {
                            contentDescription = "Reset $label for $host"
                            setOnClickListener {
                                runtime.storageController.setPermission(permission, GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT)
                                container.postDelayed({ refresh() }, 200)
                            }
                        }
                    ) {
                        choosePermissionValue(host, label, permission.value) { value ->
                            runtime.storageController.setPermission(permission, value)
                            container.postDelayed({ refresh() }, 200)
                        }
                    }
                }))
            }
            container.addView(KernelUi.footer(context, "Tap a permission to allow or block it. Reset asks again next time."))
        }, {
            container.removeAllViews()
            container.addView(KernelUi.footer(context, "Could not load site permissions."))
        })
    }

    private fun choosePermissionValue(host: String, label: String, current: Int, onChoose: (Int) -> Unit) {
        val values = listOf(
            GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW,
            GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY,
            GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT
        )
        ChromeSheet.show(context, title = label, subtitle = host, compactTitle = true) { sheet ->
            addView(KernelUi.group(context, values.map { value ->
                KernelUi.choice(context, title = SitePermissions.valueLabel(value), selected = value == current) {
                    sheet.dismiss()
                    if (value != current) onChoose(value)
                }
            }))
        }
    }

    private fun clearDataPage() = Page("Clear browsing data") {
        addView(KernelUi.sectionHeader(context, "Choose what to clear"))
        addView(KernelUi.group(
            context,
            KernelUi.item(context, "Browsing history", subtitle = "${historyProvider().size} pages", icon = R.drawable.ic_history, tile = R.color.tile_blue) {
                confirm("Clear history?", "Removes the pages listed in History.", "Clear") {
                    clearHistory()
                    toast("History cleared")
                    refresh()
                }
            },
            KernelUi.item(context, "Cookies and site data", subtitle = "Signs you out of most sites.", icon = R.drawable.ic_cookie, tile = R.color.tile_orange) {
                confirm("Clear cookies and site data?", "You'll be signed out of most sites.", "Clear") {
                    clearStorage(StorageController.ClearFlags.SITE_DATA, "Cookies and site data cleared")
                }
            },
            KernelUi.item(context, "Cached images and files", subtitle = "Frees space. Pages may load slower once.", icon = R.drawable.ic_download, tile = R.color.tile_gray) {
                clearStorage(StorageController.ClearFlags.ALL_CACHES, "Cache cleared")
            },
            KernelUi.item(context, "Site permissions", subtitle = "Location, notifications, autoplay, and more.", icon = R.drawable.ic_lock, tile = R.color.tile_teal) {
                clearStorage(StorageController.ClearFlags.PERMISSIONS or StorageController.ClearFlags.SITE_SETTINGS, "Site permissions reset")
            }
        ))
        addView(KernelUi.sectionHeader(context, ""))
        addView(KernelUi.group(context, KernelUi.item(context, "Clear everything", subtitle = "History, site data, cache, permissions, and open tabs.",
            icon = R.drawable.ic_delete, tile = R.color.tile_red, danger = true) {
            confirm("Clear everything?", "Closes all tabs and removes history, cookies, cache, and permissions.", "Clear all") {
                clearHistory()
                resetBrowserState()
                clearStorage(StorageController.ClearFlags.ALL, "All browsing data cleared")
            }
        }))

        addView(KernelUi.sectionHeader(context, "When you quit"))
        val quitRows = mutableListOf<View>(
            KernelUi.toggle(context, "Delete browsing data on quit",
                subtitle = "Clears history, open tabs, cookies, and cache whenever Kernel quits.",
                icon = R.drawable.ic_delete, tile = R.color.tile_gray, checked = browserPreferences.clearDataOnQuit) {
                browserPreferences.clearDataOnQuit = it
                host.post { refresh() }
            }
        )
        if (browserPreferences.clearDataOnQuit) {
            quitRows += KernelUi.item(context, "Quit Kernel", subtitle = "Close all tabs and delete browsing data now.",
                icon = R.drawable.ic_close, tile = R.color.tile_red, danger = true) {
                val activity = context as? Activity ?: return@item
                dialog.dismiss()
                activity.finishAndRemoveTask()
            }
        }
        addView(KernelUi.group(context, quitRows))
        addView(KernelUi.footer(context, "Bookmarks, site permissions, and saved passwords are kept."))
    }

    private fun clearStorage(flags: Long, done: String) {
        runtime.storageController.clearData(flags).accept(
            { toast(done); refresh() },
            {
                SafeLog.warning("Clearing browsing data failed", it)
                toast("Could not clear data")
            }
        )
    }

    // endregion

    // region extension and library pages

    private fun extensionsPage(): Page {
        val key = Any()
        return Page("Extensions", onLeave = { extensionsUi.unbind(key) }) {
            val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(list)
            extensionsUi.bind(key, list) { dialog.dismiss() }
        }
    }

    private fun historyPage(): Page {
        // Survives re-renders (for example after Clear history) so the search box keeps its text.
        var query = ""
        return Page("History") {
            if (historyStore.timedEntries().isEmpty()) {
                addView(KernelUi.sectionHeader(context, "Recent"))
                addView(KernelUi.group(context, KernelUi.item(context, "No history yet",
                    subtitle = "Pages from normal tabs appear here. Private tabs are never saved.")))
                return@Page
            }
            val results = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            fun renderResults() {
                results.removeAllViews()
                val matches = HistoryGrouping.filterEntries(historyStore.timedEntries(), query)
                if (matches.isEmpty()) {
                    results.addView(KernelUi.footer(context,
                        if (query.isBlank()) "No history yet." else "No pages match “${query.trim()}”."))
                    return
                }
                HistoryGrouping.group(matches, System.currentTimeMillis()).forEach { section ->
                    results.addView(KernelUi.sectionHeader(context, section.label))
                    results.addView(KernelUi.group(context, section.entries.map { entry ->
                        KernelUi.item(
                            context,
                            entry.title,
                            subtitle = listOfNotNull(timeLabel(entry.visitedAt), displayHost(entry.url)).joinToString(" · "),
                            leading = KernelUi.letterTile(context, displayHost(entry.url), entry.url),
                            trailing = removeButton("Remove ${entry.title} from history") {
                                historyStore.remove(entry.url)
                                renderResults()
                            }
                        ) {
                            dialog.dismiss()
                            openUrl(entry.url)
                        }
                    }))
                }
            }
            addView(searchField("Search history", query) {
                query = it
                renderResults()
            })
            addView(results)
            renderResults()
            addView(KernelUi.sectionHeader(context, ""))
            addView(KernelUi.group(context, KernelUi.item(context, "Clear history", danger = true) {
                confirm("Clear history?", "Removes all pages listed here.", "Clear") {
                    clearHistory()
                    query = ""
                    refresh()
                }
            }))
        }
    }

    private fun bookmarksPage(): Page {
        var query = ""
        return Page("Bookmarks") {
            if (bookmarksStore.entries().isEmpty()) {
                addView(KernelUi.sectionHeader(context, "Saved pages"))
                addView(KernelUi.group(context, KernelUi.item(context, "No bookmarks yet", subtitle = "Use Add bookmark in the browser menu.")))
                return@Page
            }
            val results = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            fun renderResults() {
                results.removeAllViews()
                val matches = HistoryGrouping.filter(bookmarksStore.entries(), query, { it.first }, { it.second })
                results.addView(KernelUi.sectionHeader(context, "Saved pages"))
                if (matches.isEmpty()) {
                    results.addView(KernelUi.footer(context,
                        if (query.isBlank()) "No bookmarks yet." else "No bookmarks match “${query.trim()}”."))
                    return
                }
                results.addView(KernelUi.group(context, matches.map { (title, url) ->
                    KernelUi.item(
                        context,
                        title,
                        subtitle = displayHost(url),
                        icon = R.drawable.ic_bookmark,
                        tile = R.color.tile_orange,
                        trailing = KernelUi.moreButton(context, "More options for $title") {
                            showBookmarkActions(title, url) { renderResults() }
                        }
                    ) {
                        dialog.dismiss()
                        openUrl(url)
                    }
                }))
            }
            addView(searchField("Search bookmarks", query) {
                query = it
                renderResults()
            })
            addView(results)
            renderResults()
        }
    }

    private fun showBookmarkActions(title: String, url: String, onChanged: () -> Unit) {
        ChromeSheet.show(context, title = title, subtitle = url, compactTitle = true) { sheet ->
            addView(KernelUi.group(
                context,
                KernelUi.item(context, "Open", icon = R.drawable.ic_globe, tile = R.color.tile_blue) {
                    sheet.dismiss()
                    dialog.dismiss()
                    openUrl(url)
                },
                KernelUi.item(context, "Edit name", icon = R.drawable.ic_bookmark, tile = R.color.tile_orange) {
                    sheet.dismiss()
                    editBookmarkTitle(title, url, onChanged)
                },
                KernelUi.item(context, "Remove", icon = R.drawable.ic_delete, tile = R.color.tile_red, danger = true) {
                    sheet.dismiss()
                    bookmarksStore.remove(url)
                    toast("Bookmark removed")
                    onChanged()
                }
            ))
        }
    }

    private fun editBookmarkTitle(title: String, url: String, onChanged: () -> Unit) {
        ChromeSheet.show(context, title = "Edit bookmark", subtitle = url, scrollable = false, showDone = false, compactTitle = true) { sheet ->
            val input = ChromeSheet.textField(context, "Name", title)
            fun save() {
                sheet.dismiss()
                bookmarksStore.rename(url, input.text.toString())
                onChanged()
            }
            input.imeOptions = EditorInfo.IME_ACTION_DONE
            input.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) { save(); true } else false
            }
            addView(input)
            addView(ChromeSheet.buttonRow(
                context,
                ChromeSheet.actionButton(context, "Cancel") { sheet.dismiss() },
                ChromeSheet.actionButton(context, "Save", primary = true) { save() }
            ))
            input.requestFocus()
            input.setSelection(input.text.length)
            sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
    }

    /** A rounded search box; [onChange] runs on every edit without rebuilding the page. */
    private fun searchField(hint: String, initial: String, onChange: (String) -> Unit): EditText {
        return ChromeSheet.textField(context, hint, initial).apply {
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            contentDescription = hint
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = KernelUi.dp(context, 8)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    onChange(s?.toString().orEmpty())
                }
            })
            setOnEditorActionListener { view, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(view.windowToken, 0)
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun timeLabel(visitedAt: Long): String? {
        if (visitedAt <= 0L) return null
        return android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(visitedAt))
    }

    private fun downloadsPage() = Page("Downloads") {
        val downloads = downloadStore.entries()
        addView(KernelUi.sectionHeader(context, "Recent"))
        if (downloads.isEmpty()) {
            addView(KernelUi.group(context, KernelUi.item(context, "No downloads yet", subtitle = "Files you download appear here.")))
            return@Page
        }
        addView(KernelUi.group(context, downloads.take(40).map { entry ->
            KernelUi.item(
                context,
                entry.title,
                subtitle = "${downloadStatus(entry)} · ${displayHost(entry.url)}",
                icon = R.drawable.ic_download,
                tile = R.color.tile_green,
                trailing = removeButton("Remove ${entry.title}") {
                    removeDownload(entry)
                    refresh()
                }
            ) { openDownload(entry) }
        }))
        addView(KernelUi.sectionHeader(context, ""))
        addView(KernelUi.group(context, KernelUi.item(context, "Clear download list", subtitle = "Files stay in your Downloads folder.", danger = true) {
            downloadStore.clear()
            refresh()
        }))
    }

    // endregion

    private fun removeButton(label: String, onClick: () -> Unit): ImageButton {
        return ImageButton(context).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(context.getColor(R.color.kernel_muted))
            background = KernelUi.selectableItemBackground(context)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = label
            setPadding(KernelUi.dp(context, 8), KernelUi.dp(context, 8), KernelUi.dp(context, 8), KernelUi.dp(context, 8))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(KernelUi.dp(context, 48), KernelUi.dp(context, 48))
        }
    }

    private fun confirm(title: String, message: String, action: String, onConfirm: () -> Unit) {
        ChromeSheet.show(context, title = title, subtitle = message, scrollable = false) { sheet ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(ChromeSheet.actionButton(context, "Cancel") { sheet.dismiss() },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = KernelUi.dp(context, 6) })
                addView(ChromeSheet.actionButton(context, action, danger = true) {
                    sheet.dismiss()
                    onConfirm()
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = KernelUi.dp(context, 6) })
            })
        }
    }

    private fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    private fun displayHost(url: String): String {
        return runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: url
    }
}
