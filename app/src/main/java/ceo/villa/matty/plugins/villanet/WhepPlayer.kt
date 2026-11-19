package ceo.villa.matty.plugins.villanet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import ceo.villa.matty.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** How live streams play: like a video, not like a phone call. */
val MEDIA_AUDIO: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
    .build()

/**
 * Shared WebRTC plumbing: the library set up once per process, and the streams' factory and EGL
 * context (one for the whole process). Voice channels have a factory of their own ([VoiceRtc]).
 */
object Rtc {
    @Volatile private var factory: PeerConnectionFactory? = null
    @Volatile private var initialized = false
    val egl: EglBase by lazy { EglBase.create() }

    /**
     * Sets the library up, once per process. Both factories call it: setting it up again would
     * reset what it holds for the whole process under a factory already running.
     */
    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()
            )
            initialized = true
        }
    }

    fun factory(context: Context): PeerConnectionFactory = factory ?: synchronized(this) {
        factory ?: run {
            ensureInitialized(context)
            val adm = JavaAudioDeviceModule.builder(context.applicationContext)
                .setUseStereoOutput(true)
                // WebRTC plays as a voice call by default (USAGE_VOICE_COMMUNICATION), which
                // Android routes to the earpiece. A stream is media: speaker, headphones or Bluetooth.
                .setAudioAttributes(MEDIA_AUDIO)
                // Receive-only: no microphone processing, nothing that switches to call mode.
                .setUseHardwareAcousticEchoCanceler(false)
                .setUseHardwareNoiseSuppressor(false)
                .createAudioDeviceModule()
            PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .createPeerConnectionFactory()
                .also { factory = it }
        }
    }
}

enum class WhepState { Idle, Connecting, Playing, Failed }

/** What VillaNET says of a stream whose player has had nothing from it for a while: see [WhepPlayer]. */
enum class OnAir { Live, Paused, Gone, Unknown }

/**
 * Receive-only WHEP session, matching VillaNET's web player: audio then video recvonly
 * transceivers, no ICE servers, an ICE-restart offer with stereo Opus, and the offer POSTed
 * straight away (Broadcast Box finds the peer itself). Stopping is just closing the peer.
 *
 * Always both, the sound and the picture, in one session: a picture nobody is looking at
 * ([pictureWanted]) is switched off on this side only. The offer always carries both an audio and
 * a video section, as the web player's does.
 */
