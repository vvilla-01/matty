package ceo.villa.matty.plugins.villanet

import ceo.villa.matty.BuildConfig
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import ceo.villa.matty.AppContainer
import ceo.villa.matty.NeverCrash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription

/**
 * The app's one voice session: this phone in a voice channel of the server on screen, through
 * Matty Plugin (from 4.3.0), which passes the audio on to its voice server. What it does is
 * decided by [VoiceCore]; this does it, with WebRTC ([VoiceRtc], [VoicePeer]), the plugin's
 * `/voice` routes, the call's audio ([VoiceAudio]) and [VoiceService], which keeps the call going
 * with Matty minimised. Main thread only.
 *
 * What a screen uses: the flows below, [join], [move], [setMuted], [setDeafened], [chooseOutput],
 * [leave] (also from Failed, which forgets the sentence), and [VoiceMicAsk], composed outside any
 * sheet, which asks for the microphone when [micWanted] says so.
 */
class VoiceSession internal constructor(private val c: AppContainer, private val plugin: VillaNetPlugin) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + NeverCrash)
    private val port = Port()
    /** Where a refusal of the microphone for good is kept, so that what the sheet says stays true after a restart. */
    private val prefs get() = c.app.getSharedPreferences("matty_device", Context.MODE_PRIVATE)
    internal val core = VoiceCore(port, refusedForGood = prefs.getBoolean(KEY_MIC_REFUSED, false) && !micGranted())
    private val audio by lazy { VoiceAudio(c.app) { held -> core.held(held) } }

    /** Where the session stands. */
    val state: StateFlow<VoiceStatus> = core.status
    /** The channel joined (or being joined, or moved to); null while not in voice. */
    val channel: StateFlow<Int?> = core.channel
    /** The channel of the session that ended last by a failure, to join again; null after a join or a leave. */
    val lastChannel: StateFlow<Int?> = core.lastChannel
    /** A move on its way, to this channel. */
    val moving: StateFlow<Int?> = core.moving
    /** This phone's own place: its session, its name on the voice server, its channel. */
    val seat: StateFlow<VoiceSeat?> = core.seat
    /**
     * Muted as shown: as it holds (the person's choice, deafened, a phone call, no microphone
     * permission), or as the voice server holds it once that has had its time (see [VoiceCore]).
     * A tap asks for the opposite of this.
     */
    val muted: StateFlow<Boolean> = core.shownMuted
    /** Deafened as shown: as it holds (the person's choice, or a phone call), or as the voice server holds it. */
    val deafened: StateFlow<Boolean> = core.shownDeafened
    /** The person's own choices, apart from what is added to them. */
    val selfMuted: StateFlow<Boolean> = core.selfMuted
    val selfDeafened: StateFlow<Boolean> = core.selfDeafened
    /** A phone call holds the session: deafened and muted until it ends. */
    val held: StateFlow<Boolean> = core.held
    /** The voice server muted this session. */
    val serverMuted: StateFlow<Boolean> = core.serverMuted
    /** This session may not speak in this channel. */
    val suppressed: StateFlow<Boolean> = core.suppressed
    /**
     * Muted as the others in the channel hear it: as [muted] shows, or held muted by the voice
     * server (muted or suppressed). The own tile and the microphone's icon show this; a tap still
     * acts on [muted].
     */
    val seenMuted: StateFlow<Boolean> = core.seenMuted
    /** The server's own sentence while a join it refused is waited out (see [VoiceCore.waitWords]). */
    val waitWords: StateFlow<String?> = core.waitWords
    /** The link is down while the core waits to see whether it comes back: a connected session shows as reconnecting ([voiceShownStatus]). */
    val linkDown: StateFlow<Boolean> = core.linkDown
    /** The sessions being heard on this phone now, this phone's own included while its voice goes out and it is not seen muted ([seenMuted]); nobody while [linkDown]. */
    val talking: StateFlow<Set<Long>> = core.talking
    /** Whether the microphone may be used. */
    val mic: StateFlow<MicPermission> = core.mic
    /** The person asked to talk without the permission: [VoiceMicAsk] asks for it. */
    val micWanted: StateFlow<Boolean> = core.micWanted
    /** Since when (wall-clock ms) the session has been in voice; 0 while not. */
    val since: StateFlow<Long> = core.since
    /** The outputs on offer, and the one in use. */
    val outputs: StateFlow<List<VoiceOutput>> get() = audio.outputs
    val output: StateFlow<VoiceOutput?> get() = audio.output

    /** Not idle and not failed. */
    val active: Boolean get() = core.active

    private var api: VillaNetApi? = null
    private var rtc: VoiceRtc? = null
    private var peer: VoicePeer? = null
    private var connecting: Job? = null
    private var networkWatch: ConnectivityManager.NetworkCallback? = null

    /** POST /voice/state, the newest state one request at a time; what the server answered goes to the core. */
    private val states = VoiceStateSender(
        scope,
        post = post@{ muted, deafened ->
            val a = api ?: return@post voiceRefusalOf(null, null, 0, plugin.shownName)
            try {
                a.voiceState(muted, deafened)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: VillaNetException) {
                voiceRefusalOf(e.status, e.serverMessage, e.retryAfterMs, plugin.shownName, e.code)
            } catch (e: Exception) {
                voiceRefusalOf(null, null, 0, plugin.shownName)
            }
        },
        done = { on, refusal -> if (refusal == null) core.stateSent(on) else core.stateUnsent(on, refusal) },
    )

    /** Joins [channel] on the server on screen, muted or not; in another channel already, moves there. */
    fun join(channel: Int, muted: Boolean) {
        val a = plugin.api ?: return
        val r = plugin.repo ?: return
        if (!core.active) api = a
        core.micChecked(micGranted())
        core.join(channel, muted, r.myId)
        renewForMic()
    }

    fun move(channel: Int) = core.move(channel)
    fun setMuted(on: Boolean) = setMuted(on, fromService = false)
    fun setDeafened(on: Boolean) = core.setDeafened(on)

    /** While true, [VoiceService] is not asked to take the microphone into its type. */
    private var fromService = false

    /**
     * Mutes or unmutes. [fromService]: the voice service or its notification's button asks, maybe
     * with Matty in the background, so the service's type is left alone; from the app, an unmute
     * gives the service the microphone when the permission is there.
     */
    internal fun setMuted(on: Boolean, fromService: Boolean) {
        this.fromService = fromService
        try {
            core.micChecked(micGranted())
            core.setMuted(on)
        } finally {
            this.fromService = false
        }
        if (!on && !fromService) micToService()
        renewForMic()
    }
    fun leave() = core.leave()

    /**
     * The notification's Unmute while the call's service could not use the microphone: it opens
     * Matty, which unmutes here, on screen, so that the service can take the microphone first.
     */
    internal fun unmuteOnScreen() {
        if (core.active && core.shownMuted.value) setMuted(false)
    }

    /** The person picked an output ([VoiceOutput.id]); null goes back to the automatic choice. */
    fun chooseOutput(id: Int?) = audio.choose(id)

    /** What the permission request answered, and whether a refusal is for good (see [VoiceMicAsk]). */
    fun micAnswered(granted: Boolean, forGood: Boolean) {
        core.micAnswered(granted, forGood)
        renewForMic()
    }

    /** Opens Matty's page in Android's settings, where a microphone refused for good can be allowed. */
    fun micSettings(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun micGranted() = c.app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** The plugin is going (a server switch, a sign-out): voice is left first, with the plugin's client still in hand. */
    internal fun detached() = core.leave()

    /** A stream is about to play beside voice: the microphone is muted unless the output is private. */
    internal fun streamStarting() {
        if (core.active) core.streamStarted(audio.output.value?.private == true)
    }

    /** A stream stopped: voice takes the audio focus back. */
    internal fun streamStopped() { if (core.active) audio.regainFocus() }

    /** Matty came back to the front: the permission may have been changed in Settings meanwhile. */
    internal fun foreground() = recheckMic()

    /**
     * Reads the permission again (Matty came to the front, or the join sheet opened), whether or
     * not a call is on; in a call, the service takes the microphone when it is allowed.
     */
    internal fun recheckMic() {
        core.micChecked(micGranted())
        micToService()
        renewForMic()
    }

    /**
     * The permission is there now, and the call's audio was made without it, so it can never
     * record: the connection is made again with new audio, and the server takes the waiting
     * voice session over.
     */
    private fun renewForMic() {
        if (core.active && rtc?.withMic == false && micGranted()) core.renew()
    }

    /** With Matty on screen and the permission there: the service takes the microphone into its type (if it hasn't). */
    private fun micToService() {
        if (core.active && micGranted()) VoiceService.micGranted()
    }

    /** What [VoiceService] shows: the channel's name, or a general word before the roster names it. */
    internal fun channelName(): String {
        val id = core.channel.value ?: return "Voice"
        return plugin.snapshot.value?.voice?.channels?.firstOrNull { it.id == id }?.name?.trim()?.ifEmpty { null } ?: "Voice"
    }

    /** The server the session is on (for the notification's tap). */
    internal fun serverId(): String? = plugin.repo?.server?.id

    internal val serverName: String get() = plugin.shownName

    // ---- the world, for the core -------------------------------------------------------------

    private inner class Port : VoicePort {
        override fun now(): Long = SystemClock.elapsedRealtime()
        override fun wall(): Long = System.currentTimeMillis()

        override fun later(ms: Long, what: () -> Unit): () -> Unit {
            val r = Runnable(what)
            main.postDelayed(r, ms)
            return { main.removeCallbacks(r) }
        }

        override fun begin() {
            audio.start()
            _volumeStream.value = AudioManager.STREAM_VOICE_CALL
            // Started while Matty is on screen (the person just tapped Join): Android allows it then.
            if (!VoiceService.start(c.app)) Log.w("Matty", "voice: the service could not start")
            watchNetwork(true)
        }

        override fun connect(attempt: Int, join: VoiceJoin) {
            val a = api ?: return core.refused(attempt, VoiceRefusal(VoiceRetry.Stop, VillaNetApi.notAnswering(plugin.shownName)))
            closePeer()
            // Audio made without the microphone permission can never record: with the permission
            // there now, it goes (after its connection, above) and new audio is made with it.
            rtc?.let { old -> if (!old.withMic && this@VoiceSession.micGranted()) { old.release(); rtc = null } }
            connecting = scope.launch {
                try {
                    val r = rtc ?: VoiceRtc(c.app, this@VoiceSession.micGranted()).also { rtc = it }
                    r.adm.setSpeakerMute(core.deafened.value)
                    val p = VoicePeer.open(r, join.record, observer(attempt), dataObserver(attempt))
                    peer = p
                    if (BuildConfig.DEBUG) scope.launch { while (peer === p) { delay(2_000); if (peer === p) p.logStats() } }
                    // After every wait, the connection is still this one: a closed one is never touched again.
                    val offer = p.pc.awaitOffer(MediaConstraints())
                    if (peer !== p) return@launch
                    p.pc.awaitSetLocal(offer)
                    if (peer !== p) return@launch
                    p.pc.awaitGathering(GATHER_MS) { peer === p }
                    if (!core.current(attempt) || peer !== p) return@launch
                    val sdp = p.pc.localDescription?.description ?: offer.description
                    voiceOfferTrouble(sdp)?.let { Log.w("Matty", "voice: the offer is not as the contract asks: $it") }
                    // A state request of an earlier connection still out could reach the server
                    // after this join and undo what the join carries: it is waited for, two
                    // seconds at most, then given up and cancelled on the wire (over HTTP/2 that
                    // ends the request, not the connection). One already on its way may still
                    // arrive; the voice server's word then shows it.
                    states.settle()
                    if (!core.current(attempt) || peer !== p) return@launch
                    val answer = a.voiceJoin(join.channel, join.muted, join.deafened, sdp)
                    if (!core.current(attempt) || peer !== p) return@launch
                    // Mute may have changed while the join was on its way: as it is now, before the answer is set.
                    p.pc.setAudioRecording(core.recordingNow)
                    p.pc.awaitSetRemote(SessionDescription(SessionDescription.Type.ANSWER, answer.sdp))
                    if (!core.current(attempt) || peer !== p) return@launch
                    core.answered(attempt, answer)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: VillaNetException) {
                    if (core.current(attempt)) core.refused(attempt, voiceRefusalOf(e.status, e.serverMessage, e.retryAfterMs, plugin.shownName, e.code))
                } catch (e: Exception) {
                    Log.w("Matty", "voice: the join failed", e)
                    if (core.current(attempt)) core.refused(attempt, voiceRefusalOf(null, null, 0, plugin.shownName))
                }
            }
        }

        override fun disconnect() {
            closePeer()
            audio.connected = false
        }

        override fun end(tell: Boolean) {
            val a = api
            if (tell && a != null) c.appScope.launch { runCatching { a.voiceLeave() } }
            states.stop()
            closePeer()
            rtc?.release()
            rtc = null
            audio.stop()
            _volumeStream.value = AudioManager.STREAM_MUSIC
            VoiceService.stop()
            watchNetwork(false)
            api = null
        }

        override fun recording(on: Boolean) { peer?.pc?.setAudioRecording(on) }

        override fun speakerMuted(on: Boolean) { rtc?.adm?.setSpeakerMute(on) }

        /**
         * The newest state, one request at a time ([VoiceStateSender]). One the server didn't take
         * is reported to the core, which sends it again.
         */
        override fun sendState(attempt: Int, muted: Boolean, deafened: Boolean) {
            if (api == null) return
            states.send(attempt, muted, deafened)
        }

        override fun sendMove(attempt: Int, channel: Int) {
            val a = api ?: return
            scope.launch {
                try {
                    val moved = a.voiceMove(channel)
                    core.moved(attempt, moved.channel)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: VillaNetException) {
                    core.moveRefused(attempt, e.serverMessage?.trim()?.ifEmpty { null } ?: "Couldn't move to that channel.")
                } catch (e: Exception) {
                    core.moveRefused(attempt, VillaNetApi.notAnswering(plugin.shownName))
                }
            }
        }

        override fun online(): Boolean {
            val cm = c.app.getSystemService(ConnectivityManager::class.java) ?: return true
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        override fun micGranted() { if (!fromService) VoiceService.micGranted() }

        override fun rememberRefusal(forGood: Boolean) {
            runCatching { prefs.edit().apply { if (forGood) putBoolean(KEY_MIC_REFUSED, true) else remove(KEY_MIC_REFUSED) }.apply() }
        }

        override fun notice(text: String) { plugin.repo?.emitNotice(text) }
    }

    /** Closes the connection, and stops the join under way on it first (it never touches a closed one). */
    private fun closePeer() {
        connecting?.cancel()
        connecting = null
        peer?.close()
        peer = null
    }

    private fun watchNetwork(on: Boolean) {
        val cm = c.app.getSystemService(ConnectivityManager::class.java) ?: return
        if (on && networkWatch == null) {
            // The default network: which one it is (another one in its place makes the
            // connection again), and its loss (acted on only when no other is there).
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val id = network.networkHandle
                    main.post { core.network(true, id) }
                }
                override fun onLost(network: Network) { main.post { core.network(false) } }
            }
            if (runCatching { cm.registerDefaultNetworkCallback(cb) }.isSuccess) networkWatch = cb
        } else if (!on) {
            networkWatch?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            networkWatch = null
        }
    }

    private fun observer(attempt: Int) = object : PeerConnection.Observer {
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
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            val link = when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> VoiceLink.Connected
                PeerConnection.PeerConnectionState.DISCONNECTED -> VoiceLink.Disconnected
                PeerConnection.PeerConnectionState.FAILED -> VoiceLink.Failed
                else -> return
            }
            main.post {
                if (link == VoiceLink.Connected && core.current(attempt)) audio.connected = true
                core.link(attempt, link)
            }
        }
    }

    private fun dataObserver(attempt: Int): (DataChannel) -> DataChannel.Observer = { channel -> object : DataChannel.Observer {
        @Volatile private var opened = false
        override fun onBufferedAmountChange(previous: Long) {}

        override fun onStateChange() {
            val state = runCatching { channel.state() }.getOrNull() ?: return
            if (state == DataChannel.State.OPEN) opened = true
            if (state == DataChannel.State.CLOSED && opened) main.post { core.dataClosed(attempt) }
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            if (buffer.binary) return
            // Read now: the buffer is the library's, and gone once this returns.
            val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
            val text = String(bytes, Charsets.UTF_8)
            main.post { voiceMessageOf(text)?.let { core.message(attempt, it) } }
        }
    } }

    companion object {
        /** The longest the offer waits for this phone's own candidates. */
        private const val GATHER_MS = 2_000L
        /** Set while the microphone is refused for good (in "matty_device"); gone once it is found granted. */
        private const val KEY_MIC_REFUSED = "mic_refused_for_good"

        private val _volumeStream = MutableStateFlow(AudioManager.STREAM_MUSIC)
        /** What the volume keys control: the call's volume while in voice, media otherwise (MainActivity follows it). */
        val volumeStream: StateFlow<Int> = _volumeStream
    }
}

/**
 * Asks for the microphone when [voice] wants it: the person joined unmuted or unmuted without
 * the permission. Composed once, outside any sheet (a sheet's window is built afresh on a density
 * change, and a request launched from it would lose its answer). Refused, the person stays in the
 * channel, muted.
 */
@Composable
fun VoiceMicAsk(voice: VoiceSession) {
    val wanted by voice.micWanted.collectAsState()
    val activity = LocalActivity.current
    fun explains() = activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == true
    // Whether Android explained the request as it was made, kept while Matty's screen is made again behind the question.
    var explainedBefore by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Refused after Android had explained it, and it no longer would: refused for good, only
        // Settings can allow it now. A question dismissed without an answer is not.
        val remembered = voice.mic.value == MicPermission.Refused
        voice.micAnswered(granted, voiceMicRefusedForGood(granted, explainedBefore, explains(), remembered))
    }
    LaunchedEffect(wanted) {
        if (!wanted) return@LaunchedEffect
        explainedBefore = explains()
        runCatching { ask.launch(Manifest.permission.RECORD_AUDIO) }.onFailure { voice.micAnswered(false, forGood = true) }
    }
}
