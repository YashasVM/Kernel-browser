package com.kernel.browser

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
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
    private val historyProvider: () -> List<Pair<String, String>>,
    private val clearHistory: () -> Unit,
    private val openUrl: (String) -> Unit,
    private val onPreferencesChanged: () -> Unit
) {
    private val motion = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val stack = mutableListOf<PageEntry>()
    private lateinit var dialog: Dialog
    private lateinit var host: FrameLayout

    private class Page(val title: String, val onLeave: () -> Unit = {}, val build: LinearLayout.() -> Unit)

    private class PageEntry(val page: Page, val view: View, val scroll: ScrollView, val content: LinearLayout)

    fun show() {
        dialog = Dialog(context, android.R.style.Theme_Material_NoActionBar)
        host = FrameLayout(context).apply {
            setBackgroundColor(context.getColor(R.color.kernel_background))
            fitsSystemWindows = true
        }
        dialog.setContentView(host)
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
                value = browserPreferences.autoplay.displayName, chevron = true) { push(autoplayPage()) }
        ))

        addView(KernelUi.sectionHeader(context, "Privacy & security"))
        addView(KernelUi.group(
            context,
            KernelUi.toggle(context, "Tracking protection", subtitle = "Block trackers, cryptominers, and fingerprinting.",
                icon = R.drawable.ic_shield, tile = R.color.tile_green, checked = browserPreferences.trackingProtection) {
                browserPreferences.trackingProtection = it
                onPreferencesChanged()
            },
            KernelUi.toggle(context, "Isolate cross-site cookies", subtitle = "Stops sites following you around. Sign-ins keep working.",
                icon = R.drawable.ic_cookie, tile = R.color.tile_orange, checked = browserPreferences.blockThirdPartyCookies) {
                browserPreferences.blockThirdPartyCookies = it
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
            KernelUi.item(context, "Clear browsing data", icon = R.drawable.ic_delete, tile = R.color.tile_red, chevron = true) {
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
        addView(KernelUi.sectionHeader(context, "Default search"))
        addView(KernelUi.group(context, SearchEngine.all.map { engine ->
            KernelUi.choice(
                context,
                title = engine.displayName,
                subtitle = if (engine.suggestionsUrl != null) "Includes search suggestions" else null,
                selected = browserPreferences.searchEngine == engine
            ) {
                browserPreferences.searchEngineId = engine.id
                onPreferencesChanged()
                refresh()
            }
        }))
        addView(KernelUi.footer(context, "Used for anything you type in the address bar that isn't a web address."))
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
                    KernelUi.item(
                        context,
                        title = SitePermissions.label(permission.permission),
                        value = SitePermissions.valueLabel(permission.value),
                        trailing = KernelUi.pill(context, "Reset", R.color.kernel_danger).apply {
                            contentDescription = "Reset ${SitePermissions.label(permission.permission)} for $host"
                            setOnClickListener {
                                runtime.storageController.setPermission(permission, GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT)
                                container.postDelayed({ refresh() }, 200)
                            }
                        }
                    )
                }))
            }
        }, {
            container.removeAllViews()
            container.addView(KernelUi.footer(context, "Could not load site permissions."))
        })
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

    private fun historyPage() = Page("History") {
        val entries = historyProvider()
        if (entries.isEmpty()) {
            addView(KernelUi.sectionHeader(context, "Recent"))
            addView(KernelUi.group(context, KernelUi.item(context, "No history yet", subtitle = "Pages from normal tabs appear here. Private tabs are never saved.")))
            return@Page
        }
        addView(KernelUi.sectionHeader(context, "Recent"))
        addView(KernelUi.group(context, entries.take(60).map { (title, url) ->
            KernelUi.item(context, title, subtitle = displayHost(url), icon = R.drawable.ic_globe, tile = R.color.tile_gray) {
                dialog.dismiss()
                openUrl(url)
            }
        }))
        addView(KernelUi.sectionHeader(context, ""))
        addView(KernelUi.group(context, KernelUi.item(context, "Clear history", danger = true) {
            confirm("Clear history?", "Removes all pages listed here.", "Clear") {
                clearHistory()
                refresh()
            }
        }))
    }

    private fun bookmarksPage() = Page("Bookmarks") {
        val bookmarks = bookmarksStore.entries()
        addView(KernelUi.sectionHeader(context, "Saved pages"))
        if (bookmarks.isEmpty()) {
            addView(KernelUi.group(context, KernelUi.item(context, "No bookmarks yet", subtitle = "Use Add bookmark in the browser menu.")))
            return@Page
        }
        addView(KernelUi.group(context, bookmarks.map { (title, url) ->
            KernelUi.item(
                context,
                title,
                subtitle = displayHost(url),
                icon = R.drawable.ic_bookmark,
                tile = R.color.tile_orange,
                trailing = removeButton("Remove $title") {
                    bookmarksStore.remove(url)
                    refresh()
                }
            ) {
                dialog.dismiss()
                openUrl(url)
            }
        }))
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
