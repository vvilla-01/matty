package ceo.villa.matty

import ceo.villa.matty.data.api.MattermostClient
import ceo.villa.matty.data.api.MattermostJson
import ceo.villa.matty.data.session.AppNeed
import ceo.villa.matty.data.session.AppNeedKeeper
import ceo.villa.matty.data.session.ReadPace
import ceo.villa.matty.data.session.SavedServer
import ceo.villa.matty.data.session.closesTo
import ceo.villa.matty.data.session.minimumInRefusal
import ceo.villa.matty.data.session.minimumOf
import ceo.villa.matty.plugins.villanet.PluginMediaRefusals
import ceo.villa.matty.plugins.villanet.VILLANET_ID
import ceo.villa.matty.plugins.villanet.VillaNetApi
import ceo.villa.matty.plugins.villanet.VillaNetException
import ceo.villa.matty.plugins.villanet.VnAccess
import ceo.villa.matty.ui.lock.updatePage
import ceo.villa.matty.ui.lock.updateWords
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections

/** A server's minimum Matty (Matty Plugin 4.4.0): when it closes the server, and what takes that away. */
class AppNeedTest {
    private val refusal = "This server needs Matty 0.1.33 or newer. You have 0.1.32."

    @Test
    fun anOlderAppIsClosedAndAnEqualOrNewerOneIsNot() {
        val k = AppNeedKeeper(null, "0.1.32")
        k.answered(k.asking(), "0.1.32", VILLANET_ID)
        assertNull("equal", k.need.value)
        k.answered(k.asking(), "0.1.30", VILLANET_ID)
        assertNull("newer", k.need.value)
        k.answered(k.asking(), "0.1.33", VILLANET_ID)
        assertEquals(AppNeed("0.1.33", "0.1.32", VILLANET_ID), k.need.value)
        // Compared part by part, not as text.
        val k2 = AppNeedKeeper(null, "0.1.32")
        k2.answered(k2.asking(), "0.1.4", VILLANET_ID)
        assertNull(k2.need.value)
        k2.answered(k2.asking(), "0.2.0", VILLANET_ID)
        assertEquals("0.2.0", k2.need.value?.minimum)
    }

    @Test
    fun aDebugBuildIsTakenAsItsVersionAsTheServerTakesIt() {
        // The server leaves a "-…" ending out of the User-Agent's version; so does the app.
        assertFalse(closesTo(AppNeed("0.1.32"), "0.1.32-debug"))
        assertTrue(closesTo(AppNeed("0.1.33"), "0.1.32-debug"))
        val k = AppNeedKeeper(null, "0.1.32-debug")
        k.answered(k.asking(), "0.1.32", VILLANET_ID)
        assertNull(k.need.value)
    }

    @Test
    fun aMissingOrUnreadableMinimumIsNone() {
        assertNull(minimumOf(null))
        assertNull(minimumOf(""))
        assertNull(minimumOf("  "))
        assertNull(minimumOf("0.1"))
        assertNull(minimumOf("v0.1.33"))
        assertNull(minimumOf("0.1.33-beta"))
        assertEquals("0.1.33", minimumOf(" 0.1.33 "))
        val k = AppNeedKeeper(AppNeed("0.1.33", "0.1.32", VILLANET_ID), "0.1.32")
        // A plugin of 4.3.0 or older says nothing of it: the answer takes this version.
        k.answered(k.asking(), null, VILLANET_ID)
        assertNull(k.need.value)
        // A minimum the app cannot read still says that one is set: it closes, naming none.
        k.answered(k.asking(), "soon", VILLANET_ID)
        assertEquals(AppNeed("", "0.1.32", VILLANET_ID), k.need.value)
        k.answered(k.asking(), " ", VILLANET_ID)
        assertNull(k.need.value)
        // And /access as a 4.3.0 plugin sends it is read with no minimum.
        val old = MattermostJson.decodeFromString(VnAccess.serializer(), """{"access":"linked","configured":true,"contract":4,"version":"4.3.0","app":{"check":"report","state":"verified"}}""")
        assertEquals("", old.app?.minimum)
        val new = MattermostJson.decodeFromString(VnAccess.serializer(), """{"access":"linked","configured":true,"contract":4,"version":"4.4.0","app":{"check":"off","minimum":"0.1.32"}}""")
        assertEquals("0.1.32", new.app?.minimum)
    }

