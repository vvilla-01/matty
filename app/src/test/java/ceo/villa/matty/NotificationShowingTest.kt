package ceo.villa.matty

import ceo.villa.matty.data.api.WsEvent
import ceo.villa.matty.data.session.SavedServer
import ceo.villa.matty.push.Clears
import ceo.villa.matty.push.PushPayload
import ceo.villa.matty.push.PushStore
import ceo.villa.matty.service.Kept
import ceo.villa.matty.service.LastLines
import ceo.villa.matty.service.NoteHead
import ceo.villa.matty.service.NoteKind
import ceo.villa.matty.service.NoteRules
import ceo.villa.matty.service.ShownPosts
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * How a notification is shown and how it goes: the rules that need no Android, and where the
 * notifier is joined to the rest (held by the source itself, as PushWiringTest holds push).
 */
class NotificationShowingTest {
    private class MemoryKept : Kept {
        var text: String? = null
        var writes = 0
        override fun read() = text
        override fun write(text: String) { this.text = text; writes++ }
    }

    // ---- what is remembered for the next process ------------------------------------------

    @Test fun aPostShownInOneProcessIsNotShownAgainByTheNext() {
        val kept = MemoryKept()
        assertTrue(ShownPosts(kept = kept).firstShow("s1", "p1"))
        val next = ShownPosts(kept = kept)
        assertTrue("the next process knows it", next.seen("s1", "p1"))
        assertFalse(next.firstShow("s1", "p1"))
        assertFalse(next.seen("s2", "p1"))
    }

    @Test fun askingWhetherAPostWasShownDoesNotShowIt() {
        val shown = ShownPosts()
        assertFalse(shown.seen("s1", "p1"))
        assertTrue("seen is only a look", shown.firstShow("s1", "p1"))
        assertTrue(shown.seen("s1", "p1"))
        assertFalse("an empty id is never seen", shown.seen("s1", ""))
    }

    @Test fun theShownPostsAreKeptForADay() {
        var now = 1_000_000L
        val kept = MemoryKept()
        ShownPosts(kept = kept, now = { now }).firstShow("s1", "old")
        now += ShownPosts.KEPT_MS + 1
        val next = ShownPosts(kept = kept, now = { now })
        assertFalse("older than a day is forgotten", next.seen("s1", "old"))
        assertTrue(next.firstShow("s1", "new"))
        assertFalse("what is written holds no post older than a day", kept.text!!.contains("old"))
    }

    @Test fun theShownPostsKeptAreBounded() {
        val kept = MemoryKept()
        val shown = ShownPosts(max = 2, kept = kept)
        listOf("a", "b", "c").forEach { shown.firstShow("s", it) }
        val next = ShownPosts(max = 2, kept = kept)
        assertFalse(next.seen("s", "a"))
        assertTrue(next.seen("s", "b") && next.seen("s", "c"))
    }

    @Test fun aClearIsRememberedByTheNextProcess() {
        val kept = MemoryKept()
        Clears(kept = kept).remember("s1", PushPayload.Clear("u", "c1", "", false, at = 100))
        val late = PushPayload.Message(
            user = "u", post = "p", channel = "c1", root = "", team = "t", sender = "b", senderName = "bob",
            channelName = "Town Square", channelType = "O", text = "hi", crt = false, idLoaded = false, fromWebhook = false, at = 90,
        )
        assertTrue(Clears(kept = kept).hides("s1", late))
        assertFalse(Clears(kept = kept).hides("s1", late.copy(at = 101)))
    }

