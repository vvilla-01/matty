package ceo.villa.matty.plugins.villanet

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What a join carries: the channel, how it starts, and whether the microphone records from the start. */
internal data class VoiceJoin(val channel: Int, val muted: Boolean, val deafened: Boolean, val record: Boolean)

/** The state of a WebRTC connection, as the core needs it. */
internal enum class VoiceLink { Connecting, Connected, Disconnected, Failed }

/**
 * This phone's own place in voice, from the join's answer and the server's word since: its
 * session on the voice server, its name there, its channel, and the Mattermost account that joined.
 */
data class VoiceSeat(val session: Long, val name: String, val channel: Int, val userId: String)

/**
 * What [VoiceCore] asks of the world: WebRTC, the server, the audio and the clock. Every call is
 * made on one thread (the main one), and every answer must come back on it.
 */
internal interface VoicePort {
    /** Milliseconds on a clock that never jumps. */
    fun now(): Long
    /** Wall-clock milliseconds, for "since when". */
    fun wall(): Long
    /** Runs [what] in [ms]; the function given back cancels it. */
    fun later(ms: Long, what: () -> Unit): () -> Unit
    /** Brings up what a call needs (the voice factory, call audio, the service), once per session. */
    fun begin()
    /** Opens a connection and joins; answers [VoiceCore.answered] or [VoiceCore.refused] with [attempt]. */
    fun connect(attempt: Int, join: VoiceJoin)
    /** Closes the connection, keeping what [begin] brought up. */
    fun disconnect()
    /** Takes everything down; [tell] says the server is told (POST /voice/leave). */
    fun end(tell: Boolean)
    /** Whether the microphone records (false lets go of it). */
    fun recording(on: Boolean)
    /** Whether what others say is silenced. */
    fun speakerMuted(on: Boolean)
    /**
     * Tells the server whether this phone is muted or deafened, on the connection of [attempt];
     * answers [VoiceCore.stateSent] or [VoiceCore.stateUnsent] with it.
     */
    fun sendState(attempt: Int, muted: Boolean, deafened: Boolean)
    /** Asks the server to move this session; answers [VoiceCore.moved] or [VoiceCore.moveRefused] with [attempt]. */
    fun sendMove(attempt: Int, channel: Int)
    /** Whether there is a network to try on. */
    fun online(): Boolean
    /** The microphone permission was just granted: the service may now use the microphone. */
    fun micGranted()
    /** Keeps whether the microphone is refused for good, over a restart of Matty ([VoiceCore]'s start reads it). */
    fun rememberRefusal(forGood: Boolean)
    /** A sentence for the person, shown as the app shows its notices. */
    fun notice(text: String)
}

/**
 * Voice channels' state machine: joining, the connection coming and going, trying again, mute
 * and deafen, the server's word on the data channel, and leaving. Pure: everything it does to the
 * world goes through [port], so the tests run it with a fake one and a fake clock.
 *
 * Mute and deafen: [selfMuted] and [selfDeafened] are the person's choices; [muted] and
 * [deafened] are what holds, which a phone call (a transient loss of audio focus) and a missing
 * microphone permission add to. Deafened is always muted too. The microphone records only while
 * not muted, and while the server neither mutes nor suppresses this session.
 *
 * [refusedForGood]: the microphone was refused for good before this start of Matty, and is not
 * granted now.
 */
internal class VoiceCore(private val port: VoicePort, refusedForGood: Boolean = false) {
    private val _status = MutableStateFlow<VoiceStatus>(VoiceStatus.Idle)
    val status: StateFlow<VoiceStatus> = _status

    /** The channel joined, or being joined or moved to; null while not in voice. */
    private val _channel = MutableStateFlow<Int?>(null)
    val channel: StateFlow<Int?> = _channel

    /** The channel of the session that ended last by a failure, to join again; null after a join or a leave. */
    private val _lastChannel = MutableStateFlow<Int?>(null)
    val lastChannel: StateFlow<Int?> = _lastChannel

    /** A move asked for and not answered yet. */
    private val _moving = MutableStateFlow<Int?>(null)
    val moving: StateFlow<Int?> = _moving

