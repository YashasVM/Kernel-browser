package com.kernel.browser.engine

import android.app.Application
import com.kernel.browser.KernelApplication
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.mozilla.geckoview.GeckoRuntime
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object GeckoEngineModule {

    @Provides
    @Singleton
    fun provideGeckoRuntime(app: Application): GeckoRuntime {
        return (app as KernelApplication).geckoRuntime
    }
}
