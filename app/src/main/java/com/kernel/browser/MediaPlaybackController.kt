package com.kernel.browser

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import com.kernel.browser.tabs.BrowserTab
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.MediaSession

/**
 * Follows GeckoView's per-tab media sessions and publishes the one the user is listening to
 * (the most recently played) to [MediaPlaybackHub], starting [MediaPlaybackService] so playback
 * survives leaving the app and can be controlled from the notification, lock screen and headset.
 *
 * MainActivity forwards its MediaSession.Delegate callbacks here. Everything runs on the main thread.
 */
class MediaPlaybackController(
    private val activity: Activity,
    /** Called whenever which session is playing changes (picture-in-picture eligibility). */
    private val onPlaybackChanged: () -> Unit
) : MediaPlaybackHub.Controls {

    private class Entry(val tab: BrowserTab, var media: MediaSession) {
        var metadata: MediaSession.Metadata? = null
        var artwork: Bitmap? = null
        var playing = false
    }

    private val entries = LinkedHashMap<GeckoSession, Entry>()
    private var current: Entry? = null
    private var released = false

    /** True between the activity's onStart and onStop: only then may a foreground service start. */
    var isAppVisible = false
        private set

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(a: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(a: Activity) {
            if (a === activity) isAppVisible = true
        }
        override fun onActivityResumed(a: Activity) {
            // Coming back from the notification-permission dialog (or anywhere else): repost the
            // notification so a just-granted permission shows it right away.
            if (a === activity && MediaPlaybackService.isRunning) publish()
        }
        override fun onActivityPaused(a: Activity) = Unit
        override fun onActivityStopped(a: Activity) {
            if (a === activity) isAppVisible = false
        }
        override fun onActivitySaveInstanceState(a: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(a: Activity) = Unit
    }

    init {
        MediaPlaybackHub.controls = this
        activity.application.registerActivityLifecycleCallbacks(lifecycle)
    }

    fun onActivated(tab: BrowserTab, media: MediaSession) {
        entry(tab, media)
    }

    fun onMetadata(tab: BrowserTab, media: MediaSession, metadata: MediaSession.Metadata) {
        val entry = entry(tab, media)
        entry.metadata = metadata
        entry.artwork = null
        val artwork = metadata.artwork
        if (artwork != null && !tab.isPrivate) {
            runCatching {
                artwork.getBitmap(ARTWORK_SIZE_PX).accept({ bitmap ->
                    // Ignore artwork that arrives after the page moved on to another track.
                    if (bitmap != null && entry.metadata === metadata && !released) {
                        entry.artwork = bitmap
                        if (current === entry) publish()
                    }
                }, {
                    // Artwork is optional; the notification simply goes without it.
                })
            }
        }
        if (current === entry) publish()
    }

    fun onPlay(tab: BrowserTab, media: MediaSession) {
        val entry = entry(tab, media)
        entry.playing = true
        current = entry
        maybeRequestNotificationPermission()
        publish()
        onPlaybackChanged()
    }

    fun onPause(tab: BrowserTab, media: MediaSession) {
        val entry = entries[tab.session] ?: return
        entry.media = media
        entry.playing = false
        if (current === entry) publish()
        onPlaybackChanged()
    }

    /** onStop and onDeactivated: the page has no playable media any more. */
    fun onEnded(tab: BrowserTab) {
        remove(tab.session)
    }

    /** Drops sessions whose tab was closed; call after tabs change. */
    fun pruneClosedSessions() {
        entries.keys.filter { !it.isOpen }.forEach(::remove)
    }

    fun isPlaying(session: GeckoSession?): Boolean = session != null && entries[session]?.playing == true

    /** Pauses whatever [session] is playing (picture-in-picture closed, etc.). */
    fun pause(session: GeckoSession) {
        entries[session]?.media?.pause()
    }

    /** Ends everything; call from the activity's onDestroy. */
    fun release() {
        if (released) return
        released = true
        activity.application.unregisterActivityLifecycleCallbacks(lifecycle)
        entries.clear()
        current = null
        if (MediaPlaybackHub.controls === this) {
            MediaPlaybackHub.controls = null
            MediaPlaybackHub.state = null
            MediaPlaybackService.stop(activity)
        }
    }

    override fun play() {
        current?.media?.play()
    }

    override fun pause() {
        current?.media?.pause()
    }

    override fun stop() {
        val entry = current ?: return
        entry.media.pause()
        entry.media.stop()
        remove(entry.tab.session)
    }

    private fun entry(tab: BrowserTab, media: MediaSession): Entry {
        val existing = entries[tab.session]
        if (existing != null) {
            existing.media = media
            return existing
        }
        return Entry(tab, media).also { entries[tab.session] = it }
    }

    private fun remove(session: GeckoSession) {
        val entry = entries.remove(session) ?: return
        if (current === entry) {
            current = entries.values.lastOrNull { it.playing }
            publish()
        }
        onPlaybackChanged()
    }

    private fun publish() {
        if (released) return
        val entry = current
        if (entry == null || !entry.tab.session.isOpen) {
            MediaPlaybackHub.state = null
            MediaPlaybackService.stop(activity)
            return
        }
        val tab = entry.tab
        val metadata = entry.metadata
        MediaPlaybackHub.state = MediaPlaybackHub.State(
            title = WebFeatureLogic.mediaTitle(metadata?.title, tab.title, tab.url, tab.isPrivate),
            subtitle = WebFeatureLogic.mediaSubtitle(metadata?.artist, metadata?.album, tab.url, tab.isPrivate),
            artwork = if (tab.isPrivate) null else entry.artwork,
            playing = entry.playing,
            isPrivate = tab.isPrivate
        )
        // Only start the service for actual playback while Kernel is on screen; once running it
        // keeps itself in the foreground until the media stops, so background updates are safe.
        MediaPlaybackService.update(activity, mayStart = entry.playing && isAppVisible)
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !isAppVisible) return
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Ask once, at the first playback; if declined, media still plays in the background,
        // just without visible controls.
        if (prefs.getBoolean(KEY_ASKED, false)) return
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        runCatching { activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS) }
    }

    private companion object {
        const val ARTWORK_SIZE_PX = 256
        const val PREFS = "kernel_media"
        const val KEY_ASKED = "asked_notification_permission"
        const val REQUEST_NOTIFICATIONS = 61
    }
}
