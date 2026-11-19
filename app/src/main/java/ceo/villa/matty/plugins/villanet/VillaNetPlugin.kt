package ceo.villa.matty.plugins.villanet

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import ceo.villa.matty.AppContainer
import ceo.villa.matty.data.api.MattermostJson
import ceo.villa.matty.data.api.Post
import ceo.villa.matty.data.api.WsEvent
import ceo.villa.matty.data.api.str
import ceo.villa.matty.data.repo.ChatRepository
import ceo.villa.matty.data.api.SocketState
import ceo.villa.matty.data.runCatchingCancellable
import ceo.villa.matty.plugins.MattyPlugin
import ceo.villa.matty.plugins.PersonFacts
import ceo.villa.matty.plugins.PluginHost
import ceo.villa.matty.plugins.PluginPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Native VillaNET: live Broadcast Box streams over WHEP, the token unlock, who's in voice,
 * upcoming plans, the leaderboard, clips, and native cards for shared clips and plans.
 */
class VillaNetPlugin(private val c: AppContainer) : MattyPlugin {
    override val id = VILLANET_ID
    /** Until the server says what it calls the plugin: see [displayName]. */
    override val name = PRODUCT_NAME
    /** Links posted before 4.0.0 name the plugin's earlier id, and stand for the same clips and plans. */
    override val formerIds = setOf(PREDECESSOR_ID)
    override val description = "Watch live streams, browse and vote on clips, see who is live, join voice channels and talk, and join planned streams."
    override val postTypes = setOf(POST_CLIP, POST_PLAN)

    var api: VillaNetApi? = null; private set
    var repo: ChatRepository? = null; private set

    /** The reader's access, and which plugin answered (`GET /access`). Null until it has. */
    private val _access = MutableStateFlow<VnAccess?>(null)
    val access: StateFlow<VnAccess?> = _access

    /**
     * What this server calls the plugin, as it is just now: the name in `GET /access`, and
     * [PRODUCT_NAME] before the server has said or when it says nothing. For words made outside
     * composition; a screen reads [displayName], which follows it.
     */
    val shownName: String get() = nameOf(_access.value)

    @Composable
    override fun displayName(): String {
        val a by access.collectAsState()
        return nameOf(a)
    }

    @Composable
    override fun panelTitle(): String = displayName()

    /**
     * Set while this server's VillaNET speaks an older contract than this app is written for, to
     * its version ("" when that couldn't be told). The plugin and the app are written to one
     * contract and released as a pair. Out of step, nothing is asked of the plugin and nothing is
     * guessed about what it would have said: the panel says which VillaNET the app needs.
     */
    private val _outdated = MutableStateFlow<String?>(null)
    val outdated: StateFlow<String?> = _outdated

    private val _snapshot = MutableStateFlow<VnSnapshot?>(null)
    val snapshot: StateFlow<VnSnapshot?> = _snapshot

    /** Everyone's connected Steam accounts (who's playing what); only while this reader may watch. */
    private val _accounts = MutableStateFlow<VnAccounts?>(null)
    val accounts: StateFlow<VnAccounts?> = _accounts
    private var accountsJob: Job? = null

    /**
     * What every avatar, member row and status line asks about a person, by user id: see
     * [personsOf]. Worked out once per change, and only replaced when somebody's facts really
     * differ, so a push about a viewer count or a new clip redraws nobody's row.
     */
    private val _persons = MutableStateFlow<Map<String, PersonFacts>>(emptyMap())

    /** The tag each copy in hand came under: sent back, the server says "unchanged" in a few bytes. */
    private var snapshotTag: String? = null
    private var accountsTag: String? = null

    /** The cards of clips shared into chats, by clip id: null for one the server says has gone. */
    private val _cards = MutableStateFlow<Map<String, VnClipCard?>>(emptyMap())
    val cards: StateFlow<Map<String, VnClipCard?>> = _cards

    /** The Clips tab's list. */
    private val _clipList = MutableStateFlow(ClipList())
    internal val clipList: StateFlow<ClipList> = _clipList

    /**
     * The one copy of the clips behind both, and behind the viewer: what is in hand is good for
     * as long as the snapshot's clips version stands, and a vote shows in all three. See
     * [ClipShelf].
     */
    private var shelf: ClipShelf? = null

    /**
     * The boards and everybody's record, which are not in the snapshot: read when somebody looks
     * at a board or a profile, and kept from then on. See [BoardsShelf].
     */
    private val _boards = MutableStateFlow(BoardsHeld())
    internal val boards: StateFlow<BoardsHeld> = _boards
    private var boardShelf: BoardsShelf? = null

    /**
     * Who has a profile banner (VillaNET 3.7.0): read when a profile opens and the snapshot says
     * the copy is behind. See [BannerShelf]. Null while nothing has been read.
     */
    private val _banners = MutableStateFlow<VnBanners?>(null)
    internal val banners: StateFlow<VnBanners?> = _banners
    private var bannerShelf: BannerShelf? = null

    /** The reader's change to a banner is on its way: one at a time, whichever profile it was made from. */
    private val _bannerBusy = MutableStateFlow(false)
    internal val bannerBusy: StateFlow<Boolean> = _bannerBusy

    /**
     * Why the last change to a banner failed, and whose banner it was: said on that profile too,
     * since the sheet covers the notice the failure also gives.
     */
    internal val bannerTrouble = MutableStateFlow<Pair<String, String>?>(null)

    /** The picture on the crop screen, between being picked (or Adjust) and being used or cancelled. See [BannerCropSession]. */
    internal val bannerCropping = MutableStateFlow<BannerCropSession?>(null)
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /**
     * What the app found of the release this server's plugin presents (see [ReleaseVerdict]):
     * null until it has looked, and while the server says nothing of it.
     */
    private val _release = MutableStateFlow<ReleaseVerdict?>(null)
    internal val release: StateFlow<ReleaseVerdict?> = _release

    /** Verdicts already reached, by server, version and what it said: each is looked at once. */
    private val releaseVerdicts = HashMap<String, ReleaseVerdict>()
    /** When a read of `/release` last came to nothing, by the same key: asked again after a while. */
    private val releaseMissed = HashMap<String, Long>()
    private var releaseJob: Job? = null

    /**
     * The server's sentence while it refuses this Matty for not having passed the app check
     * (`app_unverified`, only while it requires the check); null otherwise.
     */
    private val _appRefused = MutableStateFlow<String?>(null)
    internal val appRefused: StateFlow<String?> = _appRefused

    /**
     * What the answers say of the app check, applied in the order they came. A refusal is news:
     * what the app held of its verdict is behind, so the panel shows why instead of what the
     * server refuses, and `GET /access` is read again at once (this app proves itself if that is
     * due). The first gated answer after it takes the refusal away.
     */
    private val appRefusal = AppRefusalHearing(_appRefused) {
        _access.update { held -> held?.copy(app = afterAppRefusal(held.app)) }
        refusedAgain?.request()
    }

    /** Stops [api] hearing the refusals of the pictures loaded from its plugin: see [VillaNetApi.hearMedia]. */
    private var mediaHearing: (() -> Unit)? = null