    @Test
    fun theRefusalClosesTheServerWithTheMinimumItNames() {
        assertEquals("0.1.33", minimumInRefusal(refusal))
        assertEquals("0.1.33", minimumInRefusal("This server needs Matty 0.1.33 or newer."))
        assertNull(minimumInRefusal("No."))
        assertNull(minimumInRefusal(null))
        val k = AppNeedKeeper(null, "0.1.32")
        k.refused(refusal, VILLANET_ID)
        assertEquals(AppNeed("0.1.33", "0.1.32", VILLANET_ID), k.need.value)
        // A sentence that names none keeps the minimum already known.
        k.refused("Not this one.", VILLANET_ID)
        assertEquals("0.1.33", k.need.value?.minimum)
        // With none known it still closes, for this very version only.
        val bare = AppNeedKeeper(null, "0.1.32")
        bare.refused(null, VILLANET_ID)
        assertEquals(AppNeed("", "0.1.32", VILLANET_ID), bare.need.value)
        assertTrue(closesTo(bare.need.value, "0.1.32"))
        assertFalse(closesTo(bare.need.value, "0.1.33"))
        // One that names a version this app is not older than (the server read it otherwise) too.
        val odd = AppNeedKeeper(null, "0.1.32")
        odd.refused("This server needs Matty 0.1.32 or newer.", VILLANET_ID)
        assertEquals(AppNeed("", "0.1.32", VILLANET_ID), odd.need.value)
    }

    @Test
    fun thePageGoesWhenAnAnswerAskedAfterTheRefusalTakesThisVersion() {
        val k = AppNeedKeeper(null, "0.1.32")
        // A read already out when the refusal came cannot know of it: it does not open the server.
        val before = k.asking()
        k.refused(refusal, VILLANET_ID)
        k.answered(before, null, VILLANET_ID)
        assertEquals("0.1.33", k.need.value?.minimum)
        // The page's own read, asked after it, does (the admin lowered the minimum, or cleared it).
        k.answered(k.asking(), "0.1.31", VILLANET_ID)
        assertNull(k.need.value)
        // An answer that names a newer minimum closes it at once.
        k.answered(k.asking(), "0.1.40", VILLANET_ID)
        assertEquals("0.1.40", k.need.value?.minimum)
    }

    @Test
    fun aClosingAnswerAskedBeforeTheAnswerThatOpenedIsNotBelieved() {
        val k = AppNeedKeeper(AppNeed("0.1.33", "0.1.32", VILLANET_ID), "0.1.32")
        // Two reads overlap: the newer one answers first (the admin cleared the minimum).
        val older = k.asking()
        val newer = k.asking()
        k.answered(newer, null, VILLANET_ID)
        assertNull(k.need.value)
        // The older one's answer comes late, with the old minimum: the server stays open.
        k.answered(older, "0.1.33", VILLANET_ID)
        assertNull(k.need.value)
        // A read asked after the one that opened still closes it.
        k.answered(k.asking(), "0.1.33", VILLANET_ID)
        assertEquals("0.1.33", k.need.value?.minimum)
        // And a refusal always does.
        val k2 = AppNeedKeeper(null, "0.1.32")
        k2.answered(k2.asking(), null, VILLANET_ID)
        k2.refused(refusal, VILLANET_ID)
        assertEquals("0.1.33", k2.need.value?.minimum)
    }

