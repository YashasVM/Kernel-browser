package com.kernel.browser

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

@HiltAndroidApp
class KernelApplication : Application() {

    lateinit var geckoRuntime: GeckoRuntime
        private set

    override fun onCreate() {
        super.onCreate()

        val settings = GeckoRuntimeSettings.Builder()
            .aboutConfigEnabled(true)
            .consoleOutput(true)
            .remoteDebuggingEnabled(BuildConfig.DEBUG)
            .build()

        geckoRuntime = GeckoRuntime.create(this, settings)
    }
}