class WhepPlayer(
    private val context: Context,
    private val api: VillaNetApi,
    /**
     * What VillaNET says of the stream [handle] right now, asked afresh of the server (on the
     * main thread) once nothing has arrived from it for a while: still listed, paused ("Back
     * soon"), gone, or [OnAir.Unknown] when the server couldn't be asked.
     */
    private val onAir: suspend (handle: String) -> OnAir = { OnAir.Live },
) {
    companion object {
        /** The error when another app took the audio: the panel offers Resume rather than Try again. */
        const val PAUSED_FOR_AUDIO = "Paused because other audio started playing."
        private const val SILENCE_CHECK_MS = 5_000L
        /** This many checks with nothing received (25 s), and VillaNET is asked about the stream; and again every so many. */
        private const val SILENCE_CHECKS = 5
        /**
         * This many (six minutes) with nothing received while VillaNET still lists the stream as
         * on the air, and it is taken for dead. Long on purpose: VillaNET itself only learns that
         * a streamer paused when it next asks Broadcast Box, which an admin can set to as seldom
         * as every five minutes, and a pause must never be taken for an ending.
         */
        private const val DEAD_CHECKS = 72
        /**
         * A picture nobody can see (Matty minimised, the screen off) is still received for this
         * long: a glance at another app and back changes nothing. After it, only the sound is.
         */
        private const val PICTURE_OFF_AFTER_MS = 10_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var silenceWatch: kotlinx.coroutines.Job? = null
    private val audioManager = context.getSystemService(AudioManager::class.java)
    /** Volume the current audio focus allows (1 normally; 0 or a duck while something else talks). */
    private var focusGain = 1.0

    // Like any video player: hold audio focus while the stream can be heard.
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(MEDIA_AUDIO)
        .setOnAudioFocusChangeListener { change ->
            scope.launch {
                when (change) {
                    // Music or a clip took over for good: stop, and say so with a way back.
                    AudioManager.AUDIOFOCUS_LOSS -> if (!muted && _state.value != WhepState.Idle) {
                        stop()
                        _error.value = PAUSED_FOR_AUDIO
                        _state.value = WhepState.Failed
                    }
                    // A live stream can't pause: go quiet while a call, alarm or voice note plays.
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> setGain(0.0)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> setGain(0.2)
                    AudioManager.AUDIOFOCUS_GAIN -> setGain(1.0)
                }
            }
        }
        .build()

    private fun setGain(g: Double) {
        focusGain = g
        audioTrack?.setVolume(g)
    }

    // Headphones unplugged or Bluetooth dropped: don't let the stream jump to the loudspeaker.
    private var noisyRegistered = false
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) muted = true
        }
    }

    private fun listenForNoisy(on: Boolean) {
        if (on && !noisyRegistered) {
            context.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
            noisyRegistered = true
        } else if (!on && noisyRegistered) {
            runCatching { context.unregisterReceiver(noisy) }
            noisyRegistered = false
        }
    }

    private fun active() = _state.value == WhepState.Connecting || _state.value == WhepState.Playing
    private var pc: PeerConnection? = null
    /** Every view showing the stream (the panel's and full screen's can overlap as layouts change). */
    private val sinks = java.util.concurrent.CopyOnWriteArraySet<VideoSink>()
    private var videoTrack: VideoTrack? = null
    private var audioTrack: AudioTrack? = null
    private var generation = 0

    /** When the picture stopped being wanted; null while it is wanted. */
    private var unwantedSince: Long? = null
    /** Whether the picture is being received, and so decoded: only [receivePicture] changes it. */
    private var receiving = true

    private val _state = MutableStateFlow(WhepState.Idle)
    val state: StateFlow<WhepState> = _state

    /** Goes up with every play(): a view starts afresh (blank, poster up) for each session. */
    private val _session = MutableStateFlow(0)
    val session: StateFlow<Int> = _session
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _muted = MutableStateFlow(false)
    /** Observable, so the mute button follows changes made here (e.g. headphones unplugged). */
    val mutedState: StateFlow<Boolean> = _muted

    /** A muted stream doesn't hold audio focus: silent video shouldn't stop someone's music. */
    var muted: Boolean
        get() = _muted.value
        set(value) {
            _muted.value = value
            audioTrack?.setEnabled(!value)
            if (value) audioManager.abandonAudioFocusRequest(focus)
            else if (active()) { focusGain = 1.0; audioManager.requestAudioFocus(focus); audioTrack?.setVolume(focusGain) }
        }

    private val _picture = MutableStateFlow(true)
    /**
     * False from the moment the picture stops being received until the first frame after it is
     * received again: meanwhile a view has nothing new to draw, and shows what it last drew (or
     * the stream's still).
     */
    val picture: StateFlow<Boolean> = _picture

    /**
     * Whether anybody can see the picture: false while Matty is minimised or the screen is off,
     * when a stream plays on for its sound. A picture left unwanted is, after a short while, no
     * longer received at all, which is what spares decoding every frame of it for nobody (the
     * watch decides when: see [StreamWatch]). Wanted again, it is received again at once; it
     * shows from the stream's next key frame, which is asked for.
     *
     * This side only: Broadcast Box goes on sending the picture, and it is still downloaded.
     */
    var pictureWanted: Boolean
        get() = unwantedSince == null
        set(value) {
            if (value == pictureWanted) return
            unwantedSince = if (value) null else SystemClock.elapsedRealtime()
            if (value) receivePicture(true)
        }

    private val awaitingFrame = AtomicBoolean(false)
    private var awaitedSince = 0L
    /**
     * Told of the first frame after the picture is received again. Called on the decoder's
     * thread, for each frame until it's taken off the track: only the first is passed on.
     */
    private val firstFrameBack: VideoSink = VideoSink {
        if (awaitingFrame.compareAndSet(true, false)) scope.launch {
            if (receiving) {
                _picture.value = true
                if (BuildConfig.DEBUG) Log.d("Matty", "stream picture: its first frame, ${SystemClock.elapsedRealtime() - awaitedSince} ms after it was received again")
            }
            videoTrack?.let { t -> runCatching { t.removeSink(firstFrameBack) } }
        }
    }

    /**
     * Starts or stops receiving the picture. Stopped, its packets are dropped as they arrive:
     * nothing is put together or decoded, and nothing of it is counted as received. Started
     * again, the decoder begins afresh and waits for a key frame (asking for one).
     */
    private fun receivePicture(on: Boolean) {
        val track = videoTrack ?: return
        if (on == receiving) return
        receiving = on
        if (!on) {
            awaitingFrame.set(false)
            runCatching { track.removeSink(firstFrameBack) }
            _picture.value = false
        }
        val began = SystemClock.elapsedRealtime()
        runCatching { track.setShouldReceive(on) }.onFailure { Log.w("Matty", "Couldn't switch the stream's picture", it) }
        if (on) {
            awaitedSince = SystemClock.elapsedRealtime()
            awaitingFrame.set(true)
            runCatching { track.addSink(firstFrameBack) }
        }
        if (BuildConfig.DEBUG) Log.d("Matty", "stream picture: ${if (on) "received again" else "no longer received (sound only)"}, switched in ${SystemClock.elapsedRealtime() - began} ms")
    }

    fun attachSink(s: VideoSink) { if (sinks.add(s)) videoTrack?.addSink(s) }

    fun detachSink(s: VideoSink) { if (sinks.remove(s)) videoTrack?.let { t -> runCatching { t.removeSink(s) } } }

    fun play(handle: String) {
        stop()
        _session.value++
        val gen = ++generation
        _state.value = WhepState.Connecting
        _error.value = null
        focusGain = 1.0
        if (!muted) audioManager.requestAudioFocus(focus)
        listenForNoisy(true)
        scope.launch {
            try {
                val factory = Rtc.factory(context)
                val config = PeerConnection.RTCConfiguration(emptyList()).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                }
                val peer = factory.createPeerConnection(config, observer(gen, handle)) ?: error("WebRTC unavailable")
                pc = peer
                val recv = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
                peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, recv)
                peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, recv)
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
                }
                val offer = peer.awaitOffer(constraints)
                val munged = SessionDescription(offer.type, offer.description.replace("useinbandfec=1", "useinbandfec=1;stereo=1"))
                peer.awaitSetLocal(munged)
                val answer = api.whep(handle, munged.description)
                if (gen != generation) return@launch
                peer.awaitSetRemote(SessionDescription(SessionDescription.Type.ANSWER, answer))
            } catch (t: Throwable) {
                if (gen != generation) return@launch
                Log.w("Matty", "WHEP failed", t)
                _error.value = t.message ?: "Could not start the stream."
                _state.value = WhepState.Failed
                closePeer()
                audioManager.abandonAudioFocusRequest(focus)
                listenForNoisy(false)
            }
        }
    }

    /**
     * Watches the stream while it plays, a look every few seconds: lets go of one that has
     * stopped arriving (a paused one is waited for), and stops receiving a picture nobody is
     * looking at. What is decided, and why, is [StreamWatch]'s; this does the looking and the
     * doing.
     */
    private fun watchForSilence(gen: Int, handle: String) {
        // One watcher per session: a connection that drops and recovers reports CONNECTED again.
        silenceWatch?.cancel()
        silenceWatch = scope.launch {
            val watch = StreamWatch(SILENCE_CHECKS, DEAD_CHECKS, PICTURE_OFF_AFTER_MS)
            while (gen == generation && _state.value == WhepState.Playing) {
                kotlinx.coroutines.delay(SILENCE_CHECK_MS)
                val peer = pc ?: return@launch
                if (gen != generation || _state.value != WhepState.Playing) return@launch
                val got = runCatching { peer.received() }.getOrNull() ?: continue
                if (gen != generation || _state.value != WhepState.Playing) return@launch
                val look = watch.look(got.audio, got.video, unwantedSince?.let { SystemClock.elapsedRealtime() - it })
                receivePicture(look.picture)
                if (BuildConfig.DEBUG) Log.d("Matty", "stream watch: sound ${got.audio} B, picture ${got.video} B, ${got.frames} frames decoded; picture ${if (receiving) "received" else "not received"}${if (look.ask) "; nothing arriving, asking VillaNET" else ""}")
                if (!look.ask) continue
                if (!watch.answered(onAir(handle))) continue
                if (gen != generation || _state.value != WhepState.Playing) return@launch
                _error.value = "This stream has stopped."
                _state.value = WhepState.Failed
                closePeer()
                audioManager.abandonAudioFocusRequest(focus)
                listenForNoisy(false)
                return@launch
            }
        }
    }

    fun stop() {
        generation++
        silenceWatch?.cancel()
        silenceWatch = null
        closePeer()
        audioManager.abandonAudioFocusRequest(focus)
        listenForNoisy(false)
        _state.value = WhepState.Idle
    }

    fun release() {
        stop()
        scope.cancel()
    }

    private fun closePeer() {
        videoTrack?.let { t ->
            sinks.forEach { runCatching { t.removeSink(it) } }
            runCatching { t.removeSink(firstFrameBack) }
        }
        // The next session's picture is received from the start, whatever this one's was.
        awaitingFrame.set(false)
        receiving = true
        _picture.value = true
        videoTrack = null
        audioTrack = null
        pc?.let { runCatching { it.close(); it.dispose() } }
        pc = null
    }

    private fun observer(gen: Int, handle: String) = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(candidate: IceCandidate?) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            scope.launch {
                if (gen != generation) return@launch
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> { _state.value = WhepState.Playing; watchForSilence(gen, handle) }
                    // 'disconnected' recovers on its own most of the time; only 'failed' is final.
                    PeerConnection.PeerConnectionState.FAILED -> {
                        _error.value = "Lost the connection to this stream."
                        _state.value = WhepState.Failed
                        closePeer()
                        audioManager.abandonAudioFocusRequest(focus)
                        listenForNoisy(false)
                    }
                    else -> {}
                }
            }
        }

        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            val track = receiver?.track() ?: return
            scope.launch {
                if (gen != generation) return@launch
                when (track) {
                    is VideoTrack -> { videoTrack = track; sinks.forEach { track.addSink(it) } }
                    is AudioTrack -> { audioTrack = track; track.setEnabled(!muted); track.setVolume(focusGain) }
                }
            }
        }
    }
}

