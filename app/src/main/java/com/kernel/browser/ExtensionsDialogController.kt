package com.kernel.browser

import android.app.Dialog
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import com.kernel.browser.extensions.AllowedExtensions
import com.kernel.browser.extensions.ExtensionActionEntry
import com.kernel.browser.extensions.ExtensionActionRegistry
import com.kernel.browser.extensions.ExtensionInstaller
import com.kernel.browser.extensions.ExtensionPreferences

class ExtensionsDialogController(
    private val context: Context,
    private val actionRegistry: ExtensionActionRegistry,
    private val extensionPreferences: ExtensionPreferences,
    private val extensionInstaller: ExtensionInstaller
) {
    private var activeDialog: Dialog? = null

    fun show() {
        activeDialog = ChromeSheet.show(
            context = context,
            title = "Extensions",
            subtitle = "Block ads, inspect cookies, and open extension panels."
        ) { dialog ->
            activeDialog = dialog
            val entries = actionRegistry.entries().associateBy { it.extension.id }
            val activeCount = AllowedExtensions.all.count { extensionPreferences.isEnabled(it) }

            addView(ChromeSheet.sectionLabel(context, "How it works"))
            addView(ChromeSheet.note(
                context,
                "Turn an extension on once. When it is ready, use Open here to show its controls for the current page. Private tab access stays in Settings."
            ))

            addView(ChromeSheet.sectionLabel(context, "Available"))
            addView(ChromeSheet.row(
                context = context,
                title = "Extensions status",
                subtitle = "$activeCount of ${AllowedExtensions.all.size} enabled",
                trailing = ChromeSheet.statusPill(context, if (activeCount > 0) "On" else "Off", activeCount > 0)
            ))

            addView(ChromeSheet.sectionLabel(context, "Tools"))
            AllowedExtensions.all.forEach { allowed ->
                addView(extensionRow(entries[allowed.id], allowed.id, allowed.displayName, allowed.permissionSummary))
            }
        }.also { dialog ->
            dialog.setOnDismissListener {
                if (activeDialog === dialog) {
                    activeDialog = null
                }
            }
        }
    }

    private fun extensionRow(
        action: ExtensionActionEntry?,
        extensionId: String,
        fallbackName: String,
        summary: String
    ): View {
        val allowed = AllowedExtensions.byId(extensionId)
        val enabledByPrefs = allowed?.let { extensionPreferences.isEnabled(it) } ?: false
        val ready = action != null && action.enabled && enabledByPrefs
        val title = action?.title ?: fallbackName
        val subtitle = when {
            ready -> "On. Open its controls for the current page."
            enabledByPrefs -> "On. Finishing setup; try Refresh if it does not appear soon."
            else -> "Off. $summary"
        }
        val button = when {
            ready -> ChromeSheet.actionButton(context, "Open", primary = true) {
                activeDialog?.dismiss()
                actionRegistry.open(action!!)
            }
            allowed != null && !enabledByPrefs -> ChromeSheet.actionButton(context, "Enable") {
                extensionPreferences.setEnabled(allowed, true)
                extensionInstaller.ensureInstalled(allowed)
                Toast.makeText(context, "Enabled ${allowed.displayName}", Toast.LENGTH_SHORT).show()
                activeDialog?.dismiss()
            }
            allowed != null -> ChromeSheet.actionButton(context, "Refresh") {
                extensionInstaller.ensureInstalled(allowed)
                Toast.makeText(context, R.string.extension_not_ready, Toast.LENGTH_SHORT).show()
            }
            else -> ChromeSheet.statusPill(context, "Unavailable")
        }

        return ChromeSheet.row(
            context = context,
            title = title,
            subtitle = subtitle,
            trailing = LinearLayout(context).apply {
                addView(button, LinearLayout.LayoutParams(ChromeSheet.dp(context, 96), ChromeSheet.dp(context, 46)))
            }
        )
    }
}