    @Test fun aClearHidesByConversationAndTimeAsItHidesAPush() {
        val clears = Clears()
        clears.remember("s1", PushPayload.Clear("u", "c1", "", false, at = 100))
        clears.remember("s1", PushPayload.Clear("u", "c1", "root1", true, at = 200))
        assertTrue(clears.hides("s1", "c1", "", 100))
        assertFalse("posted after the clear", clears.hides("s1", "c1", "", 101))
        assertTrue("the collapsed thread is its own conversation", clears.hides("s1", "c1", "root1", 150))
        assertFalse(clears.hides("s1", "c1", "root2", 150))
        assertFalse("another server's", clears.hides("s2", "c1", "", 50))
        val inThread = PushPayload.Message(
            user = "u", post = "p", channel = "c1", root = "root1", team = "t", sender = "b", senderName = "bob",
            channelName = "Town Square", channelType = "O", text = "hi", crt = true, idLoaded = false, fromWebhook = false, at = 150,
        )
        assertEquals(clears.hides("s1", inThread), clears.hides("s1", "c1", Clears.threadRoot("root1", true), 150))
        assertTrue("without collapsed threads a reply is the channel's", clears.hides("s1", inThread.copy(crt = false, at = 100)))
    }

    @Test fun bothSetsLiveInTheAppsPushPreferences() {
        val prefs = MemoryPrefs()
        val store = PushStore(prefs, FakeSealer())
        assertTrue(ShownPosts(kept = store.shownKept).firstShow("s1", "p1"))
        Clears(kept = store.clearsKept).remember("s1", PushPayload.Clear("u", "c1", "", false, at = 100))
        val again = PushStore(prefs, FakeSealer())
        assertTrue(ShownPosts(kept = again.shownKept).seen("s1", "p1"))
        assertTrue(prefs.values.keys.containsAll(listOf("shown", "clears")))
        assertTrue((prefs.values["shown"] as String).contains("s1/p1"))
    }

    // ---- the lines a conversation was last posted with ---------------------------------------

    private val head = NoteHead("s1", "victor-id", "chan1", "", "team1", null, false)

    @Test fun theLinesLastPostedComeBeforeTheShade() {
        var now = 0L
        val lines = LastLines<String>(now = { now })
        assertSame("nothing posted here: the shade tells", LastLines.Recall.Unknown, lines.recall("k", showing = true))
        lines.put("k", head, listOf("a", "b"))
        now = 100
        val here = lines.recall("k", showing = false)
        assertTrue("just posted, not listed yet: still these lines", here is LastLines.Recall.Here && here.entry.lines == listOf("a", "b"))
        now = LastLines.SETTLE_MS + 200
        assertTrue(lines.recall("k", showing = true) is LastLines.Recall.Here)
        assertSame("posted a while ago and gone: dismissed, so it starts afresh", LastLines.Recall.Dismissed, lines.recall("k", showing = false))
        assertSame(LastLines.Recall.Unknown, lines.recall("k", showing = true))
    }

    @Test fun theLinesGoWithTheirNotification() {
        val lines = LastLines<String>()
        lines.put("s1/1", head, listOf("a"))
        lines.put("s2/1", head, listOf("b"))
        lines.forget("s1/1")
        assertSame(LastLines.Recall.Unknown, lines.recall("s1/1", showing = true))
        lines.forgetWhere { it.startsWith("s2/") }
        assertSame(LastLines.Recall.Unknown, lines.recall("s2/1", showing = true))
    }

    @Test fun theLinesAreBounded() {
        val lines = LastLines<String>(max = 2)
        listOf("a", "b", "c").forEach { lines.put(it, head, listOf(it)) }
        assertSame(LastLines.Recall.Unknown, lines.recall("a", showing = true))
    }

    // ---- Matty's own line, and a notification the person replied from -----------------------

    @Test fun couldNotSendIsMattysAndNeverCarriedOn() {
        assertTrue(NoteRules.isNotice("Couldn't send", NoteRules.NOTICE_KEY))
        assertFalse("the person may write those words themselves", NoteRules.isNotice("Couldn't send", "victor-id"))
        assertFalse(NoteRules.isNotice("Couldn't send", null))
        assertFalse(NoteRules.isNotice("hello", NoteRules.NOTICE_KEY))
    }

