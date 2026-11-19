package ceo.villa.matty.plugins.villanet

import android.content.Context
import android.media.AudioAttributes
import ceo.villa.matty.BuildConfig
import kotlinx.coroutines.delay
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.audio.JavaAudioDeviceModule

/** How voice channels sound: a call, speech. */
val VOICE_AUDIO: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

/**
 * The WebRTC factory for voice channels, beside the streams' ([Rtc], which stays as it is): its
 * own audio module, made for a call (voice attributes, mono both ways, the microphone source the
 * library uses by default, and WebRTC's own echo canceller, noise suppressor and gain control,
 * the phone's own hardware ones off). Made when a voice session starts, kept while it tries
 * again, and released when it ends. Main thread only.
 *
 * [withMic]: the microphone permission was granted when it was made. An audio module made without
 * it can never record (WebRTC readies the recording as the microphone's section is added, and
 * that fails for good), so the session makes a new one once the permission is there.
 */
internal class VoiceRtc(context: Context, val withMic: Boolean) {
    val adm: JavaAudioDeviceModule
    val factory: PeerConnectionFactory

    init {
        Rtc.ensureInitialized(context)
        adm = JavaAudioDeviceModule.builder(context.applicationContext)
            .setAudioAttributes(VOICE_AUDIO)
            .setUseStereoInput(false)
            .setUseStereoOutput(false)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            // The debug builds' test voice (VoiceTestSignal), switched by a debug-only receiver.
            .apply { if (BuildConfig.DEBUG) setAudioBufferCallback(VoiceTestSignal::onBuffer) }
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory()
    }

    /** The factory, then its audio module: after every connection made with it is closed. */
    fun release() {
        runCatching { factory.dispose() }
        runCatching { adm.release() }
    }
}

/**
 * One voice connection: the microphone's sending section, [VOICE_SLOTS] receiving sections (one
 * per voice the server passes on at once) and the data channel `voice`, which only the server
 * speaks on. [record] is set before anything is negotiated, so that a phone joining muted never
 * opens the microphone.
 */
internal class VoicePeer private constructor(
    val pc: PeerConnection,
    private val source: AudioSource,
    private val track: AudioTrack,
    val data: DataChannel,
) {
    /** Debug builds only: one log line of what goes out and comes in, for tests on an emulator. */
    fun logStats() {
        if (!BuildConfig.DEBUG) return
        runCatching {
            pc.getStats { report ->
                var sent = 0L
                var got = 0L
                var energy = 0.0
                var concealed = 0L
                var mic = 0.0
                for (stat in report.statsMap.values) {
                    when (stat.type) {
                        "outbound-rtp" -> sent += (stat.members["packetsSent"] as? Number)?.toLong() ?: 0L
                        "inbound-rtp" -> {
                            got += (stat.members["packetsReceived"] as? Number)?.toLong() ?: 0L
                            energy += (stat.members["totalAudioEnergy"] as? Number)?.toDouble() ?: 0.0
                            concealed += (stat.members["concealedSamples"] as? Number)?.toLong() ?: 0L
                        }
                        "media-source" -> mic = (stat.members["audioLevel"] as? Number)?.toDouble() ?: mic
                    }
                }
                android.util.Log.d("MattyVoice", "sent=$sent received=$got energy=${"%.5f".format(energy)} concealed=$concealed mic=${"%.3f".format(mic)}")
            }
        }
    }

    /** Closes it, in the order that frees everything: the connection, then the microphone's track and source. */
    fun close() {
        runCatching { data.unregisterObserver() }
        runCatching { pc.close() }
        runCatching { data.dispose() }
        runCatching { pc.dispose() }
        runCatching { track.dispose() }
        runCatching { source.dispose() }
    }

    companion object {
        /**
         * A voice connection's settings: no ICE servers, and nothing trickled either way (the
         * answer carries the server's candidates). Every section goes over one transport (max
         * bundle) and only UDP candidates are gathered, since the server listens on one UDP port:
         * the offer carries its candidates once.
         */
        fun config(): PeerConnection.RTCConfiguration = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
        }

        fun open(rtc: VoiceRtc, record: Boolean, observer: PeerConnection.Observer, channel: (DataChannel) -> DataChannel.Observer): VoicePeer {
            val pc = rtc.factory.createPeerConnection(config(), observer) ?: error("WebRTC unavailable")
            var source: AudioSource? = null
            var track: AudioTrack? = null
            var data: DataChannel? = null
            try {
                pc.setAudioRecording(record)
                val constraints = MediaConstraints().apply {
                    optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                    optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                    optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                    optional.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
                }
                val s = rtc.factory.createAudioSource(constraints).also { source = it }
                val t = rtc.factory.createAudioTrack("mic", s).also { track = it }
                pc.addTransceiver(t, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf("matty")))
                repeat(VOICE_SLOTS) {
                    pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
                }
                val d = pc.createDataChannel("voice", DataChannel.Init().apply { ordered = true })?.also { data = it } ?: error("no data channel")
                d.registerObserver(channel(d))
                return VoicePeer(pc, s, t, d)
            } catch (e: Throwable) {
                // Whatever was made goes again, in the order that frees everything, before the factory can be.
                data?.let { d -> runCatching { d.unregisterObserver() } }
                runCatching { pc.close() }
                data?.let { d -> runCatching { d.dispose() } }
                runCatching { pc.dispose() }
                track?.let { t -> runCatching { t.dispose() } }
                source?.let { s -> runCatching { s.dispose() } }
                throw e
            }
        }
    }
}

/** Waits until the connection has gathered its own candidates, [maxMs] at most (host candidates take a moment). */
internal suspend fun PeerConnection.awaitGathering(maxMs: Long, alive: () -> Boolean) {
    var waited = 0L
    while (alive() && iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE && waited < maxMs) {
        delay(50)
        waited += 50
    }
}