    private val _seat = MutableStateFlow<VoiceSeat?>(null)
    val seat: StateFlow<VoiceSeat?> = _seat

    private val _selfMuted = MutableStateFlow(false)
    val selfMuted: StateFlow<Boolean> = _selfMuted
    private val _selfDeafened = MutableStateFlow(false)
    val selfDeafened: StateFlow<Boolean> = _selfDeafened
    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted
    private val _deafened = MutableStateFlow(false)
    val deafened: StateFlow<Boolean> = _deafened
    /** Held by a phone call (a transient loss of audio focus): deafened and muted until it ends. */
    private val _held = MutableStateFlow(false)
    val held: StateFlow<Boolean> = _held

    private val _serverMuted = MutableStateFlow(false)
    val serverMuted: StateFlow<Boolean> = _serverMuted
    private val _suppressed = MutableStateFlow(false)
    val suppressed: StateFlow<Boolean> = _suppressed

    private val _talking = MutableStateFlow<Set<Long>>(emptySet())
    val talking: StateFlow<Set<Long>> = _talking

    private val _mic = MutableStateFlow(if (refusedForGood) MicPermission.Refused else MicPermission.NotAsked)
    val mic: StateFlow<MicPermission> = _mic
    /** The person asked to talk without the permission: a composable asks for it, and answers [micAnswered]. */
    private val _micWanted = MutableStateFlow(false)
    val micWanted: StateFlow<Boolean> = _micWanted

    /** Wall-clock milliseconds since when this session has been in voice (the first connection); 0 while not. */
    private val _since = MutableStateFlow(0L)
    val since: StateFlow<Long> = _since

    /** Whether the microphone records now. */
    val recordingNow: Boolean get() = !_muted.value && !_serverMuted.value && !_suppressed.value

    private var attempt = 0
    private var answered = false
    private var linked = false
    private var retries = 0
    private var waitingForNetwork = false
    /** A move asked for while the join was being answered: sent once connected. */
    private var pendingMove: Int? = null
    private var userId = ""
    /** What the server was last told (or the join carried). */
    private var told: Pair<Boolean, Boolean>? = null
    private var retryTimer: (() -> Unit)? = null
    /** A state the server didn't take, sent again on the retry steps. */
    private var stateTimer: (() -> Unit)? = null
    private var stateRetries = 0
    private var connectTimer: (() -> Unit)? = null
    private var dropTimer: (() -> Unit)? = null

    /** Whether a session is under way (not idle, not failed). */
    val active: Boolean get() = _status.value.let { it == VoiceStatus.Connecting || it == VoiceStatus.Connected || it == VoiceStatus.Reconnecting }

    /** Whether [a] is the attempt in hand: answers to any other are stale. */
    fun current(a: Int): Boolean = a == attempt && active

    // ---- what the person does --------------------------------------------------------------------

    /**
     * Joins [channel] as [userId], muted or not. Already in that channel: nothing. In another:
     * a move there. Unmuted without the permission: joined muted, and the permission asked for.
     */
    fun join(channel: Int, muted: Boolean, userId: String) {
        if (active) {
            if (channel != _channel.value) move(channel)
            return
        }
        this.userId = userId
        _lastChannel.value = null
        _selfMuted.value = muted
        _selfDeafened.value = false
        _held.value = false
        _serverMuted.value = false
        _suppressed.value = false
        _talking.value = emptySet()
        _seat.value = null
        _moving.value = null
        _since.value = 0
        _channel.value = channel
        retries = 0
        waitingForNetwork = false
        if (!muted && _mic.value != MicPermission.Granted) _micWanted.value = true
        _status.value = VoiceStatus.Connecting
        derive()
        port.begin()
        applyAudio()
        connectNow()
    }

    /** Moves to [channel]; before the connection is up, the next join carries it. */
    fun move(channel: Int) {
        if (!active) return
        if (_status.value != VoiceStatus.Connected || !answered) {
            // The join on its way carries the channel it was sent with: the move follows it.
            if (answered) pendingMove = channel else _channel.value = channel
            return
        }
        if (channel == _channel.value && _moving.value == null) return
        _moving.value = channel
        port.sendMove(attempt, channel)
    }