    @Test fun aNotificationRepliedFromIsHeldUntilPostedAgainQuietly() {
        val autoCancelHeld = 0x10 or NoteRules.HELD_BY_REPLY
        assertTrue(NoteRules.heldByReply(autoCancelHeld))
        assertFalse(NoteRules.heldByReply(0x10))
        val released = NoteRules.released(autoCancelHeld)
        assertFalse(NoteRules.heldByReply(released))
        assertEquals("auto-cancel kept, and only alerting once", 0x10 or 0x08, released)
    }

    /**
     * Android as it was seen to behave on Android 17 after a reply from the notification: the
     * cancel is ignored while the notification is held, and the first cancel that does go
     * through is followed by the held version put back.
     */
    private class Android17 : NoteRules.Showing {
        var flags: Int? = 0x10 or NoteRules.HELD_BY_REPLY
        var putBack = 1
        var cancels = 0
        override fun flags() = flags
        override suspend fun release() { flags = flags?.let { NoteRules.released(it) } }
        override fun cancel() {
            cancels++
            val now = flags ?: return
            if (NoteRules.heldByReply(now)) return
            flags = if (putBack-- > 0) 0x10 or 0x08 or NoteRules.HELD_BY_REPLY else null
        }
    }

    @Test fun aNotificationRepliedFromIsCancelledUntilItStaysGone() = kotlinx.coroutines.runBlocking {
        val once = Android17()
        once.cancel()
        assertEquals("a plain cancel does nothing to it", 1, once.cancels)
        assertTrue(once.flags != null)
        once.release(); once.cancel()
        assertTrue("let go of and cancelled once, it comes back", once.flags?.let(NoteRules::heldByReply) == true)

        val android = Android17()
        NoteRules.stayGone(android, stepMs = 1)
        assertNull("gone, and it stayed gone", android.flags)
        assertEquals(2, android.cancels)
    }

    @Test fun stayingGoneGivesUpInTime() = kotlinx.coroutines.runBlocking {
        val stuck = object : NoteRules.Showing {
            var looks = 0
            override fun flags(): Int { looks++; return 0x10 }
            override suspend fun release() = Unit
            override fun cancel() = Unit
        }
        NoteRules.stayGone(stuck, tries = 7, stepMs = 1)
        assertEquals(7, stuck.looks)
    }

    /** Android putting the held notification back a moment after it was gone (seen on the emulator). */
    private class BackAfterAnAbsence : NoteRules.Showing {
        var flags: Int? = 0x10 or NoteRules.HELD_BY_REPLY
        var cancels = 0
        var looksSinceCancel = 0
        private var putBack = true
        override fun flags(): Int? {
            looksSinceCancel++
            if (putBack && flags == null && looksSinceCancel == 2) { putBack = false; flags = 0x10 or NoteRules.HELD_BY_REPLY }
            return flags
        }
        override suspend fun release() { flags = flags?.let { NoteRules.released(it) } }
        override fun cancel() { cancels++; looksSinceCancel = 0; flags = null }
    }

    @Test fun aNotificationPutBackAfterItWasGoneIsCancelledAgain() = kotlinx.coroutines.runBlocking {
        val android = BackAfterAnAbsence()
        NoteRules.stayGone(android, stepMs = 1)
        assertNull("it came back and went again", android.flags)
        assertEquals(2, android.cancels)
        assertTrue("away five looks in a row after the last cancel", android.looksSinceCancel >= 5)
    }

    @Test fun aReplysNotificationStaysOnlyWhenSomethingWasPostedToItSinceTheActionBegan() {
        assertTrue("posted while the reply was on its way", NoteRules.keptAfterReply(postedAt = 2_000, since = 1_500))
        assertTrue(NoteRules.keptAfterReply(postedAt = 1_500, since = 1_500))
        assertFalse("posted before the action: the replied-from one", NoteRules.keptAfterReply(postedAt = 1_000, since = 1_500))
        assertFalse("read back from the shade, not posted here", NoteRules.keptAfterReply(postedAt = 0, since = 1_500))
        assertFalse("nothing showing", NoteRules.keptAfterReply(postedAt = null, since = 1_500))
    }