    /** When this app proves itself, per session and version: see [ProofPlan]. */
    private val proofPlan = ProofPlan(android.os.SystemClock::elapsedRealtime)
    /** The proof on its way, and for which session. */
    private val underWay = ProofUnderWay()
    private var proofJob: Job? = null
    /** Why this phone could not make the proof, if it could not. */
    private val _proofTrouble = MutableStateFlow<String?>(null)

    /** How many VillaNET surfaces are on screen; the safety poll only runs while one is. */
    private val visible = MutableStateFlow(0)
    private var jobs: List<Job> = emptyList()

    /** The clip popped out over the chat (tapped on a card there), if any. */
    val poppedClip = MutableStateFlow<String?>(null)

    /** One shared player so a stream keeps playing between the panel and the mini player. */
    var player: WhepPlayer? = null; private set
    val playingHandle = MutableStateFlow<String?>(null)

    /**
     * The app's one voice session (voice channels, from Matty Plugin 4.3.0): joined, left and
     * followed by the screens; it leaves by itself when this server is left. See [VoiceSession].
     */
    val voice: VoiceSession by lazy { VoiceSession(c, this) }

    /** The voice channel whose sheet is open (from the channel list or the panel), drawn by [Overlay]; null for none. */
    val voiceSheet = MutableStateFlow<Int?>(null)
    /**
     * Whether the voice sheet joins muted: the person's last choice, held here rather than by the
     * sheet, whose window Material builds afresh when the screen's density changes.
     */
    val voiceJoinMuted = MutableStateFlow(false)
    /** The channel list's "Voice Channels" category, folded. */
    val voiceFolded = MutableStateFlow(false)

    // Everything below is touched on the main thread only (the repository's scope, the UI).

    /** Rounds of reads, one at a time and never two starting within [RESYNC_GAP_MS]: see [resync]. */
    private var rounds: SingleFlight? = null

    /** The socket was away at some point since the last round: pushes may have been missed. */
    private var missed = false

    /** The newest push that arrived while Matty was minimised with nothing playing, not yet decoded. */
    private var heldPush: HeldPush? = null
    private class HeldPush(val payload: String, val tag: String?, val place: Long)

    /** An accounts event arrived while Matty was minimised: read them on coming back. */
    private var accountsStale = false
    /** A read of the accounts failed without a refusal, and one more round was asked for it. */
    private var accountsRetryAsked = false
    private var newerNoted = false

    /** The order pushed and read snapshots are shown in: see [ArrivalOrder]. */
    private var order = ArrivalOrder()

    /** `GET /access` said "unknown": asked again in a moment, as the web panel does. */
    private var unknownAgain: OnceLater? = null

    /** A request refused `app_unverified`: `GET /access` read again, at once, never twice within [REFUSED_ACCESS_GAP_MS]. */
    private var refusedAgain: SingleFlight? = null

    /** A proof of this app due later ([ProofPlan.dueIn]): `GET /access` read again then, one at a time. */
    private var proofs: ProofScheduler? = null

    private fun derive() {
        _persons.value = personsOf(_snapshot.value, _accounts.value, _access.value?.canWatch == true, shownName)
    }

    private fun show(s: VnSnapshot?, tag: String?) {
        snapshotTag = if (s == null) null else tag
        _snapshot.value = s
        // What's in hand of the clips is good until this moves. (The count too: a server with
        // no version to give still says when a clip came or went.)
        s?.clips?.let { shelf?.follow("${it.version}|${it.count}", it.count, it.newest) }
        // And of the boards, if a copy is in hand, until the snapshot's word for them moves.
        boardShelf?.follow(s?.boards?.version)
        bannerShelf?.follow(s?.banners?.version)
        derive()
    }

    private fun showAccounts(a: VnAccounts?, tag: String?) {
        accountsTag = if (a == null) null else tag
        _accounts.value = a
        derive()
    }

    /** Nothing of the server just left (or just found too old) stays behind to be shown. */
    private fun forget() {
        accountsJob?.cancel()
        unknownAgain?.cancel()
        proofs?.cancel()
        releaseJob?.cancel()
        _release.value = null
        _appRefused.value = null
        _proofTrouble.value = null
        proofJob?.cancel()
        proofJob = null
        underWay.drop()
        shelf?.clear()
        boardShelf?.clear()
        bannerShelf?.clear()
        bannerTrouble.value = null
        bannerCropping.value = null
        heldPush = null
        accountsStale = false
        accountsRetryAsked = false
        _access.value = null
        _cards.value = emptyMap()
        _clipList.value = ClipList()
        _error.value = null
        showAccounts(null, null)
        show(null, null)
    }

