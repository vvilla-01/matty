package ceo.villa.matty.plugins.villanet

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import ceo.villa.matty.MainActivity
import ceo.villa.matty.R
import ceo.villa.matty.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps a voice call going with Matty minimised or the screen off: a foreground service with the
 * notification "Voice Connected", the channel, since when, Mute or Unmute and Leave, up while a
 * voice session is and gone when it ends. The session itself is [VillaNetPlugin.voice]; this
 * holds the process awake (a wake lock and a Wi-Fi lock, as [StreamService] does).
 *
 * Its type: microphone and media playback when the microphone may be used as it starts, media
 * playback alone otherwise, gaining the microphone only when the permission is granted later
 * (with Matty on screen, so Android allows it): see [voiceServiceType]. Muting never changes it.
 */
class VoiceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var following: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var withMic = false

    private fun voice(): VoiceSession? =
        applicationContext.container.plugins.builtIn.firstNotNullOfOrNull { it as? VillaNetPlugin }?.voice

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        live = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Voice", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while you are in a voice channel, so the call goes on with Matty minimised"
                setShowBadge(false)
            }
        )
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "matty:voice").apply {
            setReferenceCounted(false)
            acquire(MAX_AWAKE_MS)
        }
        @Suppress("DEPRECATION")
        wifi = applicationContext.getSystemService(WifiManager::class.java)?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "matty:voice")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val voice = voice()
        // Whether this instance is a foreground service already, before a Leave lets it go.
        val foreground = up === this
        val action = intent?.action
        // Without the microphone in its type, an unmute from here would send nothing: that Unmute
        // opens Matty instead (see [voiceNotificationMicOf]), and one sent before stays muted.
        var unheard = false
        when (action) {
            ACTION_MUTE -> voice?.let {
                if (voiceNotificationMicOf(it.muted.value, withMic) == VoiceNotificationMic.OpenToUnmute) unheard = true
                else it.setMuted(!it.muted.value, fromService = true)
            }
            ACTION_LEAVE -> voice?.leave()
        }
        val now = voice?.let { describe(it) }
        if (unheard && foreground) now?.let { show(it) }
        val call = if (action != null) VoiceServiceCall.NotificationAction else VoiceServiceCall.Start
        // Started as a foreground service: it has to become one, even when it's about to go.
        voiceServiceTypeFor(call, foreground, withMic, micAllowed())?.let { type ->
            if (!promote(now, type, fallBack = true)) { stopSelf(); return START_NOT_STICKY }
        }
        if (call == VoiceServiceCall.NotificationAction) {
            // The notification's buttons never touch the type: the notification is brought up to
            // date by following the session. An instance that isn't the call's has nothing to do.
            if (!foreground) stopSelf()
            return START_NOT_STICKY
        }
        up = this
        if (voice == null || now == null) { finish(); return START_NOT_STICKY }
        if (following == null) following = scope.launch {
            combine(voice.state, voice.channel, voice.muted, voice.since, voice.deafened) { _, _, _, _, _ -> describe(voice) }
                .distinctUntilChanged().collect { d -> if (d == null) finish() else show(d) }
        }
        return START_NOT_STICKY
    }

    private fun micAllowed() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * Becomes a foreground service of [type] (see [voiceServiceTypeFor]). Refused the microphone,
     * the session stays muted, and the service goes on as it was: at its start ([fallBack]) with
     * media playback alone. Refused altogether, false.
     */
    private fun promote(d: Calling?, type: Int, fallBack: Boolean): Boolean {
        fun start(t: Int): Boolean {
            val had = withMic
            // The notification's Unmute is drawn for the type the service is about to have.
            withMic = t and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0
            return try {
                startForeground(NOTIFICATION_ID, notification(d), t)
                true
            } catch (e: SecurityException) {
                Log.w("Matty", "voice service refused", e); withMic = had; false
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.w("Matty", "voice service refused", e); withMic = had; false
            }
        }
        if (start(type)) return true
        if (type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE == 0) return false
        voice()?.setMuted(true, fromService = true)
        return !fallBack || start(voiceServiceType(false))
    }

    /** What the notification says, or null when there is no session any more. */
    private fun describe(v: VoiceSession): Calling? {
        val title = when (v.state.value) {
            VoiceStatus.Connected -> "Voice Connected"
            VoiceStatus.Connecting -> "Connecting…"
            VoiceStatus.Reconnecting -> "Reconnecting…"
            else -> return null
        }
        return Calling(title, v.channelName(), v.muted.value, v.since.value, v.serverName, v.serverId())
    }

    private data class Calling(val title: String, val channel: String, val muted: Boolean, val since: Long, val name: String, val serverId: String?)

    private fun show(d: Calling) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(d))
    }

    private fun notification(d: Calling?): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_VOICE)
            .putExtra(MainActivity.EXTRA_KEY, applicationContext.container.intentKey)
            .apply { d?.serverId?.let { putExtra(MainActivity.EXTRA_SERVER_ID, it) } }
        val open = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun action(code: Int, action: String, label: String, icon: Int) = Notification.Action.Builder(
            Icon.createWithResource(this, icon), label,
            PendingIntent.getService(this, code, Intent(this, VoiceService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE),
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_matty)
            .setContentTitle(d?.title ?: "Voice Connected")
            .setContentText(d?.let { if (it.muted) "${it.channel} · Muted" else it.channel } ?: "Voice")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_CALL)
            .apply {
                if (d != null && d.since > 0) setWhen(d.since).setShowWhen(true).setUsesChronometer(true)
                else setShowWhen(false)
            }
            // On a locked screen (when the person hides sensitive content) only that a call is on.
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(
                Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_matty)
                    .setContentTitle("Voice Connected")
                    .setContentText("In voice on ${d?.name ?: PRODUCT_NAME}")
                    .setOngoing(true)
                    .setCategory(Notification.CATEGORY_CALL)
                    .build()
            )
            .addAction(
                when (voiceNotificationMicOf(d?.muted == true, withMic)) {
                    VoiceNotificationMic.Mute -> action(1, ACTION_MUTE, "Mute", android.R.drawable.ic_lock_silent_mode)
                    VoiceNotificationMic.Unmute -> action(1, ACTION_MUTE, "Unmute", android.R.drawable.ic_btn_speak_now)
                    VoiceNotificationMic.OpenToUnmute -> Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_btn_speak_now), "Unmute",
                        PendingIntent.getActivity(this, 3, Intent(openIntent).putExtra(MainActivity.EXTRA_UNMUTE, true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                    ).build()
                }
            )
            .addAction(action(2, ACTION_LEAVE, "Leave", android.R.drawable.ic_menu_close_clear_cancel))
            .build()
    }

    private fun finish() {
        if (up === this) up = null
        withMic = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Swiped away from recents: that's closing Matty, and the call goes with it. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        voice()?.leave()
        finish()
    }

    override fun onDestroy() {
        if (live === this) live = null
        if (up === this) up = null
        following?.cancel()
        scope.cancel()
        wake?.let { runCatching { if (it.isHeld) it.release() } }
        wifi?.let { runCatching { if (it.isHeld) it.release() } }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "voice"
        private const val NOTIFICATION_ID = 7003
        private const val ACTION_MUTE = "ceo.villa.matty.VOICE_MUTE"
        private const val ACTION_LEAVE = "ceo.villa.matty.VOICE_LEAVE"
        /** No call is held up longer in one go; the lock lets go by itself if anything else fails to. */
        private const val MAX_AWAKE_MS = 12L * 60 * 60 * 1000

        @Volatile private var live: VoiceService? = null
        @Volatile private var up: VoiceService? = null

        /** Whether the service is up. */
        val running: Boolean get() = live != null

        /** Brings the service up for a call that is starting; only while Matty is on screen. False when Android refused. */
        fun start(context: Context): Boolean =
            runCatching { context.startForegroundService(Intent(context, VoiceService::class.java)) }.isSuccess

        /** Lets the service go (the instance that is up stops itself: see [StreamService.stop]). */
        fun stop() { up?.finish() }

        /**
         * The microphone is allowed, found with Matty on screen: the service takes the microphone
         * into its type when it hasn't it yet. Never from the notification's buttons.
         */
        fun micGranted() {
            val s = up ?: return
            val type = voiceServiceTypeFor(VoiceServiceCall.MicAllowed, foreground = true, s.withMic, s.micAllowed()) ?: return
            val v = s.voice() ?: return
            s.promote(s.describe(v), type, fallBack = false)
        }
    }
}