    /** Mutes or unmutes. Unmuting undeafens too, and without the permission asks for it. */
    fun setMuted(on: Boolean) {
        if (!on) {
            _selfDeafened.value = false
            if (_mic.value != MicPermission.Granted) _micWanted.value = true
        }
        _selfMuted.value = on
        changed()
    }

    /** Deafens (which mutes too) or undeafens (back to the mute chosen before). */
    fun setDeafened(on: Boolean) {
        _selfDeafened.value = on
        changed()
    }

    /** Leaves voice: the server is told, and everything is taken down. From Failed, only forgotten. */
    fun leave() {
        val was = _status.value
        if (was == VoiceStatus.Idle) return
        stopTimers()
        attempt++
        reset()
        _lastChannel.value = null
        _status.value = VoiceStatus.Idle
        if (was !is VoiceStatus.Failed) port.end(tell = true)
    }

    /**
     * What the permission request answered, and whether a refusal is [forGood] (Android won't ask
     * again). Refused, the person stays in, muted.
     */
    fun micAnswered(granted: Boolean, forGood: Boolean) {
        _micWanted.value = false
        val before = _mic.value
        _mic.value = when {
            granted -> MicPermission.Granted
            forGood -> MicPermission.Refused
            else -> MicPermission.Declined
        }
        if (granted && before != MicPermission.Granted) port.rememberRefusal(false)
        if (!granted && forGood && before != MicPermission.Refused) port.rememberRefusal(true)
        if (granted && before != MicPermission.Granted && active) port.micGranted()
        if (!granted) _selfMuted.value = true
        changed()
    }

    /** What Android says of the permission now (it may have been granted or taken away in Settings). */
    fun micChecked(granted: Boolean) {
        val before = _mic.value
        when {
            granted -> {
                _mic.value = MicPermission.Granted
                if (before != MicPermission.Granted) port.rememberRefusal(false)
                if (before != MicPermission.Granted && active) port.micGranted()
            }
            before == MicPermission.Granted -> _mic.value = MicPermission.NotAsked
            else -> return
        }
        changed()
    }

    /**
     * Makes the connection again at once, keeping the session on the voice server (the server
     * takes it over): the microphone was allowed after the connection's audio was made without
     * it, and that audio can never record. While trying again already, the next try is a new
     * connection anyway, and keeps its wait.
     */
    fun renew() {
        val was = _status.value
        if (was != VoiceStatus.Connecting && was != VoiceStatus.Connected) return
        stopTimers()
        _talking.value = emptySet()
        // The new join carries the channel a move was on its way to.
        (pendingMove ?: _moving.value)?.let { _channel.value = it }
        pendingMove = null
        _moving.value = null
        port.disconnect()
        if (was == VoiceStatus.Connected) _status.value = VoiceStatus.Reconnecting
        connectNow()
    }

    /** A phone call took the audio for a while ([on]), or gave it back. */
    fun held(on: Boolean) {
        if (_held.value == on) return
        _held.value = on
        changed()
    }

    /**
     * A stream starts playing beside the call: unless it is heard on a headset or Bluetooth
     * ([privateOutput]), the microphone is muted, since the echo canceller can't hear the stream.
     */
    fun streamStarted(privateOutput: Boolean) {
        if (active && !privateOutput && !_selfMuted.value) setMuted(true)
    }

    // ---- what the world answers ----------------------------------------------------------------

    /** The server took the join of [a]. */
    fun answered(a: Int, joined: VnVoiceJoined) {
        if (!current(a)) return
        answered = true
        _seat.value = VoiceSeat(joined.session, joined.name, joined.channel, userId)
        val wanted = _channel.value
        _channel.value = joined.channel
        // A change made while the join was on its way.
        if (told != (_muted.value to _deafened.value)) tell()
        if (wanted != null && wanted != joined.channel) pendingMove = wanted
        if (linked) up()
    }