    override fun onAttach(repo: ChatRepository) {
        this.repo = repo
        // A refusal that says "locked" means what this app holds about the reader is behind: ask.
        // (Not while a round is already asking: its own reads are refused the same way.)
        val a = VillaNetApi(repo.client, name = { shownName }, onAppCheck = { refused -> appCheckHeard(this, refused) }) {
            repo.scope.launch { if (rounds?.running != true) resync() }
        }
        api = a
        mediaHearing?.invoke()
        mediaHearing = a.hearMedia()
        player?.release()
        // A stream that has gone quiet: what the server says of it now, asked afresh (minimised,
        // a push may have been missed). A paused one ("Back soon") is waited for, as on the web.
        val p = WhepPlayer(c.app, a) { h ->
            val answered = refreshStreams()
            val stream = _snapshot.value?.streams?.firstOrNull { it.handle == h }
            when {
                !answered -> OnAir.Unknown
                stream == null -> OnAir.Gone
                stream.paused -> OnAir.Paused
                else -> OnAir.Live
            }
        }
        // Nobody sees a picture while Matty is minimised: see onForeground.
        p.pictureWanted = repo.appInForeground
        player = p
        shelf?.clear()
        shelf = ClipShelf(
            scope = repo.scope, clock = android.os.SystemClock::elapsedRealtime,
            fetchList = { a.clips() }, fetchCards = { ids -> a.clipCards(ids) },
            cards = _cards, list = _clipList, visible = { repo.appInForeground },
        )
        boardShelf?.close()
        boardShelf = BoardsShelf(
            scope = repo.scope, clock = android.os.SystemClock::elapsedRealtime,
            fetch = { tag -> a.boards(tag) }, held = _boards, visible = { repo.appInForeground },
        )
        bannerShelf?.clear()
        bannerShelf = BannerShelf(scope = repo.scope, fetch = { tag -> a.banners(tag) }, held = _banners, allowed = { api === a && bannersOn() })
        rounds?.cancel()
        rounds = SingleFlight(repo.scope, RESYNC_GAP_MS, android.os.SystemClock::elapsedRealtime) { missed = false; round() }
        unknownAgain?.cancel()
        unknownAgain = OnceLater(repo.scope, UNKNOWN_AGAIN_MS) { if (api === a) readAccess(a) }
        refusedAgain?.cancel()
        refusedAgain = SingleFlight(repo.scope, REFUSED_ACCESS_GAP_MS, android.os.SystemClock::elapsedRealtime) {
            if (api === a) readAccess(a)
            // Still refused, and no proof of this app on its way to change that: the stream stops,
            // as the server refuses to start it. If the server holds this Matty verified, the
            // refusal came before that verdict, and a gated read decides.
            if (api === a) when (afterRefusedAccess(_appRefused.value, underWay.run.value != null, _access.value?.app)) {
                RefusedNext.Stop -> stop()
                RefusedNext.AskGated -> refreshStreams()
                RefusedNext.Nothing -> {}
            }
        }
        proofs?.cancel()
        // A read that fails sets nothing more ([took] is where it is set): no loop on a server
        // that does not answer.
        proofs = ProofScheduler(proofPlan, Later(repo.scope) { if (api === a) readAccess(a) })
        order = ArrivalOrder()
        missed = false
        newerNoted = false
        _outdated.value = null
        forget()
        jobs.forEach { it.cancel() }
        jobs = listOf(
            repo.scope.launch {
                // A safety poll while VillaNET is on screen; the socket does the rest, and the
                // answer is nearly always "unchanged". It backs off (to ten minutes) while the
                // plugin isn't answering, so a server with VillaNET stopped doesn't get a steady
                // trickle of failed requests.
                var wait = POLL_MS
                while (isActive) {
                    delay(wait)
                    if (visible.value > 0 && repo.appInForeground && _outdated.value == null) {
                        // Refused for the app check is an answer: the poll is how a change of the
                        // server's rules is heard while the panel shows the refusal.
                        wait = if (refreshStreams() || _appRefused.value != null) POLL_MS else (wait * 2).coerceAtMost(POLL_MAX_MS)
                    }
                }
            },
            // A fresh connection has replayed nothing: read everything again. One that only
            // dropped and resumed is noted, and made up for the next time VillaNET is looked at.
            repo.scope.launch { repo.socket.reconnected.collect { resync() } },
            repo.scope.launch { repo.socket.state.collect { if (it != SocketState.Connected) missed = true } },
            // The server's own list says which VillaNET it runs. When that changes under a
            // running app (an admin updated the plugin), whether the two are a pair is asked again.
            repo.scope.launch { repo.webappPlugins.map { listedVersion() }.distinctUntilChanged().drop(1).collect { resync() } },
            // Running again, whatever its version: put back over itself, it may be another
            // release (or the app check another) though the list reads the same. What was found
            // of its release is let go, so the release is verified again even when `/access`
            // says the same of it.
            repo.scope.launch {
                repo.pluginsEnabled.filter { it == VILLANET_ID }.collect {
                    api?.base?.let { b -> forgetReleases(releaseVerdicts, b); forgetReleases(releaseMissed, b) }
                    releaseJob?.cancel()
                    resync()
                }
            },
            // While a stream plays, on screen or minimised, access is asked again at least once
            // a minute: a lock or an unlink then stops it within the minute, even if the notice
            // was missed. See [askWhilePlaying].
            repo.scope.launch {
                // Playing, not merely chosen: a stream that has failed or ended asks nothing more.
                val playing = combine(playingHandle, p.state) { handle, state -> handle.takeIf { state == WhepState.Connecting || state == WhepState.Playing } }
                askWhilePlaying(playing.distinctUntilChanged(), ACCESS_CHECK_MS) { api?.let { readAccess(it) } }
            },
        )
        resync()
    }

    override fun onDetach() {
        // Voice first, while the plugin's client is still in hand to say so to the server.
        voice.detached()
        poppedClip.value = null
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        rounds?.cancel()
        rounds = null
        unknownAgain?.cancel()
        unknownAgain = null
        refusedAgain?.cancel()
        refusedAgain = null
        proofs?.cancel()
        proofs = null
        mediaHearing?.invoke()
        mediaHearing = null
        StreamService.stop()
        player?.release()
        player = null
        playingHandle.value = null
        api = null
        _outdated.value = null
        forget()
        shelf = null
        boardShelf?.close()
        boardShelf = null
        bannerShelf = null
        repo = null
    }

    /**
     * Catches up with the server in one round: the reader's access, then the snapshot and (for a
     * reader who may watch) everyone's accounts, each sent with the tag of the copy in hand, so
     * that "nothing has changed" is a few bytes back. One round at a time, and never two starting
     * within [RESYNC_GAP_MS]: see [SingleFlight].
     */
    fun resync() { rounds?.request() }

    /** [askAgain], and wait for the round: for a pull to refresh, which shows that it's working. */
    suspend fun resyncAndWait() {
        askAgain()
        rounds?.join()
    }

    /**
     * [resync] when there is a reason to think the copy in hand is behind: the socket dropped
     * since, or it's been a while. Coming back to Matty or opening the panel with the socket up
     * the whole time needs nothing read: every change was pushed.
     */
    private fun resyncIfBehind() {
        val last = rounds?.lastStart
        if (missed || _access.value == null || last == null || android.os.SystemClock.elapsedRealtime() - last > FRESH_MS) resync()
    }

    /** VillaNET's version as the server's own plugin list gives it; "" when it doesn't say. */
    private fun listedVersion(): String = repo?.webappPlugins?.value?.firstOrNull { it.id == VILLANET_ID }?.version.orEmpty()

    private suspend fun round() {
        val a = api ?: return
        // A VillaNET from before this app's contract is known by its version, before anything
        // is asked of it: it is never sent requests it would only refuse.
        val listed = listedVersion()
        if (pairState(listed, null) == PairState.TooOld) return fallBehind(listed)
        // Who is asking comes first: what else may be asked depends on the answer.
        readAccess(a)
        if (api !== a || _outdated.value != null) return
        coroutineScope {
            launch { refreshStreams() }
            launch { refreshAccounts(); accountsJob?.join() }
        }
    }

