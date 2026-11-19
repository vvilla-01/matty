package ceo.villa.matty

import ceo.villa.matty.data.api.Post
import ceo.villa.matty.data.api.User
import ceo.villa.matty.data.repo.ChatCache
import ceo.villa.matty.data.repo.sinceAdditions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec

class ChatCacheTest {
    private val dir: File = Files.createTempDirectory("matty-chats").toFile()
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val channel = "abcdefghijklmnopqrstuvwxyz"
    private val other = "zyxwvutsrqponmlkjihgfedcba"

    private fun snapshot() = ChatCache.Snapshot(
        collapsed = false,
        posts = listOf(Post(id = "p2", createAt = 200, updateAt = 250, userId = "u1", channelId = channel, message = "second, secret"), Post(id = "p1", createAt = 100, updateAt = 100, userId = "u1", channelId = channel, message = "first")),
        users = listOf(User(id = "u1", username = "alice")),
        hasMoreOlder = true, watermark = 250, savedAt = 1,
    )

    @After fun cleanUp() { dir.deleteRecursively() }

    @Test
    fun whatIsSavedComesBackAndIsNotReadableOnDisk() {
        val cache = ChatCache(dir, { true }) { key }
        cache.write(channel, snapshot())
        assertEquals(snapshot(), cache.read(channel))
        val onDisk = File(dir, "$channel.bin").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse("the words themselves must not be in the file", onDisk.contains("secret") || onDisk.contains("alice"))
    }

    @Test
    fun aMessageDeletedWhileItsConversationWasClosedLeavesTheDevice() {
        val cache = ChatCache(dir, { true }) { key }
        val reply = Post(id = "r1", createAt = 260, updateAt = 260, rootId = "p2", userId = "u1", channelId = channel, message = "a reply to the secret")
        cache.write(channel, snapshot().let { it.copy(posts = listOf(reply) + it.posts) })

        // Somebody else's message, somewhere else: nothing changes.
        cache.forgetPost(channel, "nope")
        cache.forgetPost(other, "p2")
        assertEquals(listOf("r1", "p2", "p1"), cache.read(channel)?.posts?.map { it.id })

        // The message goes, and the replies kept inline with it.
        cache.forgetPost(channel, "p2")
        val left = cache.read(channel)
        assertEquals(listOf("p1"), left?.posts?.map { it.id })
        assertEquals("what's left is otherwise as it was saved", 250L, left?.watermark)

        // The last one: nothing is kept at all.
        cache.forgetPost(channel, "p1")
        assertNull(cache.read(channel))
        assertFalse(File(dir, "$channel.bin").exists())
    }

    @Test
    fun anotherKeyOrAnotherChannelsFileDoesNotOpen() {
        ChatCache(dir, { true }) { key }.write(channel, snapshot())
        // A different key (the old one was dropped at sign-out, say): nothing, and the file goes.
        assertNull(ChatCache(dir, { true }) { SecretKeySpec(ByteArray(32) { 7 }, "AES") }.read(channel))
        assertFalse(File(dir, "$channel.bin").exists())
        // A file put under another channel's name doesn't pass for that channel's.
        val cache = ChatCache(dir, { true }) { key }
        cache.write(channel, snapshot())
        File(dir, "$channel.bin").copyTo(File(dir, "$other.bin"))
        assertNull(cache.read(other))
    }

    @Test
    fun nothingIsKeptWhileSwitchedOffOrWithoutAKey() {
        ChatCache(dir, { false }) { key }.write(channel, snapshot())
        assertFalse(File(dir, "$channel.bin").exists())
        ChatCache(dir, { true }) { null }.write(channel, snapshot())
        assertFalse(File(dir, "$channel.bin").exists())
        ChatCache(dir, { true }) { key }.write(channel, snapshot())
        assertNull(ChatCache(dir, { false }) { key }.read(channel))
        // Names that aren't channel ids never become file names.
        ChatCache(dir, { true }) { key }.write("../escape", snapshot())
        assertTrue(dir.listFiles().orEmpty().all { it.name.startsWith(channel) })
    }

    @Test
    fun sinceAnswersOnlyJoinTheStretchThatIsLoaded() {
        fun post(id: String, at: Long, root: String = "", deleted: Long = 0) = Post(id = id, createAt = at, rootId = root, deleteAt = deleted, channelId = channel)
        val known = mapOf("n2" to post("n2", 500), "n1" to post("n1", 400))
        val order = listOf("n2", "n1")
        val answer = listOf(post("new", 600), post("n2", 500), post("old-edited", 50), post("gone", 450, deleted = 700), post("reply", 610, root = "n2"))
        // More history above: only what's as new as the loaded stretch joins; the old edit would leave a hole.
        assertEquals(listOf("new", "reply"), sinceAdditions(order, hasMoreOlder = true, known, answer, collapsed = false))
        // The whole channel is loaded: nothing can be skipped, so the old one belongs too.
        assertEquals(listOf("new", "old-edited", "reply"), sinceAdditions(order, hasMoreOlder = false, known, answer, collapsed = false))
        // Replies stay in their threads when those are collapsed.
        assertEquals(listOf("new"), sinceAdditions(order, hasMoreOlder = true, known, answer, collapsed = true))
        // An empty timeline takes whatever is new.
        assertEquals(listOf("new", "n2", "old-edited", "reply"), sinceAdditions(emptyList(), hasMoreOlder = true, emptyMap(), answer, collapsed = false))
    }
}