    /** The server refused the join of [a], or nothing answered. */
    fun refused(a: Int, r: VoiceRefusal) {
        if (!current(a)) return
        when (r.retry) {
            VoiceRetry.Stop -> fail(r.sentence)
            VoiceRetry.Wait -> lost(r.waitMs)
        }
    }

    /** The connection of [a] changed. */
    fun link(a: Int, state: VoiceLink) {
        if (!current(a)) return
        when (state) {
            VoiceLink.Connecting -> {}
            VoiceLink.Connected -> {
                dropTimer?.invoke(); dropTimer = null
                linked = true
                if (answered) up()
            }
            // Disconnected often comes back by itself: only 5 s of it is a loss.
            VoiceLink.Disconnected -> if (dropTimer == null) dropTimer = port.later(DROP_MS) { dropTimer = null; if (current(a)) lost(0) }
            VoiceLink.Failed -> lost(0)
        }
    }

    /** The data channel of [a] closed. Without an `end` first, that is a lost connection. */
    fun dataClosed(a: Int) {
        if (current(a)) lost(0)
    }

    /** The server said [m] on the data channel of [a]. */
    fun message(a: Int, m: VoiceMessage) {
        if (!current(a)) return
        when (m) {
            is VoiceMessage.Talking -> _talking.value = m.sessions
            is VoiceMessage.Self -> {
                _channel.value = m.channel
                _seat.value = _seat.value?.copy(channel = m.channel)
                _serverMuted.value = m.muted
                _suppressed.value = m.suppressed
                applyAudio()
            }
            is VoiceMessage.End -> if (m.code == END_MUMBLE) lost(0) else fail(endSentence(m.code, m.reason))
        }
    }

    /** The server took the state sent on the connection of [a]. */
    fun stateSent(a: Int) {
        if (current(a)) stateRetries = 0
    }

    /**
     * The server didn't take the state sent on the connection of [a]. Refusals that are about
     * time (and no answer at all) send the newest state again on the retry steps, never sooner
     * than the server asked; any other is left to the rejoin, which carries the state.
     */
    fun stateUnsent(a: Int, r: VoiceRefusal) {
        if (!current(a) || !answered || r.retry != VoiceRetry.Wait) return
        stateTimer?.invoke()
        val wait = maxOf(voiceRetryDelay(stateRetries), r.waitMs)
        stateRetries++
        stateTimer = port.later(wait) { stateTimer = null; if (current(a) && answered) tell() }
    }

    /** The move of [a] was answered: in [channel] now. */
    fun moved(a: Int, channel: Int) {
        if (!current(a)) return
        _moving.value = null
        _channel.value = channel
        _seat.value = _seat.value?.copy(channel = channel)
    }

    /** The move of [a] was refused: the person is told why, and stays where they are. */
    fun moveRefused(a: Int, sentence: String) {
        if (!current(a)) return
        _moving.value = null
        _seat.value?.let { _channel.value = it.channel }
        port.notice(sentence)
    }

    /** The network came ([up]) or went. */
    fun network(up: Boolean) {
        if (up && waitingForNetwork && _status.value == VoiceStatus.Reconnecting) {
            waitingForNetwork = false
            connectNow()
        }
    }

    // ---- inside ----------------------------------------------------------------------------------

    private fun connectNow() {
        retryTimer?.invoke(); retryTimer = null
        connectTimer?.invoke(); connectTimer = null
        dropTimer?.invoke(); dropTimer = null
        attempt++
        answered = false
        linked = false
        stateRetries = 0
        val a = attempt
        val join = VoiceJoin(_channel.value ?: 0, _muted.value, _deafened.value, recordingNow)
        told = join.muted to join.deafened
        connectTimer = port.later(CONNECT_MS) { connectTimer = null; if (current(a) && !(answered && linked)) lost(0) }
        port.connect(a, join)
    }

    private fun up() {
        connectTimer?.invoke(); connectTimer = null
        retries = 0
        if (_since.value == 0L) _since.value = port.wall()
        _status.value = VoiceStatus.Connected
        pendingMove?.let { pendingMove = null; move(it) }
    }

