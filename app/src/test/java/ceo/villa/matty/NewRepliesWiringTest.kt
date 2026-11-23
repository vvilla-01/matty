package ceo.villa.matty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where the pills for new replies and new messages are joined to their screens, held by the source
 * itself (as FollowNewestTest and VoiceWiringTest hold theirs): the thread draws its pill over the
 * reply box, placed as the channel's pill is; the channel keeps its own count per conversation, fed
 * from FollowNewest, and its pill shows on a number or far up, placed, dropped and risen as before;
 * and each number is written only where the list's state is known.
 */
class NewRepliesWiringTest {
    private val chat = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "app/src/main/java/ceo/villa/matty/ui/chat") }.first { it.isDirectory }

    private val thread = File(chat, "ThreadScreen.kt").readText()
    private val channel = File(chat, "ChatScreen.kt").readText()
    private val replies = File(chat, "NewReplies.kt").readText()

    /** The lines of [text] that are code, without the comments. */
    private fun code(text: String): List<String> = text.lines().map { it.trim() }
        .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
        .map { it.substringBefore("//").trim() }
        .filter { it.isNotEmpty() }

    private fun body(text: String, signature: String): String {
        val start = text.indexOf(signature).also { assertTrue("$signature is there", it >= 0) }
        // The brace that ends the signature's last line, past any default lambdas in it.
        val open = text.indexOf("{\n", start)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) { '{' -> depth++; '}' -> if (--depth == 0) return text.substring(open, i + 1) }
        }
        error("unbalanced $signature")
    }

    /** The index of the code line of [lines] that holds [part]; there is exactly one. */
    private fun line(lines: List<String>, part: String): Int {
        val at = lines.indices.filter { part in lines[it] }
        assertEquals("one line with $part", 1, at.size)
        return at.single()
    }

    @Test
    fun theThreadDrawsThePillOverTheReplyBoxAsTheChannelPlacesItsOwn() {
        val screen = code(body(thread, "fun ThreadScreen("))
        val dock = line(screen, "if (root != null) ComposerDock(")
        val pill = line(screen, "if (ids != null && !(failed && root == null)) NewRepliesPill(newReplies, threadList,")
        assertTrue("drawn after the dock, so over it", pill > dock)
        assertEquals("Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = dock + 8.dp + bar).offset { IntOffset(0, drop.roundToPx()) })", screen[pill + 1])
        assertTrue(screen.contains("if (scrollAway.hidden) (dock - navBottom).coerceAtLeast(0.dp) else 0.dp,"))
        assertTrue(screen.contains("val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()"))
        assertTrue("the thread's own count", screen.contains("val newReplies = rememberNewReplies(rootId, threadList, rows, repo.myId)"))
        val follow = line(screen, "FollowNewest(threadList, rootId, rows, repo.myId) { newest, followed ->")
        assertEquals("newReplies.decided(newest, followed, threadList.firstVisibleKey() as? String)", screen[follow + 1])
    }

    @Test
    fun theNumberIsWrittenOnlyWhereTheListsStateIsKnown() {
        val lines = code(replies)
        // One place writes the number: by place for a channel, by time for a thread.
        assertEquals(
            listOf("count = if (byPlace) newMessagesBelow(replies, seen, myId) else newRepliesBelow(replies, seen, myId)"),
            lines.filter { it.startsWith("count = ") },
        )
        val update = code(body(replies, "private fun update("))
        // Nothing is written while a new newest reply waits for the list's decision; an emptied thread is written.
        assertEquals(listOf("val newest = replies.firstOrNull()", "if (newest != null && newest.id != decided) return"), update.subList(1, 3))
        val remembered = code(body(replies, "internal fun rememberNewReplies("))
        assertTrue(remembered.contains("androidx.compose.runtime.snapshotFlow { state.firstVisibleItemIndex to state.layoutInfo.totalItemsCount }"))
        assertTrue(remembered.contains(".collect { newReplies.moved(state.firstVisibleKey() as? String) }"))
        // The list decides before it moves, in the one place that follows a new newest message.
        val follow = code(body(channel, "internal fun FollowNewest("))
        assertTrue(follow.indexOf("onDecided(newest, follows)") in 0 until follow.indexOf("state.animateScrollToItem(0)"))
        val pill = code(body(replies, "internal fun NewRepliesPill("))
        assertTrue(pill.contains("newReplies.count > 0, newReplies.words, modifier,"))
        assertTrue(pill.contains("actionLabel = \"Jump to the newest reply\", role = Role.Button, announce = true,"))
        assertTrue(pill.contains(") { scope.launch { state.glideToPresent() } }"))
    }

    @Test
    fun theChannelCountsItsOwnNewMessagesFedFromFollowNewest() {
        val screen = code(body(channel, "fun ChatScreen("))
        assertTrue("one list per conversation", screen.contains("val listState = remember(ch.id) { androidx.compose.foundation.lazy.LazyListState() }"))
        // A channel's list is in the order the phone received it, so it counts by place.
        val count = line(screen, "val newMessages = rememberNewReplies(ch.id, listState, rows, repo.myId, ::newMessagesWords, byPlace = true)")
        val follow = line(screen, "FollowNewest(listState, ch.id, rows, repo.myId) { newest, followed ->")
        assertTrue("the count is there before the list decides", count < follow)
        assertEquals("newMessages.decided(newest, followed, listState.firstVisibleKey() as? String)", screen[follow + 1])
        // Only what is a row of the list counts: a reply folded into its thread is not one.
        val remembered = code(body(replies, "internal fun rememberNewReplies("))
        assertTrue(remembered.contains("val newReplies = remember(key) { NewReplies(myId, wordsFor, byPlace) }"))
        assertTrue(remembered.contains("val replies = remember(rows) { rows.map { it.post } }"))
        assertTrue(remembered.contains("SideEffect { newReplies.replies = replies }"))
    }

    @Test
    fun theChannelsPillShowsOnANumberOrFarUpAndIsPlacedAsItWas() {
        val screen = code(body(channel, "fun ChatScreen("))
        val dock = line(screen, "ComposerDock(inView && !scrollAway.hidden, Modifier.align(Alignment.BottomCenter), onHeight = { dock = it }) {")
        val call = line(screen, "JumpToPresent(listState, newMessages, inView, dock, Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = dock + 8.dp + bar)")
        assertTrue("drawn after the dock, so over it", call > dock)
        assertEquals(".offset { androidx.compose.ui.unit.IntOffset(0, drop.roundToPx()) })", screen[call + 1])
        assertTrue(screen.contains("if (scrollAway.hidden) (dock - navBottom).coerceAtLeast(0.dp) else 0.dp,"))
        val jump = code(body(channel, "private fun JumpToPresent("))
        assertEquals(
            listOf(
                "val farUp by remember(state) { derivedStateOf { state.firstVisibleItemIndex > 8 } }",
                "val show = newMessages.count > 0 || farUp",
                // What it last said is the pill's own, not the conversation's: it fades out with it on a switch too.
                "var said by remember { mutableStateOf(PillWords(\"Jump to present\", counting = false)) }",
                "val says = channelPillWords(show, newMessages.count, newMessages.words, said)",
                "androidx.compose.runtime.SideEffect { if (show) said = says }",
            ),
            jump.subList(2, 7),
        )
        assertTrue(jump.contains("rise.animateTo(if (inView) 1f else 0f, androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow))"))
        assertEquals(
            listOf("NewestPill(", "show, says.words,",
                "modifier.graphicsLayer { translationY = (1f - rise.value) * with(density) { dock.toPx() }; alpha = rise.value },",
                "enabled = inView,",
                "actionLabel = if (says.counting) \"Jump to the newest message\" else null,",
                "role = if (says.counting) androidx.compose.ui.semantics.Role.Button else null, announce = says.counting && inView,",
                ") { scope.launch { state.glideToPresent() } }"),
            jump.dropWhile { it != "NewestPill(" }.dropLast(1),
        )
        // Left to its defaults, the shared pill has no label, role or live region: "Jump to present" as it is.
        assertTrue(channel.contains("actionLabel: String? = null, role: androidx.compose.ui.semantics.Role? = null, announce: Boolean = false,"))
        val look = code(body(channel, "internal fun NewestPill("))
        assertTrue(look.contains(".then(if (announce) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)"))
        assertTrue(look.contains(".clickable(enabled = enabled, onClickLabel = actionLabel, role = role, onClick = onClick)"))
        assertTrue(look.contains("enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut(),"))
    }
}