/** What has been received so far: the sound's bytes, the picture's, and the frames decoded. */
private class Received(val audio: Long, val video: Long, val frames: Long)

private suspend fun PeerConnection.received(): Received = suspendCancellableCoroutine { cont ->
    getStats { report ->
        var audio = 0L
        var video = 0L
        var frames = 0L
        for (stats in report.statsMap.values) {
            if (stats.type != "inbound-rtp") continue
            val bytes = (stats.members["bytesReceived"] as? Number)?.toLong() ?: 0L
            if (stats.members["kind"] == "audio") audio += bytes
            else {
                video += bytes
                frames += (stats.members["framesDecoded"] as? Number)?.toLong() ?: 0L
            }
        }
        if (cont.isActive) cont.resume(Received(audio, video, frames))
    }
}

internal suspend fun PeerConnection.awaitOffer(c: MediaConstraints): SessionDescription = suspendCancellableCoroutine { cont ->
    createOffer(object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) { cont.resume(sdp) }
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) { cont.resumeWithException(IllegalStateException(error ?: "offer failed")) }
        override fun onSetFailure(error: String?) {}
    }, c)
}

internal suspend fun PeerConnection.awaitSetLocal(sdp: SessionDescription): Unit = suspendCancellableCoroutine { cont ->
    setLocalDescription(object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() { cont.resume(Unit) }
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) { cont.resumeWithException(IllegalStateException(error ?: "local sdp failed")) }
    }, sdp)
}

internal suspend fun PeerConnection.awaitSetRemote(sdp: SessionDescription): Unit = suspendCancellableCoroutine { cont ->
    setRemoteDescription(object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() { cont.resume(Unit) }
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) { cont.resumeWithException(IllegalStateException(error ?: "remote sdp failed")) }
    }, sdp)
}