    /** The connection is gone (or the server asked to wait [waitMs]): try again on the schedule. */
    private fun lost(waitMs: Long) {
        stopTimers()
        attempt++
        answered = false
        linked = false
        _talking.value = emptySet()
        // The next join carries the channel a move was on its way to.
        (pendingMove ?: _moving.value)?.let { _channel.value = it }
        pendingMove = null
        _moving.value = null
        port.disconnect()
        _status.value = VoiceStatus.Reconnecting
        val wait = maxOf(voiceRetryDelay(retries), waitMs)
        retries++
        val a = attempt
        retryTimer = port.later(wait) {
            retryTimer = null
            if (a != attempt || _status.value != VoiceStatus.Reconnecting) return@later
            if (!port.online()) waitingForNetwork = true else connectNow()
        }
    }

    private fun fail(sentence: String) {
        stopTimers()
        attempt++
        val last = _channel.value
        reset()
        _lastChannel.value = last
        _status.value = VoiceStatus.Failed(sentence)
        port.end(tell = false)
    }

    private fun reset() {
        pendingMove = null
        answered = false
        linked = false
        waitingForNetwork = false
        told = null
        _talking.value = emptySet()
        _seat.value = null
        _moving.value = null
        _since.value = 0
        _micWanted.value = false
        _held.value = false
        _serverMuted.value = false
        _suppressed.value = false
        _channel.value = null
    }

    private fun stopTimers() {
        retryTimer?.invoke(); retryTimer = null
        connectTimer?.invoke(); connectTimer = null
        dropTimer?.invoke(); dropTimer = null
        stateTimer?.invoke(); stateTimer = null
        stateRetries = 0
    }

    private fun derive() {
        val deaf = _selfDeafened.value || _held.value
        _deafened.value = deaf
        _muted.value = deaf || _selfMuted.value || _mic.value != MicPermission.Granted
    }

    private fun applyAudio() {
        if (!active) return
        port.recording(recordingNow)
        port.speakerMuted(_deafened.value)
    }

    private fun tell() {
        stateTimer?.invoke(); stateTimer = null
        told = _muted.value to _deafened.value
        port.sendState(attempt, _muted.value, _deafened.value)
    }

    /** A choice or a hold changed: what holds now, applied, and told to the server once it has the session. */
    private fun changed() {
        derive()
        if (!active) return
        applyAudio()
        if (answered && told != (_muted.value to _deafened.value)) tell()
    }

    companion object {
        /** A connection disconnected this long is taken for lost. */
        const val DROP_MS = 5_000L
        /** A join not connected in this long is tried again. */
        const val CONNECT_MS = 25_000L
    }
}

/**
 * Tells the server the newest mute and deafen state, one request at a time, so that two quick
 * taps never reach it the wrong way round. [post] sends one and gives back its refusal (null when
 * taken); [done] hears how the newest went, with the connection it was sent on. A request that
 * comes back after a newer state, or the same state on a newer connection, was asked for says
 * nothing: the newest goes next, and is the one heard.
 */
internal class VoiceStateSender(
    private val scope: CoroutineScope,
    private val post: suspend (muted: Boolean, deafened: Boolean) -> VoiceRefusal?,
    private val done: (attempt: Int, refusal: VoiceRefusal?) -> Unit,
) {
    private var wanted: Pair<Boolean, Boolean>? = null
    private var attempt = 0
    private var job: Job? = null

    /** Sends [muted] and [deafened] on the connection of [attempt], after the request out now, if any. */
    fun send(attempt: Int, muted: Boolean, deafened: Boolean) {
        wanted = muted to deafened
        this.attempt = attempt
        if (job?.isActive == true) return
        job = scope.launch {
            while (true) {
                val w = wanted ?: break
                val on = this@VoiceStateSender.attempt
                val refusal = post(w.first, w.second)
                if (wanted != w || this@VoiceStateSender.attempt != on) continue
                done(on, refusal)
                break
            }
        }
    }

    /** Forgets what was wanted, and stops the request out now. */
    fun stop() {
        job?.cancel()
        job = null
        wanted = null
    }
}
