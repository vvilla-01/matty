package ceo.villa.matty

import ceo.villa.matty.data.api.MattermostClient
import ceo.villa.matty.plugins.villanet.VillaNetApi
import ceo.villa.matty.plugins.villanet.VoiceStateSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Where voice is joined to the screen and to the network, held by the source itself (as
 * PushWiringTest holds push's wiring): what the buttons, the mini bar and the notification show
 * and act on is the core's shown state, a join waits for a state request of an earlier connection,
 * and the line about a newer plugin is for system admins only.
 */
class VoiceWiringTest {
    private val main = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "app/src/main/java/ceo/villa/matty") }.first { it.isDirectory }

    private val session = File(main, "plugins/villanet/VoiceSession.kt").readText()
    private val plugin = File(main, "plugins/villanet/VillaNetPlugin.kt").readText()

    /** The lines of [text] that are code, without the comments. */
    private fun code(text: String): List<String> = text.lines().map { it.trim() }
        .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
        .map { it.substringBefore("//").trim() }
        .filter { it.isNotEmpty() }

    private fun body(text: String, signature: String): String {
        val start = text.indexOf(signature).also { assertTrue("$signature is there", it >= 0) }
        val open = text.indexOf('{', start)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) { '{' -> depth++; '}' -> if (--depth == 0) return text.substring(open, i + 1) }
        }
        error("unbalanced $signature")
    }

    @Test
    fun theSessionShowsAndActsOnTheShownState() {
        val lines = code(session)
        assertTrue(lines.contains("val muted: StateFlow<Boolean> = core.shownMuted"))
        assertTrue(lines.contains("val deafened: StateFlow<Boolean> = core.shownDeafened"))
        val unmute = code(body(session, "internal fun unmuteOnScreen()"))
        assertEquals(listOf("if (core.active && core.shownMuted.value) setMuted(false)"), unmute.filter { "setMuted" in it })
    }

    /**
     * Held muted by the voice server, the own tile and the microphone's icon say muted (the core's
     * seen state), while a tap still acts on the person's own mute; the sentence of a refusal
     * being waited out goes where the screen's notice goes.
     */
    @Test
    fun theOwnTileAndTheMicrophoneShowMutedAsOthersHearIt() {
        val ui = File(main, "plugins/villanet/VoiceUi.kt").readText()
        val rooms = code(body(ui, "private fun rememberVoiceRooms("))
        assertTrue("the own seat is placed as seen", rooms.contains("val muted by plugin.voice.seenMuted.collectAsState()"))
        val screen = code(body(ui, "internal fun VoiceScreen("))
        assertTrue(screen.contains("val seenMuted by voice.seenMuted.collectAsState()"))
        assertTrue(screen.contains("VoiceMember(-1, repo.myId, repo.nameOf(repo.myId).takeIf { it != \"…\" } ?: \"You\", seenMuted, deafened, phone = true, speaking = false, me = true)"))
        assertTrue(screen.contains("VoiceControls(voice, muted, deafened, seenMuted)"))
        assertTrue(screen.contains("voiceNoticeOf(held, serverMuted, suppressed, mic, waitWords)?.let { n ->"))
        val controls = code(body(ui, "private fun VoiceControls("))
        assertEquals(
            listOf("VoiceControlButton(if (seen) Icons.Filled.MicOff else Icons.Filled.Mic, if (muted) \"Unmute\" else \"Mute\", on = seen) { voice.setMuted(!muted) }"),
            controls.filter { "setMuted" in it },
        )
        val bar = code(body(ui, "internal fun VoiceBarPill("))
        assertTrue(bar.contains("val seen by voice.seenMuted.collectAsState()"))
        assertEquals(
            listOf("PillIconButton(if (seen) Icons.Filled.MicOff else Icons.Filled.Mic, if (muted) \"Unmute\" else \"Mute\", tint = if (seen) c.dnd else c.icon) { voice.setMuted(!muted) }"),
            bar.filter { "setMuted" in it },
        )
    }

    /**
     * The network watch names the default network to the core and tells it of a loss (the core
     * acts on that only with no network there); the screen, the bar and the notification show the
     * status with the link's state beside it.
     */
    @Test
    fun theWatchNamesTheNetworkAndTheStatusIsShownWithTheLink() {
        val watch = code(body(session, "private fun watchNetwork(on: Boolean)"))
        assertTrue(watch.contains("val id = network.networkHandle"))
        assertTrue(watch.contains("main.post { core.network(true, id) }"))
        assertTrue(watch.contains("override fun onLost(network: Network) { main.post { core.network(false) } }"))
        val ui = File(main, "plugins/villanet/VoiceUi.kt").readText()
        val screen = code(body(ui, "internal fun VoiceScreen("))
        assertTrue(screen.contains("val shown = voiceShownStatus(status, linkDown)"))
        assertTrue(screen.any { "voiceStatusWords(shown)" in it })
        assertTrue(screen.contains("VoiceHeader(title, subtitle, connected = shown == VoiceStatus.Connected && moving == null, onBack = onClose)"))
        val bar = code(body(ui, "internal fun VoiceBarPill("))
        assertTrue(bar.contains("val now = voiceBarOf(voiceShownStatus(status, linkDown), voiceChannelName(snap?.voice, channel), host.repo.siteName)"))
        val service = File(main, "plugins/villanet/VoiceService.kt").readText()
        assertTrue(code(body(service, "private fun describe(v: VoiceSession)")).contains("val title = when (voiceShownStatus(v.state.value, v.linkDown.value)) {"))
        assertTrue(code(service).any { it.startsWith("combine(combine(voice.state, voice.linkDown, ::Pair)") })
    }

    @Test
    fun aJoinWaitsForAStateRequestOfAnEarlierConnection() {
        val connect = code(body(session, "override fun connect(attempt: Int, join: VoiceJoin)"))
        val settle = connect.indexOfFirst { it == "states.settle()" }
        val join = connect.indexOfFirst { "a.voiceJoin(" in it }
        assertTrue("the join is there", join >= 0)
        assertTrue("settled before it", settle in 0 until join)
        assertTrue("and the attempt checked again between them", connect.subList(settle, join).any { it.startsWith("if (!core.current(attempt)") })
    }

    @Test
    fun theLineAboutANewerPluginIsForSystemAdminsOnly() {
        val update = code(body(plugin, "override fun update()"))
        assertTrue(update.any { "pluginUpdateOf(" in it && "isSystemAdmin == true" in it })
    }

    /**
     * A state request that hangs (as on a slow connection), given up by a join's settle: its call
     * is cancelled on the wire, and nothing of it is heard. Answered here, nothing leaves.
     */
    @Test
    fun aStateRequestGivenUpIsCancelledOnTheWire() {
        val out = CountDownLatch(1)
        val call = AtomicReference<Call>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            call.set(chain.call())
            out.countDown()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!chain.call().isCanceled() && System.nanoTime() < until) Thread.sleep(5)
            throw IOException("Canceled")
        }.build()
        val api = VillaNetApi(MattermostClient("https://chat.example.test", "token", http))
        val heard = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sender = VoiceStateSender(scope, post = { m, d -> api.voiceState(m, d); null }, done = { on, r -> heard += "done $on $r" })
            sender.send(1, muted = true, deafened = false)
            assertTrue("the request is out", out.await(5, TimeUnit.SECONDS))
            runBlocking { sender.settle(limitMs = 200) }
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!call.get().isCanceled() && System.nanoTime() < until) Thread.sleep(5)
            assertTrue("the call is cancelled", call.get().isCanceled())
            Thread.sleep(100)
            assertTrue("nothing of it is heard: $heard", heard.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    /** Only the state request is cancelled on the wire: the module's other calls are as they were. */
    @Test
    fun onlyTheStateRequestIsCancelledOnTheWire() {
        val api = File(main, "plugins/villanet/VillaNetApi.kt").readText()
        val aborting = code(api).filter { "exchangeAborting(" in it && !it.startsWith("private suspend fun") }
        assertEquals(1, aborting.size)
        assertTrue(aborting.single().contains("req(\"/voice/state\")"))
    }
}