    @Test
    fun aVersionIsReadByThePluginsRule() {
        // Three numbers of up to nine digits, none with a leading zero, as the plugin takes them.
        assertEquals("0.1.1000000", minimumOf("0.1.1000000"))
        assertEquals("999999999.0.999999999", minimumOf("999999999.0.999999999"))
        assertNull(minimumOf("0.1.1000000000"))
        assertNull(minimumOf("0.01.2"))
        assertNull(minimumOf("0.1.\u0663\u0663"))
        assertEquals("0.1.1000000", minimumInRefusal("This server needs Matty 0.1.1000000 or newer. You have 0.1.32."))
        assertNull(minimumInRefusal("This server needs Matty 0.1.1000000000 or newer."))
        assertNull(minimumInRefusal("This server needs Matty 0.01.2 or newer."))
        // So a long part the server takes keeps the server closed, round after round.
        val k = AppNeedKeeper(null, "0.1.32")
        k.refused("This server needs Matty 0.1.1000000 or newer. You have 0.1.32.", VILLANET_ID)
        assertEquals(AppNeed("0.1.1000000", "0.1.32", VILLANET_ID), k.need.value)
        k.answered(k.asking(), "0.1.1000000", VILLANET_ID)
        assertEquals(AppNeed("0.1.1000000", "0.1.32", VILLANET_ID), k.need.value)
        assertTrue(closesTo(k.need.value, "0.1.32"))
        assertEquals("Acme needs Matty 0.1.1000000 or newer. You have 0.1.32.", updateWords("Acme", "0.1.1000000", "0.1.32"))
        // And one the app cannot read closes it too, with the sentence that names no number.
        val odd = AppNeedKeeper(null, "0.1.32")
        odd.answered(odd.asking(), "0.1.1000000000", VILLANET_ID)
        assertEquals(AppNeed("", "0.1.32", VILLANET_ID), odd.need.value)
        assertEquals("Acme needs a newer version of Matty. You have 0.1.32.", updateWords("Acme", odd.need.value!!.minimum, "0.1.32"))
    }

    @Test
    fun thePagesReadsAreAtLeastFiveSecondsApart() {
        var now = 1_000L
        val pace = ReadPace(5_000L) { now }
        assertEquals("the first at once", 0L, pace.claim())
        now += 1_000
        assertEquals("a tap a second later waits for the rest", 4_000L, pace.claim())
        // Another at that moment is booked after that one.
        assertEquals(9_000L, pace.claim())
        // Once the gap has passed, at once again.
        now += 30_000
        assertEquals(0L, pace.claim())
    }

    @Test
    fun aPluginNoLongerOnTheServerRequiresNothing() {
        val k = AppNeedKeeper(AppNeed("0.1.33", "0.1.32", VILLANET_ID), "0.1.32")
        k.listed(setOf(VILLANET_ID, "com.example.polls"))
        assertEquals("0.1.33", k.need.value?.minimum)
        k.listed(setOf("com.example.polls"))
        assertNull(k.need.value)
    }

    @Test
    fun rememberedAcrossARestart() {
        val saved = mutableListOf<AppNeed?>()
        val k = AppNeedKeeper(null, "0.1.32") { saved += it }
        k.answered(k.asking(), "0.1.33", VILLANET_ID)
        k.answered(k.asking(), "0.1.33", VILLANET_ID)
        assertEquals(listOf<AppNeed?>(AppNeed("0.1.33", "0.1.32", VILLANET_ID)), saved)
        // Kept with the saved server, and read back from a save made before there was one.
        val s = SavedServer("id", "https://chat.example", "Example", "u", "user", "sealed", appNeed = saved.last())
        val json = MattermostJson.encodeToString(SavedServer.serializer(), s)
        val back = MattermostJson.decodeFromString(SavedServer.serializer(), json)
        assertEquals(s, back)
        val old = """{"id":"id","url":"https://chat.example","name":"Example","userId":"u","username":"user","sealedToken":"sealed"}"""
        assertNull(MattermostJson.decodeFromString(SavedServer.serializer(), old).appNeed)
        // The next start is closed before anything is asked.
        assertEquals("0.1.33", AppNeedKeeper(back.appNeed, "0.1.32").need.value?.minimum)
        // An app updated since is not: the kept need goes at once.
        val dropped = mutableListOf<AppNeed?>()
        assertNull(AppNeedKeeper(back.appNeed, "0.1.33") { dropped += it }.need.value)
        assertEquals(listOf<AppNeed?>(null), dropped)
        // Nor is one that named no minimum, once the version differs.
        assertNull(AppNeedKeeper(AppNeed("", "0.1.32", VILLANET_ID), "0.1.33").need.value)
        assertEquals(AppNeed("", "0.1.32", VILLANET_ID), AppNeedKeeper(AppNeed("", "0.1.32", VILLANET_ID), "0.1.32").need.value)
    }

