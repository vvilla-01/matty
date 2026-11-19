package ceo.villa.matty

import ceo.villa.matty.push.Paced
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A server whose session ended while the app runs, and the app coming back to the front. The
 * session manager needs the whole app, so where it is joined is held by the source itself (as
 * StartPathsTest does); the rules are tested as functions.
 */
class EndedServerTest {
    private val main = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "app/src/main/java/ceo/villa/matty") }.first { it.isDirectory }

    private val app = File(main, "MattyApp.kt").readText()
    private val center = File(main, "push/PushCenter.kt").readText()

    private fun inOrder(text: String, vararg parts: String) {
        var at = -1
        for (p in parts) {
            val next = text.indexOf(p, at + 1)
            assertTrue("'$p' after what comes before it", next > at)
            at = next
        }
    }

    private fun body(text: String, signature: String): String {
        val start = text.indexOf(signature).also { assertTrue("$signature is there", it >= 0) }
        val open = text.indexOf('{', start)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) { '{' -> depth++; '}' -> if (--depth == 0) return text.substring(open, i + 1) }
        }
        error("unbalanced $signature")
    }

    @Test fun whatEveryServerSharesIsWipedOnlyWhenNoOtherIsLive() {
        assertTrue("at the start", SessionManager.wipesAll(atStart = true, otherLive = false))
        assertTrue("the live server ended", SessionManager.wipesAll(atStart = false, otherLive = false))
        assertFalse("another server is live", SessionManager.wipesAll(atStart = false, otherLive = true))
        val drop = body(app, "private fun dropEnded(")
        inOrder(
            drop, "teardown(live); _current.value = null",
            "val all = wipesAll(atStart = only == null, otherLive = _current.value != null)",
            "if (all) c.wipeSessionData() else c.wipeServerData()",
        )
    }

    @Test fun theSharedWipeIsOwedToTheNextStart() {
        val own = body(app, "fun wipeServerData()")
        assertTrue("wipeServersGone()" in own)
        for (shared in listOf("Drafts", "ShareInbox", "wipeDiskCaches", "CookieManager", "imageLoader", "videoStills", "wipeSessionData")) {
            assertFalse("$shared is not touched while another server is live", shared in own)
        }
        assertTrue("devicePrefs.edit().putBoolean(KEY_WIPE_OWED, true).commit()" in body(app, "fun oweSharedWipe()"))
        // Owed on disk before the servers are taken out of the store, whichever wipe follows.
        inOrder(body(app, "private fun dropEnded("), "if (gone.isNotEmpty()) {", "c.oweSharedWipe()", "gone.forEach { c.sessionStore.remove(it.id) }", "if (all) c.wipeSessionData() else c.wipeServerData()")
        inOrder(body(app, "private fun wipeServersGone()"), "forgetNameColors()", "notifier.clearDropped(")
        // Settled, and the disk wipe it starts flagged, in one edit.
        inOrder(body(app, "fun wipeSessionData()"), "devicePrefs.edit().remove(KEY_WIPE_OWED).putBoolean(\"wipe_pending\", true).apply()", "Drafts.map.clear()", "ShareInbox.clear()", "wipeServersGone()")
        assertTrue("if (devicePrefs.getBoolean(KEY_WIPE_OWED, false)) wipeSessionData()" in body(app, "fun wipeOwed()"))
        // At the start, before anything is live.
        inOrder(body(app, "fun ensureActive("), "if (first) {", "dropEnded()", "c.wipeOwed()", "chooseStart(")
    }

    @Test fun anEndedServersKeptMessagesGoAtOnceWhileAnotherIsLive() {
        // The folders of servers no longer saved, by the rule the cache itself uses.
        assertEquals("s-1_a", AppContainer.chatFolder("s-1_a"))
        assertEquals("s1", AppContainer.chatFolder("s/1"))
        assertEquals(null, AppContainer.chatFolder("../"))
        assertEquals(listOf("gone", "x"), AppContainer.chatFoldersGone(listOf("live", "gone", "s1", "x"), listOf("live", "s/1", "../")))
        assertEquals(emptyList<String>(), AppContainer.chatFoldersGone(emptyList(), listOf("live")))
        assertEquals(listOf("a", "b"), AppContainer.chatFoldersGone(listOf("a", "b"), emptyList()))
        assertTrue("val safe = chatFolder(serverId) ?: return null" in body(app, "fun chatCache(serverId: String)"))
        // Off the main thread: listed first, then the servers saved read, then the rest deleted.
        val gone = body(app, "private fun wipeServersGone()")
        inOrder(
            gone, "appScope.launch(Dispatchers.IO) {", "val folders = chatsDir.list()?.toList().orEmpty()",
            "val saved = sessionStore.servers.value.map { it.id }",
            "for (name in chatFoldersGone(folders, saved)) runCatching { File(chatsDir, name).deleteRecursively() }",
        )
        assertTrue("wipeServersGone()" in body(app, "fun wipeServerData()"))
    }

    @Test fun aServerThatCannotBeStartedStopsTheConnectionToo() {
        assertTrue("if (server == null || !activate(server)) ConnectionService.sync(c.app, false)" in body(app, "private fun activateOrStop("))
        assertFalse("never an activate whose false is lost", "?.let { activate(it) } ?:" in app)
        assertTrue("activateOrStop(c.sessionStore.active)" in body(app, "fun signOut()"))
        assertTrue("activateOrStop(c.sessionStore.active)" in body(app, "private suspend fun confirmSessionEnded("))
        assertTrue("activateOrStop(c.sessionStore.active)" in body(app, "private fun dropEnded("))
    }

    @Test fun comingBackToTheAppPaysWhatPushOwes() {
        inOrder(body(app, "fun onForeground("), "if (fg) c.push.cameToFront()", "val repo = _current.value ?: return")
        val front = body(center, "fun cameToFront()")
        inOrder(front, "if (!c.sessions.started.value) return", "if (!store.enabled) return@launch", "val canRegister = chosen != null && usable(chosen)",
            "c.sessionStore.servers.value", "PushPlan.askedAtFront(",
            "owes = store.side(server.id).owes, usable = canRegister,",
            "registered = chosen?.let { store.registration(server.id, it) } != null, stopped = store.stop(server.id) != null,",
            "offer = store.side(server.id).offer,",
            "if (!asked || !fronted.due(server.id)) continue", "sync(server, OneOff.ACTION_SECONDS, askAgain = false)")
        // The start asks every server itself: the app coming to the front just after it does not ask again.
        inOrder(body(center, "fun onStart()"), "fronted.mark(server.id)", "sync(server, OneOff.ACTION_SECONDS, askAgain = false)")
        assertTrue("private val fronted = Paced(FRONT_EVERY_MS)" in center)
        assertTrue("const val FRONT_EVERY_MS = 30_000L" in center)
    }

    @Test fun aServerIsAskedAtMostOnceIn30SecondsBecauseTheAppCameBack() {
        var now = 1_000_000L
        val paced = Paced(30_000) { now }
        assertTrue(paced.due("a"))
        assertFalse("just asked", paced.due("a"))
        assertTrue("another server is its own", paced.due("b"))
        now += 29_999
        assertFalse(paced.due("a"))
        now += 1
        assertTrue(paced.due("a"))
        assertFalse(paced.due("a"))
        paced.mark("c")
        assertFalse("asked at the start", paced.due("c"))
        now += 30_000
        assertTrue(paced.due("c"))
        assertEquals("a clock that went back never makes it due early", false, Paced(30_000) { now }.let { p -> p.due("d"); now -= 5_000; p.due("d") })
    }
}