    /**
     * Asks who the reader is to VillaNET, and which VillaNET that is. Also the once-a-minute
     * check while a stream plays.
     */
    private suspend fun readAccess(a: VillaNetApi) {
        try {
            val now = a.access()
            if (api !== a) return
            val pair = pairState(listedVersion(), now.contract)
            if (pair == PairState.TooOld) return fallBehind(now.version.ifEmpty { listedVersion() })
            _outdated.value = null
            if (pair == PairState.Newer && !newerNoted) {
                newerNoted = true
                repo?.emitNotice("This server runs Matty Plugin ${now.version}, which is newer than this app knows. Update Matty to get everything it offers.")
            }
            took(now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VillaNetException) {
            if (api !== a) return
            when (accessRefusal(e, listedVersion())) {
                AccessRefusal.SignedOut -> { stop(); repo?.client?.onUnauthorized?.invoke() }
                AccessRefusal.TooOld -> fallBehind("")
                AccessRefusal.Stands -> {}
            }
        } catch (e: Exception) {
            // Offline, or the plugin not answering: what was known stands.
        }
    }

    /** The reader's access as the server just gave it: a read, or the answer to a link or an unlink. */
    private fun took(now: VnAccess) {
        val before = _access.value
        // "unknown" means the server could not tell just now: what was known stands, and a
        // playing stream plays on until the next answer, which is asked for in a moment.
        if (now.access == "unknown" && before != null) { unknownAgain?.request(); return }
        unknownAgain?.cancel()
        // A link's answer doesn't say which plugin it is, or its name; that was learnt before.
        val next = now.copy(
            contract = if (now.contract == 0) before?.contract ?: 0 else now.contract,
            version = now.version.ifEmpty { before?.version.orEmpty() },
            name = now.name.ifBlank { before?.name.orEmpty() },
            release = now.release ?: before?.release,
            app = now.app ?: before?.app,
        )
        _access.value = next
        // A VillaNET from before banners (an admin rolled the plugin back) is asked nothing of them.
        if (!bannersOffered(next.version)) {
            bannerShelf?.clear()
            bannerCropping.value = null
        }
        checkRelease(next)
        proveIfDue(next)
        // Locked out (or VillaNET switched off): the stream stops, as the web panel's does.
        if (next.access == "locked" || !next.configured) stop()
        // A locked reader is shown nothing about anybody's Steam, so whatever was known goes
        // the moment access does. The clips too, which are for readers who may watch. (The
        // snapshot stays in hand, and what it said of the clips is still where they start.)
        if (!next.canWatch) { accountsJob?.cancel(); showAccounts(null, null); shelf?.clear(keepFollowing = true) } else derive()
    }

    /**
     * The release this server's plugin presents, verified by the app for itself: once per server,
     * version and what the server says of it, off the main thread. An unsigned release, or one
     * that does not verify, works all the same; the app only says so.
     */
    private fun checkRelease(now: VnAccess) {
        val r = repo ?: return
        val a = api ?: return
        val word = now.release
        if (word == null || now.version.isEmpty()) { _release.value = null; return }
        val version = now.version
        val key = releaseKey(a.base, version, word.state)
        releaseVerdicts[key]?.let { _release.value = it; return }
        if (releaseJob?.isActive == true) return
        val missedAt = releaseMissed[key]
        if (missedAt != null && android.os.SystemClock.elapsedRealtime() - missedAt < RELEASE_AGAIN_MS) return
        releaseJob = r.scope.launch {
            val answer = runCatchingCancellable { a.release() }.getOrNull()
            if (api !== a) return@launch
            if (answer == null) { releaseMissed[key] = android.os.SystemClock.elapsedRealtime(); return@launch }
            val verdict = withContext(Dispatchers.Default) { releaseVerdictOf(answer, version) { ceo.villa.matty.verify.ReleasePins.official() } }
            releaseVerdicts[key] = verdict
            if (api === a) _release.value = verdict
        }
    }

    /**
     * Proves this app to the server when its check is on and this session has no verdict for this
     * version ([ProofPlan]): in the background, never on the main thread, and quietly; the server's
     * verdict is taken as it comes, and with it the reader's access is asked again. A proof due
     * later (half a minute after the last, say, or a while after a try that came to nothing) has
     * `GET /access` read again then ([proofs]), which proves this app if it is still due.
     */
    private fun proveIfDue(now: VnAccess) {
        val r = repo ?: return
        val a = api ?: return
        val proofs = proofs ?: return
        val versionCode = ceo.villa.matty.BuildConfig.VERSION_CODE.toLong()
        val session = proofSession(a, r)
        if (!proofs.dueNow(session, versionCode, now.app, proving = underWay.run.value != null)) return
        val userId = r.myId
        if (userId.isEmpty()) return
        val run = underWay.begin(session) ?: return
        proofJob = r.scope.launch {
            // Recorded in the plan as it ends ([attempt]): a proof cancelled by a server switch is no try.
            val answered = try {
                proofPlan.attempt(session, versionCode, cannot = { if (api === a) _proofTrouble.value = it }) {
                    val origin = appCheckOrigin(r.client.origin)
                    val encoder = java.util.Base64.getEncoder()
                    val answer = proveOnce({ a.appChallenge().challenge }) { challenge ->
                        val chain = withContext(Dispatchers.IO) { ceo.villa.matty.verify.attestedChain(c.app, appCheckChallenge(origin, userId, challenge)) }
                        a.appAttest(VnAttestRequest(challenge, origin, chain.map { encoder.encodeToString(it) }, ownClaims(versionCode)))
                    }
                    if (api === a) {
                        _proofTrouble.value = null
                        _access.update { held ->
                            held?.copy(app = VnAppWord(held.app?.check.orEmpty(), answer.state, answer.reason, answer.development, answer.versionCode))
                        }
                        appRefusal.proved(answer.state)
                        // What push owes this server (refused for the check until now) goes now.
                        if (answer.state == APP_VERIFIED) c.push.payOwed(r.server.id)
                    }
                }
            } finally {
                underWay.end(run)
            }
            // What the server now holds for this session, and, if it requires the check, what it
            // refused until now. A try that came to nothing is made again in a while, by itself.
            if (api === a && proofs.ended(answered, session, versionCode, _access.value?.app)) resync()
        }
    }

    /**
     * The person's own "Try again" on the refusal of a server that requires the app check: what
     * was tried in this session is forgotten (a try that came to nothing, a verdict given before
     * the attestation was read, a phone that could not), so the round it starts proves this app
     * once more. So does an "unverified" verdict the server holds for this version (an admin may
     * have since accepted the certificate it failed on), once per tap; a verified one stands.
     */
    fun tryAppCheckAgain() {
        val r = repo
        val a = api
        if (r != null && a != null) proofPlan.forget(proofSession(a, r))
        resync()
    }

    /**
     * Refresh, and Try again: while the server refuses this Matty for the app check, or holds no
     * verdict for it while its check is on ([refreshProves]), [tryAppCheckAgain]; otherwise [resync].
     */
    fun askAgain() {
        if (refreshProves(_appRefused.value, _access.value?.app)) tryAppCheckAgain() else resync()
    }

    /**
     * What an answer of [a]'s said of the app check, on whichever thread it came: [refused] is the
     * server's sentence for a request it refused `app_unverified`, null for an answer to a route the
     * check gates. Applied on the main thread, in the order heard: see [appRefusal].
     */
    private fun appCheckHeard(a: VillaNetApi, refused: String?) {
        val r = repo ?: return
        appRefusal.heard(refused) { apply -> r.scope.launch { if (api === a) apply() } }
    }

    /** Whether a proof is on its way for this server. */
    @Composable
    internal fun proving(): Boolean = underWay.run.collectAsState().value != null

    /** This session at this server: the server's address and a digest of the session's token. */
    private fun proofSession(a: VillaNetApi, r: ChatRepository): String =
        a.base + "|" + ceo.villa.matty.verify.sha256Colons(r.client.token.orEmpty().toByteArray())

    /** What the app reads of itself, sent beside the proof as its own word (the server never decides by it). */
    private fun ownClaims(versionCode: Long): VnAppClaims {
        val record = ceo.villa.matty.verify.SelfCheck.record
        return VnAppClaims(
            packageName = c.app.packageName,
            versionCode = versionCode,
            currentSigner = record?.current?.singleOrNull().orEmpty(),
            history = record?.history.orEmpty(),
            hybrid = record != null && record.currentIsMlDsa && record.history.size >= 2,
        )
    }

    /** What the app found of the plugin's release and of itself here, where the plugin is listed and in the panel's menu. */
    @Composable
    override fun checks(): List<ceo.villa.matty.plugins.PluginCheck> {
        val found by release.collectAsState()
        val a by access.collectAsState()
        val proving = proving()
        val trouble by _proofTrouble.collectAsState()
        return listOfNotNull(
            found?.let { ceo.villa.matty.plugins.PluginCheck(releaseWords(it), warning = it !is ReleaseVerdict.Signed) },
            appCheckWords(a?.app, trouble, proving)?.let { (text, warning) -> ceo.villa.matty.plugins.PluginCheck(text, warning) },
        )
    }

    private fun fallBehind(version: String) {
        stop()
        forget()
        _outdated.value = version
    }

    /**
     * Who's playing what on Steam. Only for a reader who may watch: see [took]. With the tag of
     * the answer in hand, nearly always nothing has changed, and the server says just that.
     */
    fun refreshAccounts() {
        val r = repo ?: return
        val a = api ?: return
        if (_access.value?.canWatch != true) { accountsJob?.cancel(); showAccounts(null, null); return }
        // The newest read wins: a burst of events (or a refresh racing one) is one answer.
        accountsJob?.cancel()
        accountsStale = false
        // The one more try a failed read asked for: if this fails too, it asks for no other.
        val retry = accountsRetryAsked
        accountsRetryAsked = false
        accountsJob = r.scope.launch {
            val held = if (_accounts.value != null) accountsTag else null
            runCatchingCancellable { a.accounts(held) }
                .onSuccess { fresh -> if (api === a && fresh != null) showAccounts(fresh.value, fresh.tag) }
                .onFailure { e ->
                    if (api !== a) return@onFailure
                    // Refused (locked since): nothing to show.
                    if (!worthAnotherTry(e)) showAccounts(null, null)
                    // Offline or a hiccup: what was known stands, and it is asked once more, in
                    // the next round, so that one failed read doesn't leave a "Streaming" that
                    // has ended standing until some other event.
                    else if (!retry) { accountsRetryAsked = true; resync() }
                }
        }
    }

    /**
     * Reads the snapshot again, saying which one is in hand: after a reconnect, on the safety
     * poll, nothing has usually changed, and the server says so without sending it. False when
     * the plugin didn't answer.
     */
    suspend fun refreshStreams(): Boolean {
        val a = api ?: return false
        val held = if (_snapshot.value != null) snapshotTag else null
        val place = order.reading()
        return runCatchingCancellable { a.streams(held) }
            .onSuccess { fresh ->
                if (api !== a) return@onSuccess
                // Never backwards: a push of a later poll that landed while this was in flight is
                // newer, and so is one of the same poll that was shown after this started.
                val current = _snapshot.value?.fetchedAt ?: 0
                if (fresh != null && (fresh.value.fetchedAt > current || (fresh.value.fetchedAt == current && order.mayShowRead(place)))) {
                    order.showed(place)
                    show(fresh.value, fresh.tag)
                }
                _error.value = null
                _appRefused.value = null
                _snapshot.value?.let { stopIfEnded(it) }
            }
            .onFailure {
                if (api === a) {
                    _error.value = it.message
                    if (it is VillaNetException && it.code == VillaNetApi.APP_UNVERIFIED) _appRefused.value = it.serverMessage ?: it.message
                }
            }
            .isSuccess
    }

    override fun onEvent(name: String, event: WsEvent) {
        if (_outdated.value != null) return
        when (name) {
            "streams" -> event.data.str("payload")?.let { payload ->
                // The tag rides with the push, quoted as an entity tag is.
                val tag = event.data.str("tag")?.takeIf { it.isNotEmpty() }?.let { "\"$it\"" }
                val place = order.pushed()
                // Minimised with nothing playing and nobody in voice, nobody is looking and nothing
                // depends on it: the newest push is kept as it came and decoded when Matty is next
                // looked at. (In voice, the call's notification names the channel from it.)
                if (repo?.appInForeground == false && playingHandle.value == null && !voice.active) heldPush = HeldPush(payload, tag, place)
                else decode(payload, tag, place)
            }
            // What the server holds about the reader may have changed: their access, or one of
            // their connected accounts; or, sent to everybody, the server's name for the plugin.
            // What it carries is never what unlocks anybody: the answer is asked for.
            "me" -> resync()
            // Somebody's Steam changed (their game, their connection): read everyone's again,
            // now if anybody is looking, otherwise on coming back.
            "accounts" -> if (repo?.appInForeground == false) accountsStale = true else refreshAccounts()
        }
    }

    /**
     * A pushed snapshot: decoded off the main thread (it's the whole thing, every time), shown on
     * it, in the order the pushes arrived ([place]): never one after a push that came later.
     */
    private fun decode(payload: String, tag: String?, place: Long) {
        val a = api
        repo?.scope?.launch(Dispatchers.Default) {
            val pushed = runCatching { MattermostJson.decodeFromString(VnSnapshot.serializer(), payload) }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main.immediate) {
                if (api !== a || _outdated.value != null || !order.mayShowPush(place) || pushed.fetchedAt < (_snapshot.value?.fetchedAt ?: 0)) return@withContext
                order.showed(place)
                show(pushed, tag)
                stopIfEnded(pushed)
            }
        }
    }

