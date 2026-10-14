package com.kernel.browser

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import com.kernel.browser.extensions.AllowedExtension
import com.kernel.browser.extensions.ExtensionActionRegistry
import com.kernel.browser.extensions.ExtensionManager
import com.kernel.browser.extensions.ExtensionPermissions
import org.mozilla.geckoview.WebExtension

/**
 * Extension UI: the manager list (toolbar sheet and Settings page share it), the
 * per-extension details sheet, and the install permission prompt.
 */
class ExtensionsDialogController(
    private val context: Context,
    private val manager: ExtensionManager,
    private val actionRegistry: ExtensionActionRegistry,
    private val openUrl: (String) -> Unit,
    private val pickExtensionFile: () -> Unit
) {
    private val refreshers = linkedMapOf<Any, () -> Unit>()
    private val pendingPrompts = mutableListOf<() -> Unit>()

    /** Answers "no" to any install prompt still open, e.g. when the activity is destroyed. */
    fun cancelPendingPrompts() {
        pendingPrompts.toList().forEach { it() }
        pendingPrompts.clear()
    }

    /** Called by the activity whenever installs, toggles, or browser actions change. */
    fun onExtensionsChanged() {
        refreshers.values.toList().forEach { it() }
    }

    fun show() {
        ChromeSheet.show(
            context = context,
            title = "Extensions",
            subtitle = "Tap an extension to open its controls for this page."
        ) { dialog ->
            val container = this
            val render = {
                container.removeAllViews()
                populate(container, openOnTap = true, dismiss = { dialog.dismiss() })
            }
            render()
            refreshers[dialog] = render
            dialog.setOnDismissListener { refreshers.remove(dialog) }
        }
    }

    /** Registers a live list (for example the Settings page) that re-renders on change. */
    fun bind(key: Any, container: LinearLayout, dismiss: () -> Unit) {
        val render = {
            container.removeAllViews()
            populate(container, openOnTap = false, dismiss = dismiss)
        }
        render()
        refreshers[key] = render
    }

    fun unbind(key: Any) {
        refreshers.remove(key)
    }

    fun populate(container: LinearLayout, openOnTap: Boolean, dismiss: () -> Unit) {
        val installed = manager.installed()

        if (manager.isInstalling()) {
            container.addView(ChromeSheet.note(context, "Installing extension..."))
        }

        container.addView(KernelUi.sectionHeader(context, "Installed"))
        if (installed.isEmpty()) {
            container.addView(KernelUi.group(context, KernelUi.item(
                context,
                title = "No extensions yet",
                subtitle = "Add one below or browse the catalog.",
                icon = R.drawable.ic_extension,
                tile = R.color.tile_gray
            )))
        } else {
            container.addView(KernelUi.group(context, installed.map { extension ->
                installedRow(extension, openOnTap, dismiss)
            }))
        }

        val suggestions = manager.suggestions().filter { allowed -> installed.none { it.id == allowed.id } }
        if (suggestions.isNotEmpty()) {
            container.addView(KernelUi.sectionHeader(context, "Recommended"))
            container.addView(KernelUi.group(context, suggestions.map { suggestionRow(it) }))
        }

        container.addView(KernelUi.sectionHeader(context, "Get more"))
        container.addView(KernelUi.group(
            context,
            KernelUi.item(
                context,
                title = "Browse extensions",
                subtitle = "Find add-ons on addons.mozilla.org and tap Add to Firefox.",
                icon = R.drawable.ic_store,
                tile = R.color.tile_orange,
                chevron = true,
                onClick = {
                    dismiss()
                    openUrl(AMO_ANDROID_URL)
                }
            ),
            KernelUi.item(
                context,
                title = "Install from file",
                subtitle = "Pick a signed .xpi you downloaded.",
                icon = R.drawable.ic_folder,
                tile = R.color.tile_gray,
                chevron = true,
                onClick = { pickExtensionFile() }
            )
        ))
        container.addView(KernelUi.footer(
            context,
            "Extensions must be signed by Mozilla. Ones marked for Firefox for Android work best."
        ))
    }

    private fun installedRow(extension: WebExtension, openOnTap: Boolean, dismiss: () -> Unit): View {
        val enabled = manager.isEnabled(extension)
        val action = actionRegistry.entry(extension.id)?.takeIf { enabled && it.enabled }
        val subtitle = when {
            !enabled -> "Off"
            action != null && openOnTap -> "Tap to open"
            else -> listOfNotNull(
                extension.metaData.version?.let { "Version $it" },
                if (extension.metaData.allowedInPrivateBrowsing) "Runs in private tabs" else null
            ).joinToString(" · ")
        }
        val toggle = Switch(context).apply {
            isChecked = enabled
            contentDescription = "Enable ${manager.displayName(extension)}"
            setOnCheckedChangeListener { _, checked -> manager.setEnabled(extension, checked) }
        }
        val details = ImageButton(context).apply {
            setImageResource(R.drawable.ic_more)
            imageTintList = ColorStateList.valueOf(context.getColor(R.color.kernel_muted))
            background = KernelUi.selectableItemBackground(context)
            contentDescription = "${manager.displayName(extension)} details"
            setOnClickListener { showDetails(extension, dismiss) }
            layoutParams = LinearLayout.LayoutParams(KernelUi.dp(context, 48), KernelUi.dp(context, 48))
        }
        val trailing = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(toggle)
            if (openOnTap) addView(details)
        }
        return KernelUi.item(
            context,
            title = manager.displayName(extension),
            subtitle = subtitle,
            leading = extensionIcon(extension),
            trailing = trailing,
            onClick = {
                if (openOnTap && action != null) {
                    dismiss()
                    actionRegistry.open(action)
                } else {
                    showDetails(extension, dismiss)
                }
            }
        )
    }

    private fun suggestionRow(allowed: AllowedExtension): View {
        return KernelUi.item(
            context,
            title = allowed.displayName,
            subtitle = allowed.permissionSummary,
            icon = R.drawable.ic_extension,
            tile = R.color.tile_purple,
            trailing = KernelUi.pill(context, "Add").apply { contentDescription = "Add ${allowed.displayName}" },
            onClick = { manager.addBundled(allowed) }
        )
    }

    private fun extensionIcon(extension: WebExtension): View {
        val bitmap = manager.icon(extension.id)
        val enabled = manager.isEnabled(extension)
        val tile = if (enabled) R.color.tile_purple else R.color.tile_gray
        return KernelUi.bitmapTile(context, bitmap, R.drawable.ic_extension, tile).apply {
            alpha = if (enabled) 1f else 0.5f
        }
    }

    fun showDetails(extension: WebExtension, dismissParent: () -> Unit = {}) {
        val meta = extension.metaData
        val byline = listOfNotNull(
            meta.version?.let { "Version $it" },
            meta.creatorName?.takeIf { it.isNotBlank() }?.let { "by $it" }
        ).joinToString(" · ")
        ChromeSheet.show(context, title = manager.displayName(extension), subtitle = byline) { dialog ->
            val container = this
            val render = {
                container.removeAllViews()
                val current = manager.find(extension.id) ?: extension
                renderDetails(container, current, dialog, dismissParent)
            }
            render()
            refreshers[dialog] = render
            dialog.setOnDismissListener { refreshers.remove(dialog) }
        }
    }

    private fun renderDetails(container: LinearLayout, extension: WebExtension, dialog: Dialog, dismissParent: () -> Unit) {
        val meta = extension.metaData
        meta.description?.takeIf { it.isNotBlank() }?.let {
            container.addView(ChromeSheet.note(context, it))
        }

        container.addView(KernelUi.group(
            context,
            KernelUi.toggle(
                context,
                title = "Enabled",
                icon = R.drawable.ic_extension,
                tile = R.color.tile_purple,
                checked = manager.isEnabled(extension)
            ) { checked -> manager.setEnabled(extension, checked) },
            KernelUi.toggle(
                context,
                title = "Run in private tabs",
                subtitle = "Lets it see pages you open privately.",
                icon = R.drawable.ic_private,
                tile = R.color.tile_indigo,
                checked = meta.allowedInPrivateBrowsing
            ) { checked -> manager.setAllowedInPrivate(extension, checked) }
        ))

        val actions = mutableListOf<View>()
        actionRegistry.entry(extension.id)?.takeIf { manager.isEnabled(extension) && it.enabled }?.let { action ->
            actions += KernelUi.item(context, title = "Open controls", icon = R.drawable.ic_play, tile = R.color.tile_blue, chevron = true) {
                dialog.dismiss()
                dismissParent()
                actionRegistry.open(action)
            }
        }
        if (!meta.optionsPageUrl.isNullOrBlank()) {
            actions += KernelUi.item(context, title = "Extension settings", icon = R.drawable.ic_settings, tile = R.color.tile_gray, chevron = true) {
                dialog.dismiss()
                dismissParent()
                manager.openOptions(extension)
            }
        }
        val listing = meta.amoListingUrl ?: meta.homepageUrl
        if (!listing.isNullOrBlank()) {
            actions += KernelUi.item(context, title = "View listing", icon = R.drawable.ic_globe, tile = R.color.tile_teal, chevron = true) {
                dialog.dismiss()
                dismissParent()
                openUrl(listing)
            }
        }
        if (actions.isNotEmpty()) {
            container.addView(KernelUi.sectionHeader(context, "Actions"))
            container.addView(KernelUi.group(context, actions))
        }

        val permissions = ExtensionPermissions.describe(
            meta.requiredPermissions.orEmpty().toList() + meta.grantedOptionalPermissions.orEmpty(),
            meta.requiredOrigins.orEmpty().toList() + meta.grantedOptionalOrigins.orEmpty()
        )
        container.addView(KernelUi.sectionHeader(context, "Permissions"))
        container.addView(KernelUi.group(context, if (permissions.isEmpty()) {
            listOf(KernelUi.item(context, title = "No special permissions", icon = R.drawable.ic_check, tile = R.color.tile_green))
        } else {
            permissions.map { KernelUi.item(context, title = it, icon = R.drawable.ic_lock, tile = R.color.tile_gray) }
        }))

        container.addView(KernelUi.sectionHeader(context, ""))
        if (manager.isRemovable(extension)) {
            container.addView(KernelUi.group(context, KernelUi.item(
                context,
                title = "Remove extension",
                icon = R.drawable.ic_delete,
                tile = R.color.tile_red,
                danger = true
            ) {
                confirm("Remove ${manager.displayName(extension)}?", "Its settings and data will be deleted.", "Remove") {
                    dialog.dismiss()
                    manager.uninstall(extension)
                }
            }))
        } else {
            container.addView(KernelUi.footer(context, "Bundled with Kernel Browser. Turn it off above if you don't need it."))
        }
    }

    fun confirmInstall(
        extension: WebExtension,
        permissions: List<String>,
        origins: List<String>,
        onResult: (granted: Boolean, allowInPrivate: Boolean) -> Unit
    ) {
        var answered = false
        var allowPrivate = false
        lateinit var cancel: () -> Unit
        fun answer(granted: Boolean) {
            if (answered) return
            answered = true
            pendingPrompts.remove(cancel)
            onResult(granted, granted && allowPrivate)
        }
        cancel = { answer(false) }
        pendingPrompts += cancel
        val name = manager.displayName(extension)
        val lines = ExtensionPermissions.describe(permissions, origins)
        val dialog = ChromeSheet.show(
            context = context,
            title = "Add $name?",
            subtitle = extension.metaData.creatorName?.takeIf { it.isNotBlank() }?.let { "by $it" }
        ) { dialog ->
            addView(KernelUi.sectionHeader(context, if (lines.isEmpty()) "Permissions" else "It will be able to"))
            addView(KernelUi.group(context, if (lines.isEmpty()) {
                listOf(KernelUi.item(context, title = "No special permissions", icon = R.drawable.ic_check, tile = R.color.tile_green))
            } else {
                lines.map { KernelUi.item(context, title = it, icon = R.drawable.ic_lock, tile = R.color.tile_gray) }
            }))
            addView(KernelUi.sectionHeader(context, "Options"))
            addView(KernelUi.group(context, KernelUi.toggle(
                context,
                title = "Run in private tabs",
                icon = R.drawable.ic_private,
                tile = R.color.tile_indigo,
                checked = false
            ) { allowPrivate = it }))
            addView(spacer())
            addView(buttonRow(
                ChromeSheet.actionButton(context, "Cancel") {
                    answer(false)
                    dialog.dismiss()
                },
                ChromeSheet.actionButton(context, "Add", primary = true) {
                    answer(true)
                    dialog.dismiss()
                }
            ))
        }
        dialog.setOnDismissListener { answer(false) }
    }

    fun confirmOptionalPermissions(extension: WebExtension, lines: List<String>, onResult: (Boolean) -> Unit) {
        var answered = false
        lateinit var cancel: () -> Unit
        fun answer(allowed: Boolean) {
            if (!answered) {
                answered = true
                pendingPrompts.remove(cancel)
                onResult(allowed)
            }
        }
        cancel = { answer(false) }
        pendingPrompts += cancel
        val dialog = ChromeSheet.show(
            context = context,
            title = "${manager.displayName(extension)} wants more access",
            scrollable = true
        ) { dialog ->
            addView(KernelUi.group(context, lines.ifEmpty { listOf("Additional permissions") }.map {
                KernelUi.item(context, title = it, icon = R.drawable.ic_lock, tile = R.color.tile_gray)
            }))
            addView(spacer())
            addView(buttonRow(
                ChromeSheet.actionButton(context, "Deny") { answer(false); dialog.dismiss() },
                ChromeSheet.actionButton(context, "Allow", primary = true) { answer(true); dialog.dismiss() }
            ))
        }
        dialog.setOnDismissListener { answer(false) }
    }

    private fun confirm(title: String, message: String, action: String, onConfirm: () -> Unit) {
        ChromeSheet.show(context, title = title, subtitle = message, scrollable = false) { dialog ->
            addView(buttonRow(
                ChromeSheet.actionButton(context, "Cancel") { dialog.dismiss() },
                ChromeSheet.actionButton(context, action, danger = true) {
                    dialog.dismiss()
                    onConfirm()
                }
            ))
        }
    }

    private fun spacer(): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, KernelUi.dp(context, 18))
    }

    private fun buttonRow(left: View, right: View): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = KernelUi.dp(context, 6)
            })
            addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = KernelUi.dp(context, 6)
            })
        }
    }

    companion object {
        const val AMO_ANDROID_URL = "https://addons.mozilla.org/android/"
    }
}