    @Test fun aThreadIsReadUpToTheLineShownElseThePhonesClock() {
        assertEquals(1_234, NoteRules.readUpTo(shownAt = 1_234, now = 9_999))
        assertEquals(9_999, NoteRules.readUpTo(shownAt = 0, now = 9_999))
    }

    private fun saved(id: String, name: String, username: String) =
        SavedServer(id = id, url = "https://$id.example.test", name = name, userId = "$username-id", username = username, sealedToken = "sealed")

    @Test fun withMoreThanOneServerANotificationSaysWhoseItIs() {
        val alice = saved("a", "Mattermost", "alice")
        assertNull("one server: as it was", NoteRules.whose("a", listOf(alice)))
        val work = saved("w", "Work", "alice")
        assertEquals("Mattermost", NoteRules.whose("a", listOf(alice, work)))
        assertEquals("Work", NoteRules.whose("w", listOf(alice, work)))
        val carol = saved("c", "mattermost", "carol")
        assertEquals("the same name twice: the account too", "@alice · Mattermost", NoteRules.whose("a", listOf(alice, work, carol)))
        assertEquals("@carol · mattermost", NoteRules.whose("c", listOf(alice, work, carol)))
        assertEquals("Work", NoteRules.whose("w", listOf(alice, work, carol)))
        assertNull("not saved", NoteRules.whose("x", listOf(alice, work)))
        val alsoAlice = saved("b", "Mattermost", "Alice")
        assertEquals("the same name and the same username: the server by its host", "@alice · a.example.test", NoteRules.whose("a", listOf(alice, alsoAlice)))
        assertEquals("@Alice · b.example.test", NoteRules.whose("b", listOf(alice, alsoAlice)))
        assertEquals("only with the one that has both", "@carol · mattermost", NoteRules.whose("c", listOf(alice, alsoAlice, carol)))
        assertEquals("@alice · a.example.test", NoteRules.whose("a", listOf(alice, alsoAlice, carol)))
    }

    @Test fun theSignedOutNoticeSaysWhoseItIsWhenAnotherServerHasItsName() {
        val alice = saved("a", "Mattermost", "alice")
        val work = saved("w", "Work", "alice")
        assertEquals("Signed out of Mattermost", NoteRules.signedOutTitle(alice, listOf(alice)))
        assertEquals("no twin: as it was", "Signed out of Mattermost", NoteRules.signedOutTitle(alice, listOf(alice, work)))
        val carol = saved("c", "mattermost", "carol")
        assertEquals("Signed out of Mattermost (@alice)", NoteRules.signedOutTitle(alice, listOf(alice, work, carol)))
        assertEquals("Signed out of mattermost (@carol)", NoteRules.signedOutTitle(carol, listOf(alice, work, carol)))
        assertEquals("already gone from the list", "Signed out of Mattermost", NoteRules.signedOutTitle(alice, listOf(work)))
    }

    @Test fun aLiveMessageInAChannelNotKnownYetTakesItsKindFromTheEvent() {
        assertEquals("a direct message: no title, not a group", NoteKind(null, false), NoteRules.unknownChannel("D", "@dave"))
        assertEquals("a group message", NoteKind("alice, dave", true), NoteRules.unknownChannel("G", "alice, dave"))
        assertEquals("anything else as before", NoteKind("Town Square", true), NoteRules.unknownChannel("O", "Town Square"))
        assertEquals(NoteKind("", true), NoteRules.unknownChannel(null, null))
    }

