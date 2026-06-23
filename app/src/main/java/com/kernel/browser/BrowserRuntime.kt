package com.kernel.browser

import android.content.Context
import org.mozilla.geckoview.GeckoRuntime

object BrowserRuntime {
    @Volatile
    private var runtime: GeckoRuntime? = null

    fun get(context: Context): GeckoRuntime {
        return runtime ?: synchronized(this) {
            runtime ?: GeckoRuntime.create(context.applicationContext).also { runtime = it }
        }
    }
}
