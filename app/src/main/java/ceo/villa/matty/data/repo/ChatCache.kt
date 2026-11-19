package ceo.villa.matty.data.repo

import ceo.villa.matty.data.api.MattermostJson
import ceo.villa.matty.data.api.Post
import ceo.villa.matty.data.api.User
import kotlinx.serialization.Serializable
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The newest stretch of each conversation, kept on the device. Opening a channel (or scrolling
 * back up it after a restart) then shows at once instead of waiting on the network, and the
 * server is only asked what changed since.
 *
 * What's kept is private, so it is treated like the session: encrypted (AES-GCM) with a key that
 * only ever sits on disk wrapped by the Android Keystore, in the app's own cache directory (which
 * Android may clear when it's short of room: it is only a cache), capped in size, and wiped on
 * sign-out. With no key to hand (a Keystore hiccup) nothing is read or written.
 */
class ChatCache(private val dir: File, private val enabled: () -> Boolean, private val key: () -> SecretKey?) {

    @Serializable
    data class Snapshot(
        val v: Int = VERSION,
        /** Whether replies were folded into threads when this was saved: a different timeline otherwise. */
        val collapsed: Boolean,
        /** Newest first, as the timeline has them, with no gaps. */
        val posts: List<Post>,
        /** Whoever wrote or reacted to them, so names and pictures are there straight away too. */
        val users: List<User>,
        val hasMoreOlder: Boolean,
        /** The latest change the server has reported for this channel (a post's update time): what to ask "since". */
        val watermark: Long,
        val savedAt: Long,
    )

    private fun file(channelId: String): File? = if (SAFE_ID.matches(channelId)) File(dir, "$channelId.bin") else null

    fun read(channelId: String): Snapshot? {
        if (!enabled()) return null
        val f = file(channelId)?.takeIf { it.isFile } ?: return null
        val k = key() ?: return null
        return try {
            val raw = f.readBytes()
            if (raw.size <= IV) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, raw, 0, IV))
            // The channel is bound in: a file moved or renamed to another channel's won't open.
            cipher.updateAAD(channelId.toByteArray())
            val plain = cipher.doFinal(raw, IV, raw.size - IV)
            MattermostJson.decodeFromString(Snapshot.serializer(), String(plain, Charsets.UTF_8)).takeIf { it.v == VERSION }
        } catch (e: Exception) {
            // Another key, another version, a torn write: it's a cache, so it's simply gone.
            f.delete()
            null
        }
    }

    fun write(channelId: String, snapshot: Snapshot) {
        if (!enabled()) return
        val f = file(channelId) ?: return
        val k = key() ?: return
        try {
            dir.mkdirs()
            val iv = ByteArray(IV).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, iv))
            cipher.updateAAD(channelId.toByteArray())
            val sealed = cipher.doFinal(MattermostJson.encodeToString(Snapshot.serializer(), snapshot).toByteArray(Charsets.UTF_8))
            // Whole or not at all: written beside, then moved into place.
            val tmp = File(dir, "$channelId.tmp")
            tmp.outputStream().use { it.write(iv); it.write(sealed) }
            if (!tmp.renameTo(f)) tmp.delete()
        } catch (e: Exception) {
            f.delete()
        }
    }

    fun remove(channelId: String) { file(channelId)?.delete() }

    /**
     * Takes one message out of what's kept for [channelId]: it was deleted on the server while
     * that conversation wasn't open, and a deleted message mustn't sit on the device waiting to
     * be shown again. (For an open conversation the timeline is saved afresh without it.)
     */
    @Synchronized
    fun forgetPost(channelId: String, postId: String) {
        val snapshot = read(channelId) ?: return
        // With its replies, where those are kept inline: a thread goes with the message that started it.
        val left = snapshot.posts.filterNot { it.id == postId || it.rootId == postId }
        if (left.size == snapshot.posts.size) return
        if (left.isEmpty()) remove(channelId) else write(channelId, snapshot.copy(posts = left))
    }

    fun clear() { dir.deleteRecursively() }

    /** Keeps the most recently used conversations within [MAX_BYTES] and [MAX_FILES]; the rest go. */
    fun prune() {
        val files = dir.listFiles { f -> f.isFile }?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        files.forEachIndexed { i, f ->
            total += f.length()
            if (i >= MAX_FILES || total > MAX_BYTES || f.name.endsWith(".tmp")) f.delete()
        }
    }

    companion object {
        const val VERSION = 1
        /** How many of a conversation's newest messages are kept. */
        const val MAX_POSTS = 400
        private const val MAX_FILES = 200
        private const val MAX_BYTES = 96L * 1024 * 1024
        private const val IV = 12
        private val SAFE_ID = Regex("^[a-z0-9]{20,40}$")
    }
}