    override fun onForeground(foreground: Boolean) {
        // Minimised, a stream plays on for its sound: its picture, which nobody can see, is no
        // longer decoded after a moment, and is back when Matty is.
        player?.pictureWanted = foreground
        if (foreground) {
            heldPush?.let { p -> heldPush = null; decode(p.payload, p.tag, p.place) }
            voice.foreground()
            if (accountsStale) refreshAccounts()
            shelf?.lookedAt()
            // The boards changed while nobody was looking, and there is a copy to bring up to
            // date; a copy that is merely old waits for somebody to open a board or a profile.
            if (_outdated.value == null) boardShelf?.cameBack()
            resyncIfBehind()
        }
        // Minimised (or the screen off) with a stream on: it keeps playing, held up by
        // StreamService and its notification. Only if Android wouldn't let that start does
        // leaving the app stop the stream, as closing the panel does on the web.
        else if (playingHandle.value != null && !StreamService.running) stop()
        // And one that ended meanwhile is let go now, whatever was on screen.
        else _snapshot.value?.let { stopIfEnded(it) }
    }

    fun setVisible(on: Boolean) {
        visible.update { (it + if (on) 1 else -1).coerceAtLeast(0) }
        // The panel closing in the very frame its stream ended: nothing else would stop it now.
        if (on) resyncIfBehind() else if (visible.value == 0) _snapshot.value?.let { stopIfEnded(it) }
        // The panel has a Boards tab: by the time it is chosen, the boards are in hand.
        if (on) boardsLookedAt()
    }

