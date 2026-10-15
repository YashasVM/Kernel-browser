package com.kernel.browser

import android.app.SearchManager
import android.content.Intent

/** Reads the fields [IncomingIntent] needs out of an Android [Intent]. */
object IncomingIntentReader {
    /** The URL [intent] asks Kernel to open, or null when it carries nothing to open. */
    fun urlFor(intent: Intent?, engine: SearchEngine): String? {
        intent ?: return null
        // Relaunching from Recents redelivers the original intent; don't reopen that page again.
        if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return null
        return runCatching {
            IncomingIntent.resolve(intent.action, intent.dataString, textOf(intent), engine)
        }.getOrNull()
    }

    private fun textOf(intent: Intent): String? {
        return when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_WEB_SEARCH, Intent.ACTION_SEARCH -> intent.getStringExtra(SearchManager.QUERY)
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            else -> null
        }
    }
}
