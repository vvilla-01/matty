package ceo.villa.matty.plugins.villanet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import ceo.villa.matty.MainActivity
import ceo.villa.matty.R
import ceo.villa.matty.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps a live stream playing, for its sound, while Matty is minimised or the screen is off (the
 * picture nobody can see is not decoded meanwhile: see [WhepPlayer.pictureWanted]). Android only
 * lets an app carry on in the background as a foreground service with a notification, so this is
 * that: up while a stream plays, gone the moment it stops. The stream itself stays in
 * [VillaNetPlugin]'s player; this holds the process awake and gives the controls a media
 * notification should have: mute, stop, and a tap back into the app.
 */
class StreamService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var following: Job? = null
    private var session: MediaSession? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    private fun plugin(): VillaNetPlugin? =
        applicationContext.container.plugins.active.value.firstNotNullOfOrNull { it as? VillaNetPlugin }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        live = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Stream playing", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a stream plays, so it can keep playing with Matty minimised"
                setShowBadge(false)
            }
        )
        session = MediaSession(this, "Matty stream").apply {
            setCallback(object : MediaSession.Callback() {
                // A live stream can't pause: the play/pause button (a headset's too) mutes it.
                override fun onPause() { plugin()?.player?.muted = true }
                override fun onPlay() { plugin()?.player?.muted = false }
                override fun onStop() { plugin()?.stop() }
                override fun onCustomAction(action: String, extras: android.os.Bundle?) { if (action == ACTION_STOP) plugin()?.stop() }
            })
            isActive = true
        }
        // The screen going off mustn't put the stream to sleep with it.
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "matty:stream").apply {
            setReferenceCounted(false)
            acquire(MAX_AWAKE_MS)
        }
        @Suppress("DEPRECATION")
        wifi = applicationContext.getSystemService(WifiManager::class.java)?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "matty:stream")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val plugin = plugin()
        when (intent?.action) {
            ACTION_STOP -> plugin?.stop()
            ACTION_MUTE -> plugin?.player?.let { it.muted = !it.muted }
        }
        val player = plugin?.player
        // Started as a foreground service: it has to become one, even when it's about to go.
        val now = plugin?.let { describe(it) }
        runCatching {
            startForeground(NOTIFICATION_ID, notification(now), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        }.onFailure { stopSelf(); return START_NOT_STICKY }
        up = this
        if (plugin == null || player == null || now == null) { finish(); return START_NOT_STICKY }
        if (following == null) following = scope.launch {
            // Muted with Matty off screen is a stream nobody sees or hears, held up by this service,
            // its wake lock and the whole download. "Pause" (a headset's button, headphones dropping
            // out, the notification's Mute) gets a few minutes to become "play" again; then the
            // stream is let go. A muted picture being watched on screen is left alone.
            launch {
                player.mutedState.collectLatest { muted ->
                    var unseen = 0L
                    while (muted && unseen < MUTED_UNSEEN_MS) {
                        delay(MUTED_CHECK_MS)
                        unseen = if (plugin.repo?.appInForeground == false) unseen + MUTED_CHECK_MS else 0L
                    }
                    if (muted) plugin.stop()
                }
            }
            combine(player.state, player.mutedState, plugin.playingHandle, plugin.snapshot, plugin.access) { _, _, _, _, _ -> describe(plugin) }
                // A snapshot arrives for anything at all (a viewer joining somebody else's stream):
                // the notification is only posted again when what it says has changed.
                .distinctUntilChanged().collect { playing ->
                if (playing == null) finish() else show(playing)
            }
        }
        return START_NOT_STICKY
    }

    /** What's playing, or null when nothing is any more (stopped, failed, the stream ended). */
    private fun describe(plugin: VillaNetPlugin): Playing? {
        val handle = plugin.playingHandle.value ?: return null
        val player = plugin.player ?: return null
        val state = player.state.value
        if (state != WhepState.Connecting && state != WhepState.Playing) return null
        val stream = plugin.snapshot.value?.streams?.firstOrNull { it.handle == handle }
        val name = plugin.shownName
        return Playing(
            title = stream?.name?.ifBlank { null } ?: "Live stream",
            text = when {
                state == WhepState.Connecting -> "Connecting…"
                player.muted -> "Muted · Live on $name"
                else -> stream?.let { streamDescription(it) }?.ifBlank { null } ?: "Live on $name"
            },
            muted = player.muted,
            name = name,
        )
    }

    /** What the notification says; [name] is what the server calls the plugin. */
    private data class Playing(val title: String, val text: String, val muted: Boolean, val name: String)

    private fun show(p: Playing) {
        session?.apply {
            setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, p.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, p.name)
                // Live: no length, so no seek bar.
                .putLong(MediaMetadata.METADATA_KEY_DURATION, -1)
                .build())
            setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
                .addCustomAction(PlaybackState.CustomAction.Builder(ACTION_STOP, "Stop", android.R.drawable.ic_menu_close_clear_cancel).build())
                .setState(if (p.muted) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build())
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(p))
    }

    private fun notification(p: Playing?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun action(code: Int, action: String, label: String, icon: Int) = Notification.Action.Builder(
            Icon.createWithResource(this, icon), label,
            PendingIntent.getService(this, code, Intent(this, StreamService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE),
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_matty)
            .setContentTitle(p?.title ?: "Live stream")
            .setContentText(p?.text ?: "Live on ${p?.name ?: PRODUCT_NAME}")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            // On a locked screen (when the person hides sensitive content) only that a stream
            // is playing, not whose or what about, as with the message notifications.
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(
                Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_matty)
                    .setContentTitle("Live stream")
                    .setContentText("Playing on ${p?.name ?: PRODUCT_NAME}")
                    .setOngoing(true)
                    .setCategory(Notification.CATEGORY_TRANSPORT)
                    .build()
            )
            .addAction(
                if (p?.muted == true) action(1, ACTION_MUTE, "Unmute", android.R.drawable.ic_lock_silent_mode_off)
                else action(1, ACTION_MUTE, "Mute", android.R.drawable.ic_lock_silent_mode)
            )
            .addAction(action(2, ACTION_STOP, "Stop", android.R.drawable.ic_menu_close_clear_cancel))
            .setStyle(Notification.MediaStyle().setMediaSession(session?.sessionToken).setShowActionsInCompactView(0, 1))
            .build()
    }

    private fun finish() {
        if (up === this) up = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Swiped away from recents: that's closing Matty, and the stream goes with it. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        plugin()?.stop()
        finish()
    }

    override fun onDestroy() {
        // Only its own: a newer instance may already be up (one stream stopped, another started).
        if (live === this) live = null
        if (up === this) up = null
        following?.cancel()
        scope.cancel()
        session?.apply { isActive = false; release() }
        session = null
        wake?.let { runCatching { if (it.isHeld) it.release() } }
        wifi?.let { runCatching { if (it.isHeld) it.release() } }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "stream"
        private const val NOTIFICATION_ID = 7002
        private const val ACTION_STOP = "ceo.villa.matty.STREAM_STOP"
        private const val ACTION_MUTE = "ceo.villa.matty.STREAM_MUTE"
        /** No stream is watched longer in one go; the lock lets go by itself if anything else fails to. */
        private const val MAX_AWAKE_MS = 12L * 60 * 60 * 1000

        /** A stream muted off screen for this long is let go: see onStartCommand. */
        private const val MUTED_UNSEEN_MS = 5L * 60 * 1000
        private const val MUTED_CHECK_MS = 15_000L

        /** The instance alive now, and the one that has become a foreground service (only that one may be told to go). */
        @Volatile private var live: StreamService? = null
        @Volatile private var up: StreamService? = null

        /** Whether the service is up: while it is, the stream may keep playing in the background. */
        val running: Boolean get() = live != null

        /**
         * Brings the service up for the stream that just started. Only possible while Matty is on
         * screen (Android refuses otherwise); false when it couldn't, and then the stream stops
         * with the app as it used to.
         */
        fun start(context: Context): Boolean =
            runCatching { context.startForegroundService(Intent(context, StreamService::class.java)) }.isSuccess

        /**
         * Lets the service go. Never by name: stopping "the service" while a start is still on
         * its way would take down a start that hasn't become a foreground service yet, which
         * Android answers by crashing the app. The instance that is up stops itself; a start still
         * pending finds nothing playing when it arrives, and goes then.
         */
        fun stop() { up?.finish() }
    }
}