    /**
     * A profile or the panel has opened: the boards are asked about again if the copy is a minute
     * old or was never read. Nothing while the server's VillaNET is too old for this app, which
     * is never asked anything.
     */
    fun boardsLookedAt() { if (_outdated.value == null) boardShelf?.lookedAt() }

    /**
     * A profile is open: [boardsLookedAt], and while it stays open with nothing to show because
     * that read failed, asked again now and then. Returns only when cancelled (the profile closing).
     */
    suspend fun boardsProfileOpen() { boardShelf?.profileOpen { _outdated.value == null } }

    /** The Boards tab is on screen, or has left it: while it is, the boards are read each minute. */
    fun boardsShown(on: Boolean) { boardShelf?.shown(on) }

    fun reloadBoards() { boardShelf?.reload() }

    /** Whether this server's VillaNET keeps banners: in step with this app, and 3.7.0 or newer. */
    internal fun bannersOn(): Boolean = _outdated.value == null && bannersOffered(_access.value?.version)

    /** A profile is open: who has a banner is read if the copy is behind. Returns only when cancelled. */
    suspend fun bannersProfileOpen() { bannerShelf?.profileOpen() }

    /** Whether the part of a GIF banner that shows can be kept: VillaNET 3.8.0 or newer. */
    internal fun bannerCropOn(): Boolean = bannersOn() && bannerCropOffered(_access.value?.version)

    /**
     * A picture picked for the reader's banner goes to the crop screen first: a still always (it
     * is cut on the phone, which needs nothing of the server), decoded for a screen whose longer
     * side is [screenPixels]; a GIF when VillaNET keeps its crop. A GIF otherwise (or one with no
     * part VillaNET would keep) is sent as it is, as before there was a choice.
     */
    internal fun pickedBanner(cr: android.content.ContentResolver, uri: android.net.Uri, screenPixels: Int) =
        bannerAction(BANNER_SET_FAILED, repo?.myId.orEmpty()) { a, r ->
            fun refused(text: String) {
                bannerTrouble.value = r.myId to text
                r.emitNotice(text)
            }
            when (val gif = withContext(Dispatchers.IO) { readBannerGif(cr, uri) }) {
                is PreparedBanner.Refused -> refused(gif.text)
                is PreparedBanner.Ready -> {
                    val size = gifScreenSize(gif.bytes)
                    if (size != null && bannerCropOn() && gifCropPossible(size.first, size.second)) {
                        if (api === a) bannerCropping.value = BannerCropSession(r.myId, null, null, gif.bytes, size.first, size.second)
                    } else {
                        val set = a.setBanner(gif.bytes, gif.type)
                        if (api === a) bannerShelf?.took(r.myId, set.takeIf { it.v.isNotEmpty() })
                    }
                }
                null -> {
                    val preview = withContext(Dispatchers.IO) { decodeBannerPreview(cr, uri, screenPixels) }
                    if (preview == null) refused(BANNER_UNREADABLE)
                    else if (api === a) bannerCropping.value = BannerCropSession(r.myId, uri, preview, null, preview.width, preview.height)
                }
            }
        }

    /**
     * Adjust: the reader's GIF banner as it is stored, on the crop screen from the part showing
     * now. Only the crop is sent back.
     */
    internal fun adjustBanner() = bannerAction(BANNER_ADJUST_FAILED, repo?.myId.orEmpty()) { a, r ->
        val mine = _banners.value?.people?.get(r.myId)?.takeIf { it.v.isNotEmpty() && it.type == "image/gif" } ?: return@bannerAction
        if (!bannerCropOn()) return@bannerAction
        val bytes = a.bannerBytes(r.myId, mine.v)
        val size = gifScreenSize(bytes)?.takeIf { (w, h) -> gifCropPossible(w, h) }
        if (size == null) {
            bannerTrouble.value = r.myId to BANNER_NOT_ADJUSTABLE
            r.emitNotice(BANNER_NOT_ADJUSTABLE)
            return@bannerAction
        }
        if (api === a) bannerCropping.value = BannerCropSession(r.myId, null, null, bytes, size.first, size.second, adjusting = true, start = bannerCropOf(mine.crop), v = mine.v)
    }

    /** Cancel or Back on the crop screen: nothing is sent, and nothing changes. */
    internal fun cancelBannerCrop(s: BannerCropSession) {
        if (bannerCropping.value === s) bannerCropping.value = null
    }

    /**
     * Use on the crop screen: a still is cut to the part chosen and sent; a GIF is sent as it is
     * with that part (or, adjusting, only the part). The screen stays until the server has
     * taken it, and on a failure says why with the picture still there; adjusting a banner that
     * has changed or gone since, it closes instead (see [bannerUseRefusalCloses]), the sentence
     * said on the profile, and the list is read again.
     */
    internal fun useBannerCrop(s: BannerCropSession) {
        val chosen = s.rect
        // Adjusting and nothing moved: there is nothing to send.
        if (s.adjusting && chosen == null) return cancelBannerCrop(s)
        val cr = c.app.contentResolver
        val failed: (String, Exception) -> Unit = { text, e ->
            if (bannerUseRefusalCloses((e as? VillaNetException)?.code.orEmpty(), s.adjusting)) {
                cancelBannerCrop(s)
                bannerShelf?.behind()
            } else {
                s.trouble = text
            }
        }
        bannerAction(if (s.adjusting) BANNER_ADJUST_FAILED else BANNER_SET_FAILED, s.userId, onFailure = failed) { a, r ->
            s.trouble = null
            val crop = chosen?.let { wireCrop(it) }
            if (s.gif != null && crop != null && !cropAccepted(crop, s.width, s.height)) {
                s.trouble = BANNER_PART_REFUSED
                return@bannerAction
            }
            val set = when {
                s.gif == null -> when (val picture = withContext(Dispatchers.IO) { prepareBanner(cr, s.uri!!, chosen) }) {
                    is PreparedBanner.Refused -> {
                        s.trouble = picture.text
                        bannerTrouble.value = r.myId to picture.text
                        return@bannerAction
                    }
                    is PreparedBanner.Ready -> a.setBanner(picture.bytes, picture.type)
                }
                s.adjusting -> a.setBannerCrop(crop, s.v)
                // A VillaNET rolled back from under the screen keeps no crop: the GIF goes as before.
                else -> a.setBanner(s.gif, "image/gif", crop?.takeIf { bannerCropOn() })
            }
            cancelBannerCrop(s)
            if (api === a) bannerShelf?.took(r.myId, set.takeIf { it.v.isNotEmpty() })
        }
    }

    /** Takes the reader's own banner away. */
    internal fun removeMyBanner() = bannerAction(BANNER_REMOVE_FAILED, repo?.myId.orEmpty()) { a, r ->
        a.removeMyBanner()
        if (api === a) bannerShelf?.took(r.myId, null)
    }

    /** A system admin taking [userId]'s banner away. */
    internal fun removeBanner(userId: String) = bannerAction(BANNER_REMOVE_FAILED, userId) { a, _ ->
        a.removeBanner(userId)
        if (api === a) bannerShelf?.took(userId, null)
    }