    @Test
    fun theUpdatePagesWords() {
        assertEquals("Acme Chat needs Matty 0.1.33 or newer. You have 0.1.32.", updateWords("Acme Chat", "0.1.33", "0.1.32"))
        assertEquals("Acme Chat needs a newer version of Matty. You have 0.1.32-debug.", updateWords("Acme Chat", "", "0.1.32-debug"))
        assertEquals("https://github.com/vvilla-01/matty/releases/latest", updatePage("https://github.com/vvilla-01/matty"))
        assertEquals("https://github.com/vvilla-01/matty/releases/latest", updatePage("https://github.com/vvilla-01/matty/"))
    }

    /** A plugin that answers every request with [answer], and the outdated refusals heard. */
    private class Answering {
        @Volatile var answer: Pair<Int, String> = 200 to "{}"
        val heard: MutableList<String?> = Collections.synchronizedList(ArrayList())
        val http: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val (code, body) = answer
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("-")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = VillaNetApi(MattermostClient("https://chat.example.test", "token", http), onOutdated = { heard += it })
    }

    @Test
    fun theRefusalIsHeardOnRequestsAndPictures() = runBlocking {
        val a = Answering()
        a.answer = 403 to """{"error": "$refusal", "code": "app_outdated"}"""
        val e = runCatching { a.api.streams() }.exceptionOrNull() as VillaNetException
        assertEquals(VillaNetApi.APP_OUTDATED, e.code)
        assertEquals(refusal, e.serverMessage)
        runCatching { a.api.vote("c1", true) }
        assertEquals(listOf<String?>(refusal, refusal), a.heard.toList())
        // Another refusal is not this one.
        a.heard.clear()
        a.answer = 403 to """{"error": "Locked.", "code": "locked"}"""
        runCatching { a.api.streams() }
        assertEquals(emptyList<String?>(), a.heard.toList())
        // A picture of the plugin's, refused the same way.
        val media = OkHttpClient.Builder().addInterceptor(PluginMediaRefusals.interceptor).addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("-")
                .body("""{"error": "$refusal", "code": "app_outdated"}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val stop = a.api.hearMedia()
        try {
            media.newCall(Request.Builder().url(a.api.bannerUrl("u1", "3")).build()).execute().close()
        } finally {
            stop()
        }
        assertEquals(listOf<String?>(refusal), a.heard.toList())
    }

    private val src = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "app/src/main/java/ceo/villa/matty") }.first { it.isDirectory }

    @Test
    fun theLockShowsFirstThenTheUpdatePage() {
        // Nothing of a server that asks for an unlock shows before it, the update page included.
        val root = File(src, "ui/MattyRoot.kt").readText()
        assertTrue(root.contains("ServerGate(activity, r) { UpdateGate(activity, r, nav) { Session(activity, r, nav) } }"))
        // The plugin list's read tells the keeper which plugins still run.
        assertTrue(File(src, "data/repo/ChatRepository.kt").readText().contains("appNeeds.listed("))
    }
}
