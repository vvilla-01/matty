package ceo.villa.matty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where a thread's pill for new replies is joined to the screen, held by the source itself (as
 * FollowNewestTest and VoiceWiringTest hold theirs): the thread draws it over the reply box, placed
 * as the channel's pill is, its number is written only where the list's state is known, and the
 * channel's "Jump to present" is called and drawn as it was.
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
        val follow = line(screen, "FollowNewest(threadList, rootId, rows.firstOrNull()?.post, repo.myId) { newest, followed ->")
        assertEquals("newReplies.decided(newest, followed, threadList.firstVisibleKey() as? String)", screen[follow + 1])
    }

    @Test
    fun theNumberIsWrittenOnlyWhereTheListsStateIsKnown() {
        val lines = code(replies)
        assertEquals(listOf("count = newRepliesBelow(replies, seen, myId)"), lines.filter { it.startsWith("count = ") })
        val update = code(body(replies, "private fun update("))
        // Nothing is written while a new newest reply waits for the list's decision; an emptied thread is written.
        assertEquals(listOf("val newest = replies.firstOrNull()", "if (newest != null && newest.id != decided) return"), update.subList(1, 3))
        val remembered = code(body(replies, "internal fun rememberNewReplies("))
        assertTrue(remembered.contains("androidx.compose.runtime.snapshotFlow { state.firstVisibleItemIndex to state.layoutInfo.totalItemsCount }"))
        assertTrue(remembered.contains(".collect { newReplies.moved(state.firstVisibleKey() as? String) }"))
        // The list decides before it moves, in the one place that follows a new newest message.
        val follow = code(body(channel, "internal fun FollowNewest("))
        assertTrue(follow.indexOf("onDecided(newest, follows)") in 0 until follow.indexOf("if (follows) state.animateScrollToItem(0)"))
        val pill = code(body(replies, "internal fun NewRepliesPill("))
        assertTrue(pill.contains("newReplies.count > 0, newReplies.words, modifier,"))
        assertTrue(pill.contains("actionLabel = \"Jump to the newest reply\", role = Role.Button, announce = true,"))
        assertTrue(pill.contains(") { scope.launch { state.glideToPresent() } }"))
    }

    @Test
    fun theChannelsJumpToPresentIsAsItWas() {
        val screen = code(body(channel, "fun ChatScreen("))
        val call = line(screen, "JumpToPresent(listState, inView, dock, Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = dock + 8.dp + bar)")
        assertEquals(".offset { androidx.compose.ui.unit.IntOffset(0, drop.roundToPx()) })", screen[call + 1])
        assertTrue(screen.contains("if (scrollAway.hidden) (dock - navBottom).coerceAtLeast(0.dp) else 0.dp,"))
        val jump = code(body(channel, "private fun JumpToPresent("))
        assertTrue(jump.contains("val show by remember(state) { derivedStateOf { state.firstVisibleItemIndex > 8 } }"))
        assertEquals(
            listOf("NewestPill(", "show, \"Jump to present\",",
                "modifier.graphicsLayer { translationY = (1f - rise.value) * with(density) { dock.toPx() }; alpha = rise.value },",
                "enabled = inView,", ") { scope.launch { state.glideToPresent() } }"),
            jump.dropWhile { it != "NewestPill(" }.dropLast(1),
        )
        // Left to its defaults, the shared pill is the channel's as it was: no label, role or live region.
        assertTrue(channel.contains("actionLabel: String? = null, role: androidx.compose.ui.semantics.Role? = null, announce: Boolean = false,"))
        val look = code(body(channel, "internal fun NewestPill("))
        assertTrue(look.contains(".then(if (announce) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)"))
        assertTrue(look.contains(".clickable(enabled = enabled, onClickLabel = actionLabel, role = role, onClick = onClick)"))
        assertTrue(look.contains("enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut(),"))
    }
}