    /**
     * One change to a banner, through the session's scope ([ChatRepository.launchAction]), so
     * that closing the sheet doesn't cut it short and a failure is told with the server's sentence.
     */
    private fun bannerAction(failure: String, whose: String, onFailure: (String, Exception) -> Unit = { _, _ -> }, work: suspend (VillaNetApi, ChatRepository) -> Unit) {
        val r = repo ?: return
        val a = api?.takeIf { bannersOn() } ?: return
        if (_bannerBusy.value) return
        _bannerBusy.value = true
        bannerTrouble.value = null
        r.launchAction(failure) {
            try {
                work(a, r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (api === a) r.failureText(failure, e).let { text ->
                    bannerTrouble.value = whose to text
                    onFailure(text, e)
                }
                throw e
            } finally {
                _bannerBusy.value = false
            }
        }
    }

    fun play(handle: String) {
        // Beside a call, heard on the speaker: the microphone is muted (the echo canceller can't hear the stream).
        voice.streamStarting()
        player?.play(handle)
        playingHandle.value = handle
        // So it can carry on with Matty minimised. (It takes itself down when the stream stops.)
        StreamService.start(c.app)
    }

    fun stop() {
        val was = playingHandle.value != null
        player?.stop()
        playingHandle.value = null
        StreamService.stop()
        if (was) voice.streamStopped()
    }

    /** Null on success, otherwise what to tell the user (offline, server refused…). */
    suspend fun link(token: String): String? {
        val a = api ?: return VillaNetApi.notAnswering(shownName)
        val clean = VillaNetApi.cleanToken(token)
        if (clean.isEmpty()) return "Paste your bearer token first."
        return changeLink(a) { a.link(clean) }
    }

    suspend fun unlink(): String? {
        val a = api ?: return VillaNetApi.notAnswering(shownName)
        return changeLink(a) { a.unlink().also { if (api === a) stop() } }
    }

    /**
     * A link or an unlink. The answer says what the reader's access is now, so the lock opens (or
     * closes) on the answer itself; the snapshot and the accounts follow from the round the
     * server's own event about the change starts, or this one if that never comes.
     */
    private suspend fun changeLink(a: VillaNetApi, write: suspend () -> VnAccess): String? = try {
        val answer = write()
        // Still the server it was asked of: an answer that arrives after a switch to another
        // server says nothing about the reader there.
        if (api === a) {
            took(answer)
            resync()
        }
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: VillaNetException) {
        // The server's own sentence: it words every refusal, and no copy of them is kept here.
        VillaNetApi.lockCopy(e.serverMessage, shownName)
    } catch (e: Exception) {
        VillaNetApi.lockCopy(null, shownName)
    }

    /**
     * A shared clip's card is on screen ([on]) or has left it. While it's there its clip is asked
     * about, in batches with the other cards, and again when the clips change: see [ClipShelf].
     * Only for a reader who may watch; nobody else is given cards.
     */
    fun cardShown(clipId: String, on: Boolean) {
        if (on) shelf?.show(clipId) else shelf?.hide(clipId)
    }

    /** The Clips tab is on screen ([on]) or has left it: its list is read when it's behind. */
    fun clipsShown(on: Boolean) { shelf?.showList(on) }

    /** Reads the list of clips again, whatever is in hand. */
    fun reloadClips() { shelf?.reloadList() }

    /**
     * One clip, read fresh for the viewer. What came of asking is told apart: found, gone (only
     * when the server says so), locked, or not answered, which says nothing about the clip.
     */
    internal suspend fun openClip(clipId: String): ClipOpen {
        // A VillaNET too old for this app is asked nothing (the panel says so instead).
        val a = api?.takeIf { _outdated.value == null } ?: return ClipOpen.Failed(VillaNetApi.notAnswering(shownName))
        return runCatchingCancellable { a.clip(clipId) }.fold(
            onSuccess = { clip -> if (api === a) shelf?.took(clip); ClipOpen.Found(clip) },
            onFailure = { e -> clipOpenOf(e).also { if (it is ClipOpen.Gone && api === a) shelf?.gone(clipId) } },
        )
    }

    suspend fun vote(clipId: String, up: Boolean): VnVote {
        val v = (api?.takeIf { _outdated.value == null } ?: throw java.io.IOException("$shownName isn't available")).vote(clipId, up)
        // The same count in the list, the viewer and the card.
        shelf?.voted(clipId, v)
        return v
    }

    /** Matty's composer runs this: a bare Broadcast Box token about to be posted is stopped. */
    override fun checkOutgoing(text: String): String? =
        if (VillaNetApi.looksLikeToken(text)) "That looks like your bearer token. Paste it into the unlock bar in the $shownName panel instead." else null

    override fun panelArgsForLink(path: String): Map<String, String>? = panelArgsOf(path)


    override val panel = PluginPanel(PRODUCT_NAME, Icons.Outlined.LiveTv) { host, args, onClose ->
        // The voice screen is a page of the panel's route: the notification, the bar and the sheet open it.
        if (args[VOICE_ARG] != null) VillaNetColors { VoiceScreen(this, host, onClose) }
        else VillaNetPanel(this, host, args, onClose)
    }

    @Composable
    override fun sidebarSection(host: PluginHost, teamId: String): (androidx.compose.foundation.lazy.LazyListScope.() -> Unit)? =
        voiceSidebarSection(this, host)

    @Composable
    override fun PostContent(post: Post, host: PluginHost) {
        when (post.type) {
            POST_CLIP -> ClipPostCard(this, post, host)
            POST_PLAN -> PlanPostCard(this, post, host)
        }
    }

    @Composable
    override fun notice(): String? {
        val theirs by outdated.collectAsState()
        return theirs?.let { outOfStepWords(it) }
    }

    /** People live right now, as the Live now list counts them: no 24/7 channels, nothing paused. */
    @Composable
    override fun panelBadge(): Int {
        val s by snapshot.collectAsState()
        return s?.streams.orEmpty().count { !it.alwaysOn && !it.paused && it.coveredBy.isEmpty() }
    }

    /**
     * What's asked about a person, everywhere a person is shown. Each caller follows its own
     * entry of the map: a row is only drawn again when that person's facts change.
     */
    @Composable
    override fun person(userId: String): PersonFacts? =
        // Keyed by the person: a place that shows somebody else next (a DM's header, on switching
        // conversation) starts from that person's facts, never a frame of the last one's.
        androidx.compose.runtime.key(userId) {
            val theirs = androidx.compose.runtime.remember { _persons.map { it[userId] }.distinctUntilChanged() }
            val facts by theirs.collectAsState(_persons.value[userId])
            facts
        }

    @Composable
    override fun ProfileExtras(userId: String, host: PluginHost) = VillaNetProfileExtras(this, userId, host)

    @Composable
    override fun ProfileBanner(userId: String, host: PluginHost, modifier: androidx.compose.ui.Modifier) =
        VillaNetProfileBanner(this, userId, host, modifier)

    @Composable
    override fun ProfileOver(userId: String, host: PluginHost) = VillaNetProfileOver(this, userId, host)

    @Composable
    override fun Overlay(host: PluginHost) {
        val clipId by poppedClip.collectAsState()
        val theirs by outdated.collectAsState()
        // Never over a VillaNET too old for this app, which is asked nothing.
        if (theirs == null) clipId?.let { id ->
            androidx.compose.runtime.key(id) {
                // Unlock is in the panel: it holds on to the clip, and opens it once the reader may watch.
                ClipViewer(this, host, id, onUnlock = { poppedClip.value = null; host.openPanel(VILLANET_ID, mapOf("clip" to id)) }) { poppedClip.value = null }
            }
        }
        // Voice channels: the microphone is asked for here, outside every sheet, and the voice
        // sheet is drawn after it (see VoiceSheetHost).
        VoiceMicAsk(voice)
        if (theirs == null) VillaNetColors { VoiceSheetHost(this, host) }
        // Gone with the screen it was opened over (signing in to another server, the activity
        // closing): it mustn't pop back up, playing, when the app's UI comes back.
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { poppedClip.value = null; voiceSheet.value = null } }
    }

