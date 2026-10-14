package com.kernel.browser

import android.content.Context
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

object BrowserRuntime {
    @Volatile
    private var runtime: GeckoRuntime? = null

    fun get(context: Context): GeckoRuntime {
        return runtime ?: synchronized(this) {
            runtime ?: GeckoRuntime.create(context.applicationContext, settings()).also { runtime = it }
        }
    }

    private fun settings(): GeckoRuntimeSettings {
        return GeckoRuntimeSettings.Builder()
            // Lets addons.mozilla.org drive installs ("Add to Firefox") through our install prompt.
            .extensionsWebAPIEnabled(true)
            // Run extension background pages out of the main process so a heavy or crashing
            // extension can't take the browser UI down with it.
            .extensionsProcessEnabled(true)
            .consoleOutput(BuildConfig.DEBUG)
            .remoteDebuggingEnabled(BuildConfig.DEBUG)
            .aboutConfigEnabled(BuildConfig.DEBUG)
            .build()
    }
}
