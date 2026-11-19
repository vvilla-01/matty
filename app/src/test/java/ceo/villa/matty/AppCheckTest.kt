package ceo.villa.matty

import ceo.villa.matty.data.api.MattermostJson
import ceo.villa.matty.plugins.villanet.Later
import ceo.villa.matty.plugins.villanet.ProofPlan
import ceo.villa.matty.plugins.villanet.ProofScheduler
import kotlinx.coroutines.delay
import ceo.villa.matty.plugins.villanet.ProofUnderWay
import ceo.villa.matty.plugins.villanet.VillaNetException
import ceo.villa.matty.plugins.villanet.VnAttestAnswer
import ceo.villa.matty.plugins.villanet.attempt
import ceo.villa.matty.plugins.villanet.proveOnce
import ceo.villa.matty.verify.AttestationUnavailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import ceo.villa.matty.verify.causesOf
import ceo.villa.matty.verify.staleProofKeys
import kotlinx.coroutines.runBlocking
import ceo.villa.matty.plugins.villanet.VnAppClaims
import ceo.villa.matty.plugins.villanet.VnAppWord
import ceo.villa.matty.plugins.villanet.VnAttestRequest
import ceo.villa.matty.plugins.villanet.appCheckChallenge
import ceo.villa.matty.plugins.villanet.appCheckOrigin
import ceo.villa.matty.plugins.villanet.appCheckWords
import ceo.villa.matty.plugins.villanet.pendingWords
import ceo.villa.matty.data.api.MattermostClient
import ceo.villa.matty.plugins.villanet.PanelPage
import ceo.villa.matty.plugins.villanet.VillaNetApi
import ceo.villa.matty.plugins.villanet.VnAccess
import ceo.villa.matty.plugins.villanet.afterAppRefusal
import ceo.villa.matty.plugins.villanet.appCheckGated
import ceo.villa.matty.plugins.villanet.panelPage
import okhttp3.MediaType.Companion.toMediaType
import ceo.villa.matty.plugins.villanet.AppRefusalHearing
import ceo.villa.matty.plugins.villanet.PluginMediaRefusals
import ceo.villa.matty.plugins.villanet.RefusedNext
import ceo.villa.matty.plugins.villanet.afterRefusedAccess
import ceo.villa.matty.plugins.villanet.refreshProves
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.Collections
import ceo.villa.matty.verify.MattyCertificates
import ceo.villa.matty.verify.SelfVerdict
import ceo.villa.matty.verify.SigningRecord
import ceo.villa.matty.verify.selfVerdictOf
import ceo.villa.matty.verify.selfWords
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app check as the app plays its part: the challenge it is attested for, when it proves itself, and what it says. */
class AppCheckTest {
    @Test
    fun theChallengeIsTheDigestOfWhatItIsFor() {
        // The plugin's own test pins the same figure for the same input
        // (server/appcheck/rules_test.go, TestTheChallengeIsTheDigestOfWhatItIsFor), and its
        // contract/README.md gives it as the worked example.
        val hex = appCheckChallenge("https://chat.example.com", "uid-alice", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=").joinToString("") { "%02x".format(it) }
        assertEquals("c398158673574461506bf44b65ed7a43ef8322a0804efb3b2ee73c94e475e6ab", hex)
        // Anything else in it is another challenge.
        assertFalse(appCheckChallenge("https://chat.example.com", "uid-bob", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=").contentEquals(appCheckChallenge("https://chat.example.com", "uid-alice", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")))
    }

    @Test
    fun theOriginIsWrittenAsThePluginWritesItsOwn() {
        // The plugin's appcheck.Origin table, for the addresses an app can be signed in at.
        for ((address, origin) in mapOf(
            "https://Chat.Example.com" to "https://chat.example.com",
            "https://chat.example.com:443/" to "https://chat.example.com",
            "HTTPS://chat.example.com/mattermost" to "https://chat.example.com",
            "http://localhost:8065" to "http://localhost:8065",
            "http://localhost:80" to "http://localhost",
            "https://chat.example.com:8443" to "https://chat.example.com:8443",
            "http://[::1]:8065" to "http://[::1]:8065",
            "https://[2001:DB8::1]" to "https://[2001:db8::1]",
            "https://[2001:db8:0:0::1]" to "https://[2001:db8::1]",
            "https://[2001:0db8:0000:0000:0001:0000:0000:0001]" to "https://[2001:db8::1:0:0:1]",
            "http://[::ffff:127.0.0.1]:8065" to "http://127.0.0.1:8065",
            "https://[::1.2.3.4]" to "https://[::102:304]",
            "https://bücher.example" to "https://xn--bcher-kva.example",
            "https://BÜCHER.Example" to "https://xn--bcher-kva.example",
            "https://日本.example:8443" to "https://xn--wgv71a.example:8443",
            "https://xn--bcher-kva.example" to "https://xn--bcher-kva.example",
        )) assertEquals(address, origin, appCheckOrigin(address.toHttpUrl()))
    }

    @Test
    fun theProofCarriesTheFieldsTheServerReads() {
        val body = MattermostJson.encodeToString(
            VnAttestRequest.serializer(),
            VnAttestRequest("AAEC", "https://chat.example.com", listOf("MIIB", "MIIC"), VnAppClaims("ceo.villa.matty", 28, "75:78", listOf("E5:52", "75:78"), true)),
        )
        val o = Json.parseToJsonElement(body).jsonObject
        assertEquals(setOf("challenge", "origin", "chain", "claims"), o.keys)
        assertEquals(2, o.getValue("chain").jsonArray.size)
        val claims = o.getValue("claims").jsonObject
        assertEquals(setOf("package", "versionCode", "currentSigner", "history", "hybrid"), claims.keys)
        assertEquals("ceo.villa.matty", claims.getValue("package").jsonPrimitive.content)
    }

    private var now = 0L
    private fun plan() = ProofPlan({ now }, againMs = 600_000L)

    @Test
    fun itProvesItselfOncePerSessionAndVersion() {
        val p = plan()
        // Off, or a server that doesn't say: nothing is asked.
        assertFalse(p.due("s", 28, null))
        assertFalse(p.due("s", 28, VnAppWord(check = "off", state = "none")))
        // On, and never checked: once.
        val none = VnAppWord(check = "report", state = "none")
        assertTrue(p.due("s", 28, none))
        p.tried("s", 28, verdict = true)
        // The verdict is in: this version is not asked again, verified or not, nor in a loop.
        assertFalse(p.due("s", 28, VnAppWord(check = "report", state = "verified", versionCode = 28)))
        assertFalse(p.due("s", 28, VnAppWord(check = "require", state = "unverified", reason = "Another certificate signed this Matty.", versionCode = 28)))
        // Nor later: a Matty that failed for what it is fails again.
        now += 6_000_000L
        assertFalse(p.due("s", 28, VnAppWord(check = "require", state = "unverified", reason = "Another certificate signed this Matty.", versionCode = 28)))
        // A verdict held for this version needs no try at all, in a session this app never tried.
        assertFalse(p.due("other", 28, VnAppWord(check = "require", state = "verified", versionCode = 28)))
        // Another version (the app was updated): proven again.
        assertTrue(p.due("s", 29, VnAppWord(check = "report", state = "verified", versionCode = 28)))
    }

    @Test
    fun aVerdictGivenBeforeTheAttestationWasReadIsTriedAgainInAWhile() {
        // The server refused before it read the app's version (its Site URL is another, or the
        // phone's boot key is not listed yet): an admin may change that, so it is tried again,
        // not straight away.
        val p = plan()
        val early = VnAppWord(check = "require", state = "unverified", reason = "The app reached this server at A, but its Site URL is B.")
        p.tried("s", 28, verdict = true)
        assertFalse(p.due("s", 28, early))
        now += 599_999L
        assertFalse(p.due("s", 28, early))
        now += 1L
        assertTrue(p.due("s", 28, early))
    }

    @Test
    fun thePersonsOwnTapTriesAgain() {
        val p = plan()
        val none = VnAppWord(check = "require", state = "none")
        // A try that came to nothing: at once, on the person's tap.
        p.tried("s", 28, verdict = false)
        assertFalse(p.due("s", 28, none))
        p.forget("s")
        assertTrue(p.due("s", 28, none))
        // A phone that could not make the key (the hardware may have been busy): the same.
        p.tried("s", 28, verdict = false, cannot = true)
        assertFalse(p.due("s", 28, none))
        p.forget("s")
        assertTrue(p.due("s", 28, none))
        // A verdict given before the attestation was read: the same.
        val early = VnAppWord(check = "require", state = "unverified", reason = "Its Site URL is not set.")
        p.tried("s", 28, verdict = true)
        assertFalse(p.due("s", 28, early))
        p.forget("s")
        assertTrue(p.due("s", 28, early))
        // Only this session's tries are forgotten.
        p.tried("s", 28, verdict = false)
        p.tried("t", 28, verdict = false)
        p.forget("s")
        assertTrue(p.due("s", 28, none))
        assertFalse(p.due("t", 28, none))
        // A verified verdict the server holds for this version stands, even on the person's tap.
        p.forget("t")
        assertFalse(p.due("t", 28, VnAppWord(check = "require", state = "verified", versionCode = 28)))
    }

    @Test
    fun thePersonsTapProvesOncePastAnUnverifiedVerdictHeldForThisVersion() {
        // Refused for a certificate the server did not accept yet; an admin may have added it since.
        val p = plan()
        val refused = VnAppWord(check = "require", state = "unverified", reason = "This copy of Matty was signed with a certificate this server does not accept (AB:CD).", versionCode = 28)
        p.tried("s", 28, verdict = true)
        assertFalse(p.due("s", 28, refused))
        // The tap: one proof, past the verdict.
        p.forget("s")
        assertTrue(p.due("s", 28, refused))
        // It is made, and the server refuses again: no more until the next tap, never in a loop.
        p.tried("s", 28, verdict = true)
        assertFalse(p.due("s", 28, refused))
        now += 6_000_000L
        assertFalse(p.due("s", 28, refused))
        // Another tap: one more.
        p.forget("s")
        assertTrue(p.due("s", 28, refused))
        p.tried("s", 28, verdict = false)
        assertFalse(p.due("s", 28, refused))
        // Only the session tapped in.
        p.forget("s")
        assertFalse(p.due("t", 28, refused))
    }

    @Test
    fun aProofCancelledBeforeItWasAnsweredIsNoTry() = runBlocking {
        val p = plan()
        val none = VnAppWord(check = "require", state = "none")
        // The person switches servers while the proof is on its way: no try is recorded, so
        // coming back at once proves again.
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val left = launch { p.attempt("s", 28, cannot = {}) { started.complete(Unit); awaitCancellation() } }
        started.await()
        left.cancelAndJoin()
        assertTrue(p.due("s", 28, none))
        // Nor when the cancel comes back as the network's own failure (a call cancelled under it).
        val started2 = kotlinx.coroutines.CompletableDeferred<Unit>()
        val left2 = launch {
            p.attempt("s", 28, cannot = {}) {
                started2.complete(Unit)
                try { awaitCancellation() } catch (e: CancellationException) { throw java.io.IOException("Canceled") }
            }
        }
        started2.await()
        left2.cancelAndJoin()
        assertTrue(p.due("s", 28, none))
        // A try that came to nothing while it ran is one: not again straight away.
        assertFalse(p.attempt("s", 28, cannot = {}) { throw java.io.IOException("offline") })
        assertFalse(p.due("s", 28, none))
        // An answered one is one, and a phone that could not is told why and not asked again.
        assertTrue(p.attempt("t", 28, cannot = {}) { })
        assertFalse(p.due("t", 28, none))
        var why: String? = null
        assertFalse(p.attempt("u", 28, cannot = { why = it }) { throw AttestationUnavailable("No secure hardware.") })
        assertEquals("No secure hardware.", why)
        now += 6_000_000L
        assertFalse(p.due("u", 28, none))
    }

    @Test
    fun aProofForAServerJustLeftHoldsNothingBackOfTheNext() {
        val u = ProofUnderWay()
        val a = u.begin("A")!!
        // One at a time.
        assertNull(u.begin("A"))
        // The person switches servers: A's is dropped, and B's may start.
        u.drop()
        val b = u.begin("B")!!
        assertEquals("B", u.run.value?.session)
        // A's ends at last: B's is still on its way.
        u.end(a)
        assertEquals(b, u.run.value)
        assertNull(u.begin("B"))
        u.end(b)
        assertNull(u.run.value)
    }

    @Test
    fun aStaleChallengeIsAskedForOnceMore() = runBlocking {
        val asked = mutableListOf<String>()
        var n = 0
        val stale = VillaNetException("conflict", "This challenge has gone.", 409)
        // A 409: a new challenge straight away, and its answer is the verdict.
        val answer = proveOnce({ "c${++n}" }) { c -> asked += c; if (c == "c1") throw stale else VnAttestAnswer(state = "verified") }
        assertEquals("verified", answer.state)
        assertEquals(listOf("c1", "c2"), asked)
        // Only once: a second 409 is a try that came to nothing.
        n = 0
        assertEquals(409, runCatching { proveOnce({ "c${++n}" }) { throw stale } }.exceptionOrNull().let { (it as VillaNetException).status })
        assertEquals(2, n)
        // Any other refusal is not retried.
        n = 0
        assertEquals(503, runCatching { proveOnce({ "c${++n}" }) { throw VillaNetException("unavailable", "Busy.", 503) } }.exceptionOrNull().let { (it as VillaNetException).status })
        assertEquals(1, n)
    }

    @Test
    fun keysLeftBehindAreSweptAndAndroidsWrappedFailuresAreRead() {
        assertEquals(
            listOf("matty-app-check-0b1d", "matty-app-check-"),
            staleProofKeys(listOf("matty-app-check-0b1d", "other-key", "matty-app-check-", "xmatty-app-check-1")),
        )
        val inner = IllegalStateException("inner")
        val middle = java.security.ProviderException("middle", inner)
        val outer = RuntimeException("outer", middle)
        assertEquals(listOf(outer, middle, inner), causesOf(outer).toList())
        // A cause that loops back ends the walk.
        val a = Exception("a")
        val b = Exception("b", a)
        a.initCause(b)
        assertEquals(listOf(a, b), causesOf(a).toList())
    }

    @Test
    fun aVerdictThatHasGoneIsAskedForAgainButNotInALoop() {
        val p = plan()
        val none = VnAppWord(check = "require", state = "none")
        p.tried("s", 28, verdict = true)
        // The server says none again (it expired, or it could not keep it): not straight away...
        assertFalse(p.due("s", 28, none))
        now += 29_999L
        assertFalse(p.due("s", 28, none))
        // ...but soon, not after the ten minutes of a try that came to nothing.
        now += 1L
        assertTrue(p.due("s", 28, none))
    }

    @Test
    fun aVerdictTheServerStoppedCountingWhenItsRulesChangedIsProvedAgainSoon() {
        val p = plan()
        val changed = "This server's rules for the app check have changed, and this Matty is to be checked under the new ones."
        // Verified, then an admin takes the certificate off the list: the server says none.
        p.tried("s", 28, verdict = true)
        now += 120_000L
        assertFalse(p.due("s", 28, VnAppWord(check = "require", state = "verified", versionCode = 28)))
        assertTrue(p.due("s", 28, VnAppWord(check = "require", state = "none", reason = changed)))
        // The new proof fails: that verdict stands for this version, past the half minute too.
        p.tried("s", 28, verdict = true)
        val failed = VnAppWord(check = "require", state = "unverified", reason = "This copy of Matty was signed with a certificate this server does not accept (AB:CD).", versionCode = 28)
        now += 6_000_000L
        assertFalse(p.due("s", 28, failed))
        // The admin lists the certificate again: none once more, and proved again at once, as
        // the last try was long ago; under report the same.
        assertTrue(p.due("s", 28, VnAppWord(check = "report", state = "none", reason = changed)))
        // Proved, and the server at once says none again (it could not keep it): not in a loop.
        p.tried("s", 28, verdict = true)
        now += 10_000L
        assertFalse(p.due("s", 28, VnAppWord(check = "report", state = "none", reason = changed)))
        // A try that came to nothing keeps the ten minutes.
        p.tried("s", 28, verdict = false)
        now += 30_000L
        assertFalse(p.due("s", 28, VnAppWord(check = "report", state = "none", reason = changed)))
        now += 570_000L
        assertTrue(p.due("s", 28, VnAppWord(check = "report", state = "none", reason = changed)))
    }

    @Test
    fun theProofIsDueNowLaterOrNotWithoutTheAsk() {
        val p = plan()
        val none = VnAppWord(check = "report", state = "none")
        val verified = VnAppWord(check = "report", state = "verified", versionCode = 28)
        val failed = VnAppWord(check = "require", state = "unverified", reason = "This copy of Matty was signed with a certificate this server does not accept (AB:CD).", versionCode = 28)
        // The check off, or no word: not without the ask.
        assertNull(p.dueIn("s", 28, null))
        assertNull(p.dueIn("s", 28, VnAppWord(check = "off", state = "none")))
        // Never tried: now.
        assertEquals(0L, p.dueIn("s", 28, none))
        // A verdict held for this version: a verified one never, an unverified one on the ask.
        assertNull(p.dueIn("s", 28, verified))
        assertNull(p.dueIn("s", 28, failed))
        p.forget("s")
        assertEquals(0L, p.dueIn("s", 28, failed))
        assertNull(p.dueIn("s", 28, verified))
        // This app was given a verdict here, and the server holds one: not without the ask.
        p.tried("s", 28, verdict = true)
        assertNull(p.dueIn("s", 28, VnAppWord(check = "report", state = "verified", versionCode = 27)))
        assertNull(p.dueIn("s", 28, failed.copy(versionCode = 27)))
        // The server holds none where it gave a verdict: half a minute after the last try.
        now += 10_000L
        assertEquals(20_000L, p.dueIn("s", 28, none))
        now += 20_000L
        assertEquals(0L, p.dueIn("s", 28, none))
        now += 5_000L
        assertEquals(0L, p.dueIn("s", 28, none))
        // A verdict given before the attestation was read: ten minutes after the last try.
        val early = VnAppWord(check = "require", state = "unverified", reason = "Its Site URL is not set.")
        assertEquals(565_000L, p.dueIn("s", 28, early))
        // A try that came to nothing: ten minutes after it.
        p.tried("s", 28, verdict = false)
        assertEquals(600_000L, p.dueIn("s", 28, none))
        now += 1L
        assertEquals(599_999L, p.dueIn("s", 28, none))
        // A phone that cannot make the proof: not without the ask, however long it has been.
        p.tried("s", 28, verdict = false, cannot = true)
        now += 6_000_000L
        assertNull(p.dueIn("s", 28, none))
        // due is dueIn's "now".
        assertFalse(p.due("s", 28, none))
        p.forget("s")
        assertTrue(p.due("s", 28, none))
    }

    @Test
    fun aProofDueLaterHasTheAccessReadThenWithNobodyThere() = runBlocking {
        now = 0L
        val p = ProofPlan({ now }, againMs = 400L, soonMs = 100L)
        var reads = 0
        val later = Later(this) { reads++ }
        val s = ProofScheduler(p, later)
        val none = VnAppWord(check = "report", state = "none")
        val verified = VnAppWord(check = "report", state = "verified", versionCode = 28)
        // Never tried: due now, and nothing is set for later.
        assertTrue(s.dueNow("s", 28, none, proving = false))
        assertFalse(later.waiting)
        // The server answered: everything is read again (and that read decides what comes next).
        p.tried("s", 28, verdict = true)
        assertTrue(s.ended(true, "s", 28, none))
        assertFalse(later.waiting)
        // While a proof is on its way, nothing is decided.
        assertFalse(s.dueNow("s", 28, none, proving = true))
        assertFalse(later.waiting)
        // The verdict has gone: one read, half a minute (here 100 ms) after the last try; a
        // second answer replaces the wait that is set.
        assertFalse(s.dueNow("s", 28, none, proving = false))
        assertTrue(later.waiting)
        now += 50L
        assertFalse(s.dueNow("s", 28, none, proving = false))
        delay(20)
        assertEquals("not before its wait", 0, reads)
        delay(70)
        assertEquals("once, at the new wait", 1, reads)
        delay(100)
        assertEquals("the wait it replaced does nothing", 1, reads)
        // A verdict held for this version: what was set is cancelled.
        assertFalse(s.dueNow("s", 28, none, proving = false))
        assertFalse(s.dueNow("s", 28, verified, proving = false))
        assertFalse(later.waiting)
        // A try that came to nothing (the server busy): the access is read again in a while,
        // with nobody there, and the next proof is made then.
        assertFalse(p.attempt("s", 28, cannot = {}) { throw java.io.IOException("busy") })
        assertFalse(s.ended(false, "s", 28, none))
        assertTrue(later.waiting)
        delay(300)
        assertEquals("not before its wait", 1, reads)
        delay(200)
        assertEquals("once, ten minutes (here 400 ms) after the try", 2, reads)
        now += 400L
        assertTrue("due then", s.dueNow("s", 28, none, proving = false))
        // A phone that cannot make the proof: nothing more without the person's ask.
        later.after(1_000L)
        assertFalse(p.attempt("s", 28, cannot = {}) { throw AttestationUnavailable("No secure hardware.") })
        assertFalse(s.ended(false, "s", 28, none))
        assertFalse(later.waiting)
        // The person left this server: what was set is cancelled.
        p.forget("s")
        p.tried("s", 28, verdict = true)
        assertFalse(s.dueNow("s", 28, none, proving = false))
        assertTrue(later.waiting)
        s.cancel()
        assertFalse(later.waiting)
        delay(200)
        assertEquals(2, reads)
    }

    @Test
    fun aServerThatDropsEveryVerdictIsProvedToAFewTimesNotForEver() {
        val p = plan()
        val gone = VnAppWord(check = "report", state = "none", reason = "This server's rules for the app check have changed, and this Matty is to be checked under the new ones.")
        // The first proof, then four more half a minute apart (or an admin who changes the
        // lists four times in ten minutes: each change is followed).
        p.tried("s", 28, verdict = true)
        repeat(4) {
            assertEquals(30_000L, p.dueIn("s", 28, gone))
            now += 30_000L
            assertTrue(p.due("s", 28, gone))
            p.tried("s", 28, verdict = true)
        }
        // After those, ten minutes.
        assertEquals(600_000L, p.dueIn("s", 28, gone))
        now += 599_999L
        assertFalse(p.due("s", 28, gone))
        now += 1L
        assertTrue(p.due("s", 28, gone))
        // The proof made then is a run's first: half a minute again.
        p.tried("s", 28, verdict = true)
        assertEquals(30_000L, p.dueIn("s", 28, gone))
        // Proofs further apart than ten minutes are no run at all.
        repeat(6) {
            now += 600_000L
            p.tried("s", 28, verdict = true)
            assertEquals(30_000L, p.dueIn("s", 28, gone))
        }
        // The person's ask starts the count again.
        repeat(4) {
            now += 30_000L
            p.tried("s", 28, verdict = true)
        }
        assertEquals(600_000L, p.dueIn("s", 28, gone))
        p.forget("s")
        assertEquals(0L, p.dueIn("s", 28, gone))
        p.tried("s", 28, verdict = true)
        assertEquals(30_000L, p.dueIn("s", 28, gone))
        // Only that session's.
        repeat(4) {
            now += 30_000L
            p.tried("s", 28, verdict = true)
            p.tried("t", 28, verdict = true)
        }
        p.tried("t", 28, verdict = true)
        p.forget("s")
        assertEquals(600_000L, p.dueIn("t", 28, gone))
    }

    @Test
    fun refreshProvesWhileRefusedOrWhileTheServerHoldsNoVerdict() {
        val changed = "This server's rules for the app check have changed, and this Matty is to be checked under the new ones."
        val failed = VnAppWord(check = "report", state = "unverified", reason = "This copy of Matty was signed with a certificate this server does not accept (AB:CD).", versionCode = 28)
        // Refused: as Try again, whatever is in hand.
        assertTrue(refreshProves("Refused.", failed.copy(check = "require")))
        assertTrue(refreshProves("Refused.", null))
        // The server's check is on and it holds nothing for this Matty.
        assertTrue(refreshProves(null, VnAppWord(check = "report", state = "none", reason = changed)))
        assertTrue(refreshProves(null, VnAppWord(check = "require", state = "none")))
        // A verdict the server holds, the check off, or no word: only read again.
        assertFalse(refreshProves(null, failed))
        assertFalse(refreshProves(null, VnAppWord(check = "report", state = "verified", versionCode = 28)))
        assertFalse(refreshProves(null, VnAppWord(check = "off", state = "none")))
        assertFalse(refreshProves(null, null))
        // So a Refresh a quarter of a minute after the last proof proves at once.
        val p = plan()
        val gone = VnAppWord(check = "report", state = "none", reason = changed)
        p.tried("s", 28, verdict = true)
        now += 15_000L
        assertFalse(p.due("s", 28, gone))
        if (refreshProves(null, gone)) p.forget("s")
        assertTrue(p.due("s", 28, gone))
    }

    @Test
    fun aRefusalTakesTheVerifiedWordAway() {
        val verified = VnAppWord(check = "require", state = "verified", development = true, versionCode = 28)
        val after = afterAppRefusal(verified)!!
        assertEquals("none", after.state)
        assertEquals("require", after.check)
        // So the line no longer says verified beside the refusal.
        assertEquals("This phone's Matty has not been checked by this server yet" to true, appCheckWords(after, null, false))
        assertEquals("This phone's Matty is being checked by this server" to false, appCheckWords(after, null, true))
        // Any other word stands as the server gave it.
        val failed = VnAppWord(check = "require", state = "unverified", reason = "No.", versionCode = 28)
        assertEquals(failed, afterAppRefusal(failed))
        assertNull(afterAppRefusal(null))
    }

    @Test
    fun everyRouteButTheExemptOnesIsGated() {
        // The plugin's appCheckExempt (server/app_check.go).
        for (path in listOf("/access", "/me", "/release", "/app/challenge", "/app/attest")) assertFalse(path, appCheckGated(path))
        for (path in listOf("/streams", "/accounts", "/boards", "/banners", "/banner", "/clips", "/clips/clip", "/whep", "/me/link", "/me/banner", "/schedule/join")) assertTrue(path, appCheckGated(path))
        assertFalse("not one of the plugin's routes", appCheckGated(""))
    }

    /** A plugin that answers every request with [answer], and what the app check heard of each. */
    private class Answering {
        @Volatile var answer: Pair<Int, String> = 200 to "{}"
        val heard: MutableList<String?> = Collections.synchronizedList(ArrayList())
        private val http = OkHttpClient.Builder().addInterceptor { chain ->
            val (code, body) = answer
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("-")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = VillaNetApi(MattermostClient("https://chat.example.test", "token", http), onAppCheck = { heard += it })
    }

    @Test
    fun aRefusalForTheAppCheckIsHeardOnAnyRequestAndAGatedAnswerTakesItAway() = runBlocking {
        val a = Answering()
        val sentence = "VillaNET answers only the official Matty app, and this copy has to be checked again: this server's rules for the check have changed."
        a.answer = 403 to """{"error": "$sentence", "code": "app_unverified"}"""
        runCatching { a.api.streams() }
        runCatching { a.api.accounts("\"t\"") }
        runCatching { a.api.whep("alice", "v=0") }
        runCatching { a.api.vote("c1", true) }
        assertEquals(listOf<String?>(sentence, sentence, sentence, sentence), a.heard.toList())
        // Another refusal is not about the app check.
        a.heard.clear()
        a.answer = 403 to """{"error": "Locked.", "code": "locked"}"""
        runCatching { a.api.streams() }
        assertEquals(emptyList<String?>(), a.heard.toList())
        // A gated route answered (in full, or "nothing changed"): heard as null.
        a.answer = 200 to """{"streams": []}"""
        a.api.streams()
        a.answer = 304 to ""
        a.api.boards("\"t\"")
        assertEquals(listOf<String?>(null, null), a.heard.toList())
        // The routes a refused Matty may still use say nothing of it.
        a.heard.clear()
        a.answer = 200 to """{"access": "linked"}"""
        a.api.access()
        assertEquals(emptyList<String?>(), a.heard.toList())
    }

    @Test
    fun aRefusedPictureIsHeardLikeTheOtherRequests() {
        val sentence = "Acme TV answers only the official Matty app, and this copy has to be checked again: this server's rules for the check have changed."
        var answer = 403 to """{"error": "$sentence", "code": "app_unverified"}"""
        // The media client: the hearing, then a server that answers every picture with [answer].
        val media = OkHttpClient.Builder().addInterceptor(PluginMediaRefusals.interceptor).addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(answer.first).message("-")
                .body(answer.second.toResponseBody("application/json".toMediaType())).build()
        }.build()
        fun load(url: String) = media.newCall(Request.Builder().url(url).build()).execute().close()
        val a = Answering()
        val stop = a.api.hearMedia()
        try {
            load(a.api.bannerUrl("u1", "3"))
            load(a.api.clipThumbUrl("c1", "2"))
            assertEquals(listOf<String?>(sentence, sentence), a.heard.toList())
            // Another server's picture, another refusal, or a picture answered: nothing.
            a.heard.clear()
            load("https://elsewhere.example.test/plugins/x/api/v1/stills?handle=alice")
            answer = 403 to """{"error": "Locked.", "code": "locked"}"""
            load(a.api.bannerUrl("u1", "3"))
            answer = 200 to "{}"
            load(a.api.bannerUrl("u1", "3"))
            assertEquals(emptyList<String?>(), a.heard.toList())
        } finally {
            stop()
        }
        // Once it stops listening, nothing.
        answer = 403 to """{"error": "$sentence", "code": "app_unverified"}"""
        load(a.api.bannerUrl("u1", "3"))
        assertEquals(emptyList<String?>(), a.heard.toList())
    }

    @Test
    fun answersAreAppliedInTheOrderTheyWereHeard() {
        val refused = MutableStateFlow<String?>(null)
        val refusals = ArrayList<String>()
        val hearing = AppRefusalHearing(refused) { refusals += it }
        // The main thread's queue, run by hand: a refusal heard, then a gated answer, before either is applied.
        val queue = ArrayDeque<() -> Unit>()
        val post: (() -> Unit) -> Unit = { queue.addLast(it) }
        hearing.heard("Refused.", post)
        hearing.heard(null, post)
        while (queue.isNotEmpty()) queue.removeFirst()()
        assertNull(refused.value)
        assertEquals(listOf("Refused."), refusals)
        // And the other way round: the refusal stands.
        hearing.heard(null, post)
        hearing.heard("Refused again.", post)
        while (queue.isNotEmpty()) queue.removeFirst()()
        assertEquals("Refused again.", refused.value)
    }

    @Test
    fun aVerdictOfVerifiedTakesTheRefusalAwayAndStopsNothing() {
        val refused = MutableStateFlow<String?>("Refused.")
        val hearing = AppRefusalHearing(refused) {}
        hearing.proved("unverified")
        assertEquals("Refused.", refused.value)
        hearing.proved("verified")
        assertNull(refused.value)
        // GET /access read again after a refusal: the stream stops only while nothing says otherwise.
        val verified = VnAppWord(check = "require", state = "verified", versionCode = 28)
        val none = VnAppWord(check = "require", state = "none")
        assertEquals(RefusedNext.Stop, afterRefusedAccess("Refused.", false, none))
        assertEquals(RefusedNext.Stop, afterRefusedAccess("Refused.", false, null))
        assertEquals(RefusedNext.Nothing, afterRefusedAccess("Refused.", true, none))
        assertEquals(RefusedNext.Nothing, afterRefusedAccess(null, false, none))
        // The server holds this Matty verified: the refusal came before the verdict.
        assertEquals(RefusedNext.AskGated, afterRefusedAccess("Refused.", false, verified))
    }

    @Test
    fun aRefusalIsShownOnEveryTabWhateverIsInHand() {
        val linked = VnAccess(access = "linked", configured = true)
        assertEquals(PanelPage.Tabs, panelPage(null, linked, null))
        assertEquals(PanelPage.Refused, panelPage(null, linked, "Refused."))
        // Before the reader's access is in hand, and over a plugin too old for this app, as before.
        assertEquals(PanelPage.Waiting, panelPage(null, null, "Refused."))
        assertEquals(PanelPage.OutOfStep, panelPage("3.8.0", linked, "Refused."))
        // Before the lock screen and "not set up": everything there is refused as well.
        assertEquals(PanelPage.Refused, panelPage(null, VnAccess(access = "locked", configured = true), "Refused."))
        assertEquals(PanelPage.Locked, panelPage(null, VnAccess(access = "locked", configured = true), null))
        assertEquals(PanelPage.NotSetUp, panelPage(null, VnAccess(access = "linked", configured = false), null))
    }

    @Test
    fun aTryThatCameToNothingWaitsAndAPhoneThatCannotIsNotAsked() {
        val p = plan()
        val none = VnAppWord(check = "report", state = "none")
        p.tried("s", 28, verdict = false)
        assertFalse(p.due("s", 28, none))
        now += 600_000L
        assertTrue(p.due("s", 28, none))
        p.tried("s", 28, verdict = false, cannot = true)
        now += 6_000_000L
        assertFalse(p.due("s", 28, none))
    }

    @Test
    fun whatThePersonIsTold() {
        assertNull(appCheckWords(null, null, false))
        assertNull(appCheckWords(VnAppWord(check = "off", state = "none"), null, false))
        assertEquals("This phone's Matty is verified by this server" to false, appCheckWords(VnAppWord("report", "verified", versionCode = 28), null, false))
        assertEquals("This phone's Matty is verified by this server (development build)" to false, appCheckWords(VnAppWord("require", "verified", development = true), null, false))
        // The server's reason, in its words, as a sentence of its own.
        assertEquals("Not verified by this server. This phone's bootloader is unlocked." to true, appCheckWords(VnAppWord("report", "unverified", " This phone's bootloader is unlocked."), null, false))
        assertEquals("This phone's Matty is not verified by this server" to true, appCheckWords(VnAppWord("report", "unverified"), null, false))
        assertEquals("This phone's Matty is being checked by this server" to false, appCheckWords(VnAppWord("report", "none"), null, true))
        // Quiet while the server only reports; a warning while it requires.
        assertEquals("This phone's Matty has not been checked by this server yet" to false, appCheckWords(VnAppWord("report", "none"), null, false))
        assertEquals("This phone's Matty has not been checked by this server yet" to true, appCheckWords(VnAppWord("require", "none"), "  ", false))
        assertEquals("Not checked by this server yet. No key." to true, appCheckWords(VnAppWord("require", "none"), "No key.", false))
        // The server's reason for none: its rules changed since this Matty was checked.
        val changed = "This server's rules for the app check have changed, and this Matty is to be checked under the new ones."
        // It says why nothing is held, alone: this Matty may well have been checked before.
        assertEquals(changed to false, appCheckWords(VnAppWord("report", "none", changed), null, false))
        assertEquals(changed to true, appCheckWords(VnAppWord("require", "none", "  $changed "), "  ", false))
        // This phone's own reason comes first.
        assertEquals("Not checked by this server yet. No key." to true, appCheckWords(VnAppWord("require", "none", changed), "No key.", false))
    }

    @Test
    fun aRefusedMattyIsShownTheServersSentence() {
        val sentence = "Acme TV answers only the official Matty app, and this copy did not pass the check. This phone's bootloader is unlocked."
        assertEquals("Acme TV is for the official Matty app" to sentence, pendingWords("Acme TV", sentence))
        // Nothing at all is "Can't reach".
        assertEquals("Can't reach Acme TV" to "Check your connection, then try again.", pendingWords("Acme TV", null))
        // While this Matty's proof is on its way, the refusal is not shown: its answer is waited for.
        assertNull(pendingWords("Acme TV", sentence, proving = true))
        assertEquals("Can't reach Acme TV", pendingWords("Acme TV", null, proving = true)?.first)
    }

    @Test
    fun theAppKnowsItselfByAndroidsRecord() {
        val rsa = MattyCertificates.RSA_SHA256
        val ml = MattyCertificates.MLDSA_SHA256
        val official = SigningRecord(listOf(ml), listOf(rsa, ml), multipleSigners = false, currentIsMlDsa = true)
        assertEquals(SelfVerdict.Official, selfVerdictOf(false, official))
        assertEquals("the same, written in lower case", SelfVerdict.Official, selfVerdictOf(false, official.copy(current = listOf(ml.lowercase()), history = listOf(rsa.lowercase(), ml.lowercase()))))
        // A debug build says it is one, whatever signed it.
        assertEquals(SelfVerdict.Development, selfVerdictOf(true, official))
        assertEquals(SelfVerdict.Unreadable, selfVerdictOf(false, null))
        val other = "70:89:65:94:E5:82:2D:14:7B:54:3E:D6:DD:7B:C5:D6:31:5D:8E:2F:C8:57:BC:37:B7:D8:53:D7:70:5E:6E:B6"
        for (record in listOf(
            // Signed with the RSA key alone (a classical-only copy).
            SigningRecord(listOf(rsa), listOf(rsa), false, false),
            // Another key before Matty's in its history.
            SigningRecord(listOf(ml), listOf(other, rsa, ml), false, true),
            // The pair, but somebody else's.
            SigningRecord(listOf(other), listOf(rsa, other), false, false),
            SigningRecord(listOf(ml, rsa), emptyList(), true, false),
        )) assertEquals(record.toString(), SelfVerdict.NotOfficial, selfVerdictOf(false, record))
        assertEquals("This copy of Matty is not the official build.", selfWords(SelfVerdict.NotOfficial))
    }
}
