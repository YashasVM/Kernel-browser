package com.kernel.browser

import android.app.Activity
import android.content.Intent
import android.view.MenuItem
import org.mozilla.geckoview.BasicSelectionActionDelegate
import org.mozilla.geckoview.GeckoSession

/**
 * The floating toolbar shown for selected page text: GeckoView's Cut / Copy / Paste /
 * Select all plus Android's text-processing apps (Translate, ...), with Kernel's own
 * "Search <engine>" and "Share" actions added in front of the app actions.
 *
 * The base class maps menu item ids to positions in [getAllActions], so the array must be
 * the same on every call; it is built once here.
 */
class KernelSelectionActionDelegate(
    activity: Activity,
    private val searchLabel: () -> String,
    private val onSearch: (String) -> Unit,
    private val onShare: (String) -> Unit
) : BasicSelectionActionDelegate(activity) {

    private val actions: Array<String> = super.getAllActions().let { base ->
        // Keep ACTION_PROCESS_TEXT (the third-party apps) last, like Chrome and Firefox do.
        val processIndex = base.indexOf(Intent.ACTION_PROCESS_TEXT)
        if (processIndex < 0) {
            base + arrayOf(ACTION_SEARCH, ACTION_SHARE)
        } else {
            base.copyOfRange(0, processIndex) + arrayOf(ACTION_SEARCH, ACTION_SHARE) +
                base.copyOfRange(processIndex, base.size)
        }
    }

    override fun getAllActions(): Array<String> = actions

    override fun isActionAvailable(id: String): Boolean {
        if (id == ACTION_SEARCH || id == ACTION_SHARE) {
            val selection = mSelection ?: return false
            val isPassword = (selection.flags and GeckoSession.SelectionActionDelegate.FLAG_IS_PASSWORD) != 0
            return !isPassword && WebFeatureLogic.selectionQuery(selection.text) != null
        }
        return super.isActionAvailable(id)
    }

    override fun prepareAction(id: String, item: MenuItem) {
        when (id) {
            ACTION_SEARCH -> item.setTitle(searchLabel())
            ACTION_SHARE -> item.setTitle("Share")
            else -> super.prepareAction(id, item)
        }
    }

    override fun performAction(id: String, item: MenuItem): Boolean {
        if (id != ACTION_SEARCH && id != ACTION_SHARE) return super.performAction(id, item)
        val text = mSelection?.text ?: return false
        if (id == ACTION_SEARCH) {
            val query = WebFeatureLogic.selectionQuery(text) ?: return false
            clearSelection()
            onSearch(query)
        } else {
            clearSelection()
            onShare(text)
        }
        return true
    }

    private companion object {
        const val ACTION_SEARCH = "com.kernel.browser.SELECTION_SEARCH"
        const val ACTION_SHARE = "com.kernel.browser.SELECTION_SHARE"
    }
}
