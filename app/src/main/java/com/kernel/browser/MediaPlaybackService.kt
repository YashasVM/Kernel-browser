package com.kernel.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.media.session.MediaSession as PlatformMediaSession

/**
 * Keeps page audio/video playing while Kernel is in the background and shows the media
 * notification (title, artwork, play/pause, stop). A framework MediaSession mirrors the page's
 * playback so headset buttons, Bluetooth controls and the lock screen work too.
 *
 * The service only renders [MediaPlaybackHub.state]; button presses go back to the page through
 * [MediaPlaybackHub.controls]. It is started (as a foreground service) by [MediaPlaybackController]
 * while the app is in the foreground and stops itself once nothing is playing any more.
 */
class MediaPlaybackService : Service() {
    private var mediaSession: PlatformMediaSession? = null
    private var inForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureChannel(this)
        mediaSession = PlatformMediaSession(this, "KernelMedia").apply {
            setCallback(object : PlatformMediaSession.Callback() {
                override fun onPlay() {
                    MediaPlaybackHub.controls?.play()
                }

                override fun onPause() {
                    MediaPlaybackHub.controls?.pause()
                }

                override fun onStop() {
                    MediaPlaybackHub.controls?.stop()
                }
            })
            setSessionActivity(contentIntent(this@MediaPlaybackService))
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = MediaPlaybackHub.state
        // startForegroundService() obliges us to call startForeground() promptly, even when the
        // media stopped in the meantime; do it first and stop right after if there is nothing to show.
        val notification = buildNotification(state ?: MediaPlaybackHub.State.placeholder())
        if (!inForeground) {
            inForeground = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                true
            }.getOrDefault(false)
        }
        when (intent?.action) {
            ACTION_PLAY -> MediaPlaybackHub.controls?.play()
            ACTION_PAUSE -> MediaPlaybackHub.controls?.pause()
            ACTION_STOP -> {
                MediaPlaybackHub.controls?.stop()
                shutDown()
                return START_NOT_STICKY
            }
        }
        if (state == null || !inForeground) {
            shutDown()
        } else {
            render(state)
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping Kernel away from Recents ends playback, like closing the tab would.
        MediaPlaybackHub.controls?.stop()
        shutDown()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        mediaSession?.run {
            isActive = false
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    /** Re-renders the notification and the platform session from the hub's current state. */
    fun refresh() {
        val state = MediaPlaybackHub.state
        if (state == null) shutDown() else render(state)
    }

    private fun render(state: MediaPlaybackHub.State) {
        val session = mediaSession ?: return
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (state.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (state.playing) 1f else 0f
                )
                .build()
        )
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, state.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, state.subtitle)
                .apply { state.artwork?.let { putBitmap(MediaMetadata.METADATA_KEY_ART, it) } }
                .build()
        )
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(state)) }
    }

    private fun shutDown() {
        mediaSession?.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        inForeground = false
        stopSelf()
    }

    private fun buildNotification(state: MediaPlaybackHub.State): Notification {
        val toggle = if (state.playing) {
            action(R.drawable.ic_pause, "Pause", ACTION_PAUSE, REQUEST_PAUSE)
        } else {
            action(R.drawable.ic_play, "Play", ACTION_PLAY, REQUEST_PLAY)
        }
        val stop = action(R.drawable.ic_close, "Stop", ACTION_STOP, REQUEST_STOP)
        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1)
        mediaSession?.let { style.setMediaSession(it.sessionToken) }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle(state.title)
            .setContentText(state.subtitle)
            .setLargeIcon(state.artwork)
            .setContentIntent(contentIntent(this))
            .setDeleteIntent(serviceIntent(ACTION_STOP, REQUEST_DELETE))
            .setVisibility(if (state.isPrivate) Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(state.playing)
            .addAction(toggle)
            .addAction(stop)
            .setStyle(style)
            .build()
    }

    private fun action(icon: Int, title: String, action: String, requestCode: Int): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, icon), title, serviceIntent(action, requestCode)).build()

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, MediaPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    companion object {
        private const val CHANNEL_ID = "media_playback"
        private const val NOTIFICATION_ID = 7301
        private const val ACTION_PLAY = "com.kernel.browser.media.PLAY"
        private const val ACTION_PAUSE = "com.kernel.browser.media.PAUSE"
        private const val ACTION_STOP = "com.kernel.browser.media.STOP"
        private const val REQUEST_PLAY = 1
        private const val REQUEST_PAUSE = 2
        private const val REQUEST_STOP = 3
        private const val REQUEST_DELETE = 4
        private const val REQUEST_CONTENT = 5

        /** The running service, if any. Only touched on the main thread. */
        private var instance: MediaPlaybackService? = null

        val isRunning: Boolean
            get() = instance != null

        /**
         * Shows or updates the notification for the hub's current state. Starting the service is
         * only attempted when [mayStart] (the app is in the foreground); Android 12+ refuses to
         * start foreground services from the background.
         */
        fun update(context: Context, mayStart: Boolean) {
            val running = instance
            if (running != null) {
                running.refresh()
                return
            }
            if (MediaPlaybackHub.state == null || !mayStart) return
            val intent = Intent(context, MediaPlaybackService::class.java)
            runCatching {
                context.startForegroundService(intent)
            }.onFailure { SafeLog.warning("Could not start media service", it) }
        }

        /** Stops the service (and removes the notification) once [MediaPlaybackHub.state] is null. */
        fun stop(context: Context) {
            val running = instance
            if (running != null) {
                running.refresh()
            } else {
                // Nothing running; also make sure no stale notification is left behind.
                runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
            }
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Media playback", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Controls for audio and video playing in Kernel"
                    setShowBadge(false)
                }
            )
        }

        private fun contentIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                REQUEST_CONTENT,
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}

/**
 * Shared, main-thread-only state between the browser (which knows about GeckoView sessions)
 * and [MediaPlaybackService] (which renders it).
 */
object MediaPlaybackHub {
    data class State(
        val title: String,
        val subtitle: String,
        val artwork: Bitmap?,
        val playing: Boolean,
        val isPrivate: Boolean
    ) {
        companion object {
            fun placeholder() = State("Media", "Kernel", null, playing = false, isPrivate = true)
        }
    }

    interface Controls {
        fun play()
        fun pause()
        fun stop()
    }

    /** What to show, or null when nothing is playing (the service then stops). */
    var state: State? = null

    var controls: Controls? = null
}
