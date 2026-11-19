package ceo.villa.matty.plugins.villanet

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A call's audio on the phone: call mode while in voice ([AudioManager.MODE_IN_COMMUNICATION]),
 * audio focus held as a call holds it, and the output (speaker, earpiece, headset or Bluetooth)
 * through Android's communication device. A headset or Bluetooth is used when one is connected,
 * else the speaker; the person's own choice wins until that device goes. With the earpiece, the
 * screen goes off against the face. Main thread only.
 *
 * [onHeld] hears a transient loss of focus (a phone call: true) and its end (false); a lasting
 * loss (a stream or music that starts) is ignored.
 */
internal class VoiceAudio(private val context: Context, private val onHeld: (Boolean) -> Unit) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var started = false

    private val _outputs = MutableStateFlow<List<VoiceOutput>>(emptyList())
    /** The outputs a call can use now. */
    val outputs: StateFlow<List<VoiceOutput>> = _outputs
    private val _output = MutableStateFlow<VoiceOutput?>(null)
    /** The output in use. */
    val output: StateFlow<VoiceOutput?> = _output
    private var chosen: Int? = null

    /** Whether voice is connected: the earpiece's proximity lock is only held while it is. */
    var connected = false
        set(value) { field = value; proximity() }

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(VOICE_AUDIO)
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener({ change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onHeld(true)
                AudioManager.AUDIOFOCUS_GAIN -> onHeld(false)
                // A stream or music that starts beside the call: the call goes on as it is.
                AudioManager.AUDIOFOCUS_LOSS -> onHeld(false)
            }
        }, main)
        .build()

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = route()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = route()
    }
    private val changed = AudioManager.OnCommunicationDeviceChangedListener { route(apply = false) }

    private var ear: PowerManager.WakeLock? = null

    fun start() {
        if (started) return
        started = true
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.requestAudioFocus(focus)
        am.registerAudioDeviceCallback(devices, main)
        runCatching { am.addOnCommunicationDeviceChangedListener(context.mainExecutor, changed) }
        route()
    }

    /** Takes focus again (a stream that took it has stopped). */
    fun regainFocus() { if (started) am.requestAudioFocus(focus) }

    /** The person picked [id] as the output; null goes back to the automatic choice. */
    fun choose(id: Int?) {
        chosen = id
        route()
    }

    fun stop() {
        if (!started) return
        started = false
        connected = false
        chosen = null
        runCatching { am.unregisterAudioDeviceCallback(devices) }
        runCatching { am.removeOnCommunicationDeviceChangedListener(changed) }
        runCatching { am.clearCommunicationDevice() }
        am.mode = AudioManager.MODE_NORMAL
        am.abandonAudioFocusRequest(focus)
        _outputs.value = emptyList()
        _output.value = null
        proximity()
    }

    /** What is on offer now, and (with [apply]) the device picked from it set. */
    private fun route(apply: Boolean = true) {
        if (!started) return
        val infos = runCatching { am.availableCommunicationDevices }.getOrDefault(emptyList())
        val list = infos.mapNotNull { outputOf(it) }.distinctBy { it.id }
        _outputs.value = list
        chosen = keptChoice(list, chosen)
        if (apply) {
            val pick = pickOutput(list, chosen)
            val device = pick?.let { p -> infos.firstOrNull { it.id == p.id } }
            if (device != null && am.communicationDevice?.id != device.id) runCatching { am.setCommunicationDevice(device) }
        }
        val now = am.communicationDevice
        _output.value = now?.let { d -> list.firstOrNull { it.id == d.id } } ?: pickOutput(list, chosen)
        proximity()
    }

    private fun proximity() {
        val want = started && connected && _output.value?.kind == VoiceOutputKind.Earpiece
        if (want && ear == null) {
            val pm = context.getSystemService(PowerManager::class.java)
            if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                ear = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "matty:voice-ear").apply {
                    setReferenceCounted(false)
                    acquire(MAX_EAR_MS)
                }
            }
        } else if (!want) {
            ear?.let { runCatching { if (it.isHeld) it.release() } }
            ear = null
        }
    }

    private fun outputOf(d: AudioDeviceInfo): VoiceOutput? {
        val kind = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> VoiceOutputKind.Speaker
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> VoiceOutputKind.Earpiece
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> VoiceOutputKind.Headset
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_HEARING_AID -> VoiceOutputKind.Bluetooth
            AudioDeviceInfo.TYPE_TELEPHONY -> return null
            else -> VoiceOutputKind.Other
        }
        val name = when (kind) {
            VoiceOutputKind.Speaker -> "Speaker"
            VoiceOutputKind.Earpiece -> "Phone"
            VoiceOutputKind.Headset -> "Headset"
            VoiceOutputKind.Bluetooth -> d.productName?.toString()?.trim()?.ifEmpty { null } ?: "Bluetooth"
            VoiceOutputKind.Other -> d.productName?.toString()?.trim()?.ifEmpty { null } ?: "Other"
        }
        return VoiceOutput(d.id, kind, name)
    }

    private companion object {
        /** No call is held to the ear longer in one go; the lock lets go by itself if anything else fails to. */
        const val MAX_EAR_MS = 4L * 60 * 60 * 1000
    }
}