    @Composable
    override fun hasMiniBar(): Boolean {
        val playing by playingHandle.collectAsState()
        val panels by visible.collectAsState()
        val snap by snapshot.collectAsState()
        val inVoice by voice.state.collectAsState()
        // Voice first, whatever is open (the bar never shows over the voice screen, a full-screen
        // view); else the stream, whose panel has its own controls. Twitch never plays here.
        return inVoice != VoiceStatus.Idle ||
            (playing != null && panels == 0 && snap?.streams?.any { it.handle == playing && !it.isTwitch } == true)
    }

    @Composable
    override fun MiniBar(host: PluginHost, modifier: androidx.compose.ui.Modifier) {
        val playing by playingHandle.collectAsState()
        val snap by snapshot.collectAsState()
        val inVoice by voice.state.collectAsState()
        val now = snap?.streams?.firstOrNull { it.handle == playing }
        // Keeps drawing the last stream, or the last voice bar, while the bar slides away.
        val last = androidx.compose.runtime.remember { arrayOfNulls<VnStream>(1) }
        val wasVoice = androidx.compose.runtime.remember { booleanArrayOf(false) }
        if (now != null) last[0] = now
        if (inVoice != VoiceStatus.Idle) wasVoice[0] = true else if (now != null) wasVoice[0] = false
        val s = now ?: last[0]
        VillaNetColors {
            if (wasVoice[0] || s == null) VoiceBarPill(this, host, modifier)
            else MiniPlayerPill(this, host, s, openLabel = "Open ${displayName()}", onOpen = { host.openPanel(VILLANET_ID) }, modifier = modifier)
        }
    }

    /**
     * The stream playing ended while nobody was looking at it (the panel closed, or Matty
     * minimised): stop it. An open panel on screen shows the moment itself.
     */
    private fun stopIfEnded(s: VnSnapshot) {
        val h = playingHandle.value ?: return
        val watched = visible.value > 0 && repo?.appInForeground != false
        if (!watched && s.streams.none { it.handle == h }) stop()
    }

    companion object {
        private const val POLL_MS = 30_000L
        private const val POLL_MAX_MS = 600_000L
        private const val ACCESS_CHECK_MS = 60_000L
        /** "unknown" from `GET /access` is asked about again this soon (the web panel's UNKNOWN_AGAIN_MS). */
        private const val UNKNOWN_AGAIN_MS = 5_000L
        /** The least time between two reads of `GET /access` that refusals for the app check ask for. */
        private const val REFUSED_ACCESS_GAP_MS = 5_000L
        /** A read of `/release` that came to nothing is tried again no sooner than this. */
        private const val RELEASE_AGAIN_MS = 5 * 60_000L
        /** Rounds of reads start no closer together than this. */
        private const val RESYNC_GAP_MS = 2_000L
        /** With the socket up throughout, a copy this recent needs no reading again. */
        private const val FRESH_MS = 60_000L
        private const val BANNER_SET_FAILED = "Couldn't change your banner"
        private const val BANNER_REMOVE_FAILED = "Couldn't remove the banner"
        private const val BANNER_ADJUST_FAILED = "Couldn't adjust your banner"
        private const val BANNER_NOT_ADJUSTABLE = "This GIF is too tall or too wide to adjust."
        private const val BANNER_PART_REFUSED = "That part can't be used. Try a larger part."
        const val POST_CLIP = "custom_villanet_clip"
        const val POST_PLAN = "custom_villanet_plan"
    }
}

/**
 * What to say when the server's plugin ([theirs]; "" when unknown) is older than this app works
 * with. About the software, so it says "Matty Plugin" whatever the server calls it.
 */
internal fun outOfStepWords(theirs: String): String =
    (if (theirs.isBlank()) "This server runs a version of Matty Plugin older than this app works with." else "This server runs Matty Plugin $theirs.") +
        " This version of Matty works with Matty Plugin ${VnAccess.SINCE} or newer."

/** What [VillaNetPlugin.readAccess] does with a refusal of `GET /access`. */
internal enum class AccessRefusal { SignedOut, TooOld, Stands }

/** What a refusal [e] of `GET /access` means, with [listed] the version the server's plugin list gives ("" when none). */
internal fun accessRefusal(e: VillaNetException, listed: String): AccessRefusal = when {
    // Mattermost no longer vouches for this session (signed out elsewhere, deactivated,
    // expired). The reader may not watch, and no later answer will say so any better:
    // the stream stops now, and the session is checked (and ended here) the usual way.
    e.status == 401 -> AccessRefusal.SignedOut
    // Only when the server's plugin list didn't say which plugin this is (it normally does, and
    // an old one is then never asked: see round). A plugin from before this contract has no such
    // route, and says so in one of two ways: "unknown endpoint", or, to a reader it has locked,
    // the refusal every route it hasn't classified gets. One that has the route refuses it to
    // nobody signed in. With a version listed, the same refusals are a plugin restarting, and
    // what was known stands.
    (e.status == 404 || e.status == 403) && listed.isBlank() -> AccessRefusal.TooOld
    else -> AccessRefusal.Stands
}

/**
 * What a `/plug/<id>/<path>` link opens the panel with: `/clip/<id>` and `/plan/<id>`, and the
 * panel itself for anything else. The same under the plugin's earlier id ([PREDECESSOR_ID]).
 */
internal fun panelArgsOf(path: String): Map<String, String>? {
    val parts = path.trim('/').split('/')
    return when (parts.firstOrNull()) {
        // A link to an id that can't be a clip's opens the panel and asks about nothing.
        "clip" -> parts.getOrNull(1)?.let { if (isClipId(it)) mapOf("clip" to it) else emptyMap() }
        "plan" -> parts.getOrNull(1)?.let { mapOf("plan" to it) }
        else -> emptyMap()
    }
}

/** The game [userId] is in on Steam ("" when Steam doesn't say which), or null when they aren't in one. */
internal fun steamGameOf(a: VnAccounts?, userId: String): String? {
    if (a == null || !a.steam.on || userId.isEmpty()) return null
    val s = a.people[userId]?.steam ?: return null
    return if (s.inGame) s.game.orEmpty().trim() else null
}