    @Test fun aSignedOutNoticeIsKnownByItsAddressWithoutCase() {
        assertTrue(NoteRules.sameAddress("https://Chat.Example.test", "https://chat.example.test"))
        assertTrue(NoteRules.sameAddress("https://chat.example.test/", "https://chat.example.test"))
        assertFalse(NoteRules.sameAddress("https://chat.example.test", "https://other.example.test"))
        assertFalse("a notice with no address is another kind", NoteRules.sameAddress(null, "https://chat.example.test"))
    }

    // ---- a thread read on the live connection ---------------------------------------------

    private fun event(name: String, user: String, unread: Long?, thread: String? = "root1") = WsEvent(
        name,
        buildJsonObject {
            thread?.let { put("thread_id", it) }
            unread?.let { put("unread_replies", JsonPrimitive(it)) }
        },
        buildJsonObject { put("user_id", user) },
        seq = 1,
    )

    @Test fun aThreadReadWithNothingUnreadLosesItsNotification() {
        assertEquals("root1", NoteRules.threadRead(event("thread_read_changed", "victor-id", 0), "victor-id"))
        assertNull("still unread (marked unread)", NoteRules.threadRead(event("thread_read_changed", "victor-id", 2), "victor-id"))
        assertNull("someone else's", NoteRules.threadRead(event("thread_read_changed", "alice-id", 0), "victor-id"))
        assertNull(NoteRules.threadRead(event("thread_updated", "victor-id", 0), "victor-id"))
        assertNull("every thread at once names none", NoteRules.threadRead(event("thread_read_changed", "victor-id", 0, thread = null), "victor-id"))
        assertNull(NoteRules.threadRead(event("thread_read_changed", "victor-id", null), "victor-id"))
    }

    // ---- where it is joined -----------------------------------------------------------------

