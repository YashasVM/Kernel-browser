package com.kernel.browser.extensions

import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.kernel.browser.ChromeSheet
import com.kernel.browser.SafeLog
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension

data class ExtensionActionEntry(
    val extension: WebExtension,
    val action: WebExtension.Action,
    val title: String,
    val enabled: Boolean
)

class ExtensionActionRegistry(
    private val context: Context,
    private val runtime: GeckoRuntime,
    private val onChanged: () -> Unit = {}
) : WebExtension.ActionDelegate {
    private val actions = linkedMapOf<String, ExtensionActionEntry>()
    private var popupDialog: Dialog? = null
    private var popupSession: GeckoSession? = null

    fun entries(): List<ExtensionActionEntry> = actions.values.toList()

    fun register(extension: WebExtension) {
        extension.setActionDelegate(this)
    }

    fun open(entry: ExtensionActionEntry) {
        SafeLog.status("Opening extension action: ${entry.extension.id}")
        entry.action.click()

        val fallbackPath = AllowedExtensions.byId(entry.extension.id)?.defaultPopupPath ?: return
        Handler(Looper.getMainLooper()).postDelayed({
            if (popupSession == null) {
                val baseUrl = entry.extension.metaData.baseUrl
                if (!baseUrl.isNullOrBlank()) {
                    SafeLog.status("Opening extension popup fallback: ${entry.extension.id}")
                    openPopup(entry.extension, entry.action, baseUrl + fallbackPath)
                }
            }
        }, 1200)
    }

    override fun onBrowserAction(
        extension: WebExtension,
        session: GeckoSession?,
        action: WebExtension.Action
    ) {
        updateAction(extension, action)
    }

    override fun onPageAction(
        extension: WebExtension,
        session: GeckoSession?,
        action: WebExtension.Action
    ) {
        updateAction(extension, action)
    }

    override fun onTogglePopup(
        extension: WebExtension,
        action: WebExtension.Action
    ): GeckoResult<GeckoSession>? {
        return openPopup(extension, action)
    }

    override fun onOpenPopup(
        extension: WebExtension,
        action: WebExtension.Action
    ): GeckoResult<GeckoSession>? {
        return openPopup(extension, action)
    }

    private fun updateAction(extension: WebExtension, action: WebExtension.Action) {
        val title = action.title
            ?: AllowedExtensions.byId(extension.id)?.displayName
            ?: extension.metaData.name
            ?: extension.id
        val enabled = action.enabled ?: true
        actions[extension.id] = ExtensionActionEntry(extension, action, title, enabled)
        onChanged()
    }

    private fun openPopup(
        extension: WebExtension,
        action: WebExtension.Action
    ): GeckoResult<GeckoSession>? {
        return openPopup(extension, action, null)
    }

    private fun openPopup(
        extension: WebExtension,
        action: WebExtension.Action,
        popupUri: String?
    ): GeckoResult<GeckoSession>? {
        closePopup()

        val session = GeckoSession()
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onCloseRequest(session: GeckoSession) {
                closePopup()
            }
        }
        session.open(runtime)
        popupSession = session
        if (popupUri != null) {
            markAsExtensionPopup(session)
            session.loadUri(popupUri)
        }

        val geckoView = GeckoView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(520)
            )
            setSession(session)
        }

        val frame = FrameLayout(context).apply {
            setPadding(0, 0, 0, 0)
            addView(geckoView)
        }

        popupDialog = ChromeSheet.show(
            context = context,
            title = action.title ?: AllowedExtensions.byId(extension.id)?.displayName ?: "Extension",
            scrollable = false
        ) {
            addView(frame)
            addView(ChromeSheet.actionButton(context, "Close") { closePopup() }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ChromeSheet.dp(context, 46)
                ).apply {
                    topMargin = ChromeSheet.dp(context, 12)
                }
            })
        }.also { dialog ->
                dialog.setOnDismissListener { closePopup() }
            }

        SafeLog.status("Opened extension popup: ${extension.id}")
        return GeckoResult.fromValue(session)
    }

    private fun markAsExtensionPopup(session: GeckoSession) {
        runCatching {
            session.settings.javaClass
                .getDeclaredMethod("setIsExtensionPopup", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(session.settings, true)
        }.onFailure { throwable ->
            SafeLog.warning("Unable to mark extension popup session", throwable)
        }
    }

    private fun closePopup() {
        val dialog = popupDialog
        popupDialog = null
        if (dialog?.isShowing == true) {
            dialog.setOnDismissListener(null)
            dialog.dismiss()
        }

        popupSession?.let { session ->
            runCatching { session.close() }
        }
        popupSession = null
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}
