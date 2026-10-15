package com.kernel.browser

import android.app.Activity
import android.app.Application
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.MediaSession

/**
 * Picture-in-picture for fullscreen web video: pressing Home while a video is fullscreen
 * shrinks Kernel into a floating window (automatically on Android 12+, from onUserLeaveHint
 * before that). Closing the floating window pauses the video and leaves fullscreen, like Chrome.
 */
class PictureInPictureController(
    private val activity: Activity,
    private val isPlaying: (GeckoSession) -> Boolean,
    /** The PiP window was closed (not expanded): pause [GeckoSession]'s media and leave fullscreen. */
    private val onDismissed: (GeckoSession) -> Unit
) {
    private var fullScreenSession: GeckoSession? = null
    private var videoFullscreen = false
    private var aspectRatio: Pair<Int, Int>? = null
    private var started = false
    private var released = false

    private val supported: Boolean =
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(a: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(a: Activity) {
            if (a === activity) started = true
        }
        override fun onActivityResumed(a: Activity) = Unit
        override fun onActivityPaused(a: Activity) = Unit
        override fun onActivityStopped(a: Activity) {
            if (a === activity) started = false
        }
        override fun onActivitySaveInstanceState(a: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(a: Activity) = Unit
    }

    init {
        activity.application.registerActivityLifecycleCallbacks(lifecycle)
    }

    val isInPictureInPicture: Boolean
        get() = activity.isInPictureInPictureMode

    /** From MediaSession.Delegate.onFullscreen: a media element entered or left fullscreen. */
    fun onMediaFullscreen(enabled: Boolean, meta: MediaSession.ElementMetadata?) {
        videoFullscreen = enabled && (meta == null || meta.videoTrackCount > 0 || meta.width > 0L)
        aspectRatio = if (enabled && meta != null) WebFeatureLogic.pipAspectRatio(meta.width, meta.height) else null
        updateParams()
    }

    /** From MainActivity.onFullScreenChanged: the page entered (session) or left (null) fullscreen. */
    fun onFullScreenChanged(session: GeckoSession?) {
        fullScreenSession = session
        if (session == null) {
            videoFullscreen = false
            aspectRatio = null
        }
        updateParams()
    }

    /** Playback started or stopped somewhere; auto-enter only applies while the video plays. */
    fun onPlaybackChanged() = updateParams()

    /**
     * From Activity.onUserLeaveHint (Home / Recents). Android 12+ enters PiP on its own through
     * setAutoEnterEnabled, so this only acts on older versions. Returns true when PiP was entered.
     */
    fun onUserLeaveHint(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return false
        if (!supported || !eligible() || activity.isInPictureInPictureMode) return false
        return runCatching { activity.enterPictureInPictureMode(buildParams(autoEnter = false)) }
            .getOrDefault(false)
    }

    /** From Activity.onPictureInPictureModeChanged. */
    fun onPictureInPictureModeChanged(inPictureInPicture: Boolean) {
        // Closing the PiP window stops the activity before this callback; expanding it does not.
        // So leaving PiP while stopped means the window was closed rather than expanded.
        if (!inPictureInPicture && !started) dismissed()
    }

    fun release() {
        if (released) return
        released = true
        activity.application.unregisterActivityLifecycleCallbacks(lifecycle)
    }

    private fun dismissed() {
        val session = fullScreenSession ?: return
        if (!session.isOpen) return
        onDismissed(session)
    }

    private fun eligible(): Boolean {
        val session = fullScreenSession ?: return false
        return session.isOpen && (videoFullscreen || isPlaying(session))
    }

    private fun updateParams() {
        if (!supported || released) return
        runCatching { activity.setPictureInPictureParams(buildParams(autoEnter = eligible() && isPlayingFullscreen())) }
    }

    private fun isPlayingFullscreen(): Boolean = fullScreenSession?.let(isPlaying) == true

    private fun buildParams(autoEnter: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        aspectRatio?.let { (width, height) -> builder.setAspectRatio(Rational(width, height)) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter)
        }
        return builder.build()
    }
}