    private val main = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "app/src/main/java/ceo/villa/matty") }.first { it.isDirectory }
    private val notifier = File(main, "service/Notifier.kt").readText()
    private val center = File(main, "push/PushCenter.kt").readText()
    private val root = File(main, "ui/MattyRoot.kt").readText()
    private val app = File(main, "MattyApp.kt").readText()

    private fun inOrder(text: String, vararg parts: String) {
        var at = -1
        for (p in parts) {
            val next = text.indexOf(p, at + 1)
            assertTrue("'$p' after what comes before it", next > at)
            at = next
        }
    }

    /** [text] before [anchor], which must be there. */
    private fun before(text: String, anchor: String): String {
        assertTrue("'$anchor' is there", anchor in text)
        return text.substringBefore(anchor)
    }

    /** [text] after [anchor], which must be there. */
    private fun after(text: String, anchor: String): String {
        assertTrue("'$anchor' is there", anchor in text)
        return text.substringAfter(anchor)
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

    @Test fun aSuccessfulReplyPostsTheNotificationAgainThenCancelsIt() {
        val act = body(notifier, "private suspend fun act(")
        assertTrue("NotificationActions.Outcome.Done -> true" in act)
        val receive = body(notifier, "override fun onReceive(")
        inOrder(receive, "val since = System.currentTimeMillis()", "goAsync()", "act(c, head, text, shownAt)", "else if (done)", "c.notifier.replied(head, text, since)")
        val replied = body(notifier, "suspend fun replied(")
        inOrder(replied, "withLock", "current(tag, id, head)", "NoteRules.keptAfterReply(entry?.shownAt, since)", "kept || record(tag, id) != null",
            "post(entry.head", "quiet = true", "if (kept) return@withLock", "awaitReleased(tag, id)", "posted.forget", "gone(tag, id)")
    }

    @Test fun everyWayANotificationGoesLetsGoOfAHeldOneFirst() {
        assertTrue("lockOf(tag, id).withLock { removeLocked(tag, id) }" in after(notifier, "private suspend fun remove(tag: String?, id: Int) ="))
        inOrder(body(notifier, "private suspend fun removeLocked("), "posted.forget", "heldByReply", "gone(tag, id)", "nm.cancel(tag, id)")
        inOrder(after(notifier, "private suspend fun gone("), "NoteRules.stayGone", "release(tag, id, it)", "nm.cancel(tag, id)")
        inOrder(body(notifier, "private suspend fun release("), "NoteRules.released", "nm.notify(tag, id, copy)", "awaitReleased(tag, id)")
        inOrder(body(notifier, "private fun clearWhere("), "heldByReply", "remove(sbn.tag, sbn.id)", "nm.cancel(sbn.tag, sbn.id)")
        assertTrue("a clear goes through the same cancel", "cancel(serverId, NoteIdentity.conversation(" in body(notifier, "suspend fun cleared("))
    }

    @Test fun aConversationIsReadAndPostedUnderItsLock() {
        val show = body(notifier, "suspend fun show(note: MessageNote, pushed: Boolean = false)")
        inOrder(show, "shown.seen(note.serverId, note.postId)", "loadAvatar", "withLock",
            "if (pushed && clears.hides(note.serverId, note.channelId, note.threadRoot, note.at)) return@withLock",
            "if (!shown.firstShow(note.serverId, note.postId)) return@withLock", "current(tag, id", "post(note.head")
        assertFalse("counted as shown only under the lock, as it is posted", "firstShow" in before(show, "withLock"))
        assertTrue("a push's show looks for a clear once more", "show(draft.copy(avatar = avatar), pushed = true)" in body(notifier, "suspend fun showPush("))
        val current = body(notifier, "private fun current(")
        inOrder(current, "posted.recall", "extractMessagingStyleFromNotification", "NoteRules.isNotice")
        val post = body(notifier, "private fun post(")
        assertTrue("what was posted is remembered", "posted.put(key(tag, id), head, lines, shown)" in post)
        assertFalse("Matty's own line is never remembered", post.lines().single { "posted.put(" in it }.contains("notice"))
    }

    @Test fun aPushIsCheckedBeforeAnythingIsAskedOfItsServer() {
        val showPush = body(notifier, "suspend fun showPush(")
        inOrder(showPush, "clears.hides", "shown.seen", "viewing(", "open()", "PushNotes.idLoaded")
        assertTrue("no picture without the push's own client", "if (one == null) null" in showPush)
        val show = body(center, "override suspend fun arrived(")
        assertTrue("the client is opened only when the notifier asks", "c.notifier.showPush(server, arrival.message, arrival.silent) {" in show)
        assertTrue("c.notifier.cleared(serverId, arrival.clear)" in show)
    }

    @Test fun theRepostIsBuiltAfreshWithItsActionsAndMattysOwnLine() {
        val couldNot = body(notifier, "suspend fun couldNotSend(")
        inOrder(couldNot, "NoteRules.NOTICE_KEY", "post(entry.head, entry.lines", "notice =")
        assertFalse("never a copy of the old one", "Builder(app, old)" in notifier)
        val post = body(notifier, "private fun post(")
        inOrder(post, "\"Reply\"", "\"Mark as read\"", ".setAuthenticationRequired(true)")
        assertTrue(".setVisibility(NotificationCompat.VISIBILITY_PRIVATE)" in post)
        assertTrue("the tap names the thread", "putExtra(EXTRA_ROOT_ID, head.threadRoot)" in before(post, "val replyInput"))
        assertTrue("the actions name the team", "putExtra(EXTRA_TEAM_ID, head.team)" in post)
        assertFalse("no shortcut is published, so none is named", "setShortcutId" in notifier)
    }

    @Test fun anActionNeverDropsAReplyForAServerStillSaved() {
        val receive = body(notifier, "override fun onReceive(")
        inOrder(receive, "intent.data == null", "c.notifier.removeUnnamed(channelId)", "return", "NoteIdentity.data(serverId, head.conversation)")
        val act = body(notifier, "private suspend fun act(")
        inOrder(act, "if (one == null)", "c.sessionStore.servers.value.any { it.id == serverId }", "c.notifier.couldNotSend(head)", "else c.notifier.cancel(")
        assertTrue("a thread is marked as a thread", "head.threadRoot, head.team, shownAt)" in act)
    }

    @Test fun theLiveConnectionClearsAThreadReadAndATapOpensIt() {
        assertTrue("NoteRules.threadRead(e, repo.myId)" in body(notifier, "fun attach(repo: ChatRepository)"))
        inOrder(body(root, "private fun handleIntent("), "nav.openChannel(channelId)", "Notifier.EXTRA_ROOT_ID", "nav.openThread(it)")
        inOrder(root, "is Route.Thread ->", "notifier.cancel(nav.repo.server.id, route.rootId)", "ThreadScreen(nav, route.rootId)")
    }

    @Test fun shownPostsAndClearsOutliveTheProcessAndTheFetchIsBounded() {
        assertTrue("ShownPosts(kept = PushStore.of(app).shownKept)" in notifier)
        assertTrue("Clears(kept = PushStore.of(app).clearsKept)" in notifier)
        assertTrue("held to ID_LOADED_MS", "PushNotes.idLoaded(one.client, m)" in body(notifier, "suspend fun showPush("))
    }

    @Test fun markAsReadCarriesTheTimeOfTheNewestLineShown() {
        val post = body(notifier, "private fun post(")
        inOrder(post, "val shownAt = lines.filter { it.person != null }.maxOfOrNull { it.timestamp } ?: 0L",
            "fun action(name: String)", "putExtra(EXTRA_AT, shownAt)", "action(ACTION_READ)")
        val receive = body(notifier, "override fun onReceive(")
        inOrder(receive, "intent.getLongExtra(Notifier.EXTRA_AT, 0L)", "act(c, head, text, shownAt)")
        assertTrue("head.threadRoot, head.team, shownAt)" in body(notifier, "private suspend fun act("))
    }

    @Test fun markAsReadKeepsAMessageThatCameWhileItWasOnItsWay() {
        val receive = body(notifier, "override fun onReceive(")
        inOrder(receive, "else if (done)", "c.notifier.replied(head, text, since)", "else c.notifier.markedRead(head, since)")
        val read = body(notifier, "suspend fun markedRead(head: NoteHead, since: Long)")
        inOrder(read, "lockOf(tag, id).withLock", "if (NoteRules.keptAfterReply(current(tag, id, head)?.shownAt, since)) return@withLock", "removeLocked(tag, id)")
    }

    @Test fun aReplyOrMarkTheServerTookNeverEndsInCouldNotSend() {
        // The receiver needs Android: its source is checked. Only the sending is boxed by WORK_MS;
        // a Done is seen to afterwards, held to the broadcast's time, and never followed by "Couldn't send".
        val receive = body(notifier, "override fun onReceive(")
        val box = "val done = withTimeoutOrNull(WORK_MS) { act(c, head, text, shownAt) }"
        inOrder(receive, box, "if (done == null) c.notifier.couldNotSend(head)",
            "else if (done) withTimeoutOrNull(deadline.leftMs() + (FINISH_MS - REQUESTS_MS)) {",
            "c.notifier.replied(head, text, since)", "else c.notifier.markedRead(head, since)")
        assertFalse("nothing after a Done inside the box", "replied(" in body(notifier, "private suspend fun act(") || "markedRead(" in body(notifier, "private suspend fun act("))
        assertFalse("never a fallback that follows a Done", "?: c.notifier.couldNotSend(head)" in receive)
        assertEquals("one \"Couldn't send\" in the receiver's own code", 1, Regex("couldNotSend\\(").findAll(receive).count())
        assertTrue("under Android's 10 s", "const val FINISH_MS = 9_500L" in notifier)
    }

    @Test fun aReplyGoesUnderTheIdOfItsNotificationAndText() {
        inOrder(body(notifier, "private suspend fun act("), "NotificationActions.reply(one.client, one.server.userId, head.channelId, head.threadRoot, text,",
            "NotificationActions.replyId(serverId, one.server.userId, head.conversation, shownAt, text))")
    }

    @Test fun onlyAMessageShownMakesANotificationStayAfterAReplyOrAMark() {
        var now = 1_000L
        val lines = LastLines<String>(now = { now })
        lines.put("k", head, listOf("from alice"), shown = true)
        now = 6_000
        // "Couldn't send" for another action, or a reply's own line, posted again from here.
        lines.put("k", head, listOf("from alice"))
        val entry = (lines.recall("k", showing = true) as LastLines.Recall.Here).entry
        assertEquals("posted again", 6_000L, entry.at)
        assertEquals("the message was shown before", 1_000L, entry.shownAt)
        assertFalse("a mark begun at 4 s takes the notification away", NoteRules.keptAfterReply(entry.shownAt, since = 4_000))
        now = 7_000
        lines.put("k", head, listOf("from alice", "from bob"), shown = true)
        val again = (lines.recall("k", showing = true) as LastLines.Recall.Here).entry
        assertTrue("a message shown after it began keeps it", NoteRules.keptAfterReply(again.shownAt, since = 4_000))
        assertEquals("never shown here", 0L, LastLines<String>().apply { put("j", head, listOf("x")) }.let { (it.recall("j", true) as LastLines.Recall.Here).entry.shownAt })
        // Only show posts as a message shown; Matty's own posts again do not.
        assertTrue("post(note.head, noteLines(earlier, line), silent = note.silent, shown = true)" in body(notifier, "suspend fun show(note: MessageNote, pushed: Boolean = false)"))
        assertFalse("shown = true" in body(notifier, "suspend fun couldNotSend(") || "shown = true" in body(notifier, "suspend fun replied("))
        assertEquals("one post shows a message", 1, Regex("shown = true\\)").findAll(notifier).count())
    }

    @Test fun aLiveMessageInAChannelNotKnownYetIsAskedOfTheEvent() {
        val noteFor = body(notifier, "private suspend fun noteFor(")
        inOrder(noteFor, "ch == null -> NoteRules.unknownChannel(e.data.str(\"channel_type\"), e.data.str(\"channel_display_name\"))",
            "title = kind.title, group = kind.group")
    }

    @Test fun aMessageNotificationSaysWhoseItIs() {
        assertTrue(".setSubText(NoteRules.whose(head.serverId, c.sessionStore.servers.value))" in body(notifier, "private fun post("))
    }

    @Test fun signingInAgainTakesTheSignedOutNoticeAway() {
        inOrder(body(notifier, "fun showEnded(server: SavedServer)"), "clearSignedOut(server.url)",
            "notice(server, NoteRules.signedOutTitle(server, c.sessionStore.servers.value)", "signedOut = server.url")
        val clear = body(notifier, "private fun clearSignedOut(")
        inOrder(clear, "NoteIdentity.isNotice(it.tag)", "NoteRules.sameAddress(it.notification.extras?.getString(EXTRA_SIGNED_OUT), address)", "nm.cancel(it.tag, it.id)")
        assertTrue("addExtras(Bundle().apply { putString(EXTRA_SIGNED_OUT, it) })" in body(notifier, "private fun notice("))
        assertTrue("fun signedIn(address: String) = clearSignedOut(address)" in notifier)
        inOrder(body(app, "fun signedIn(url: String"), "c.sessionStore.upsert(server)", "c.notifier.signedIn(clean)", "activate(server)")
    }

    @Test fun theChannelSaysWhatItCarries() {
        assertTrue("NotificationChannel(CH_MESSAGES, \"Messages\"" in notifier)
        assertTrue("description = \"Messages your server notifies you about\"" in notifier)
    }
}
