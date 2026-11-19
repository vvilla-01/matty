package ceo.villa.matty.data.net

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** A still from the video attachment [fileId] (at [url]), as something the image loader can show. */
data class VideoStill(val fileId: String, val url: String)

/**
 * Stills for video attachments. Mattermost makes previews for pictures only, so a video's tile in
 * the chat would be blank; the app takes a frame itself. It reads just the parts of the file the
 * decoder asks for (ranged requests through Matty's own HTTP client, with its rules about where
 * the session token may go), never the whole video, and the frame is kept as a JPEG in the image
 * cache, so each video is read once.
 */
class VideoStills(private val http: () -> OkHttpClient, private val prefs: SharedPreferences) {
    /** Two at a time: each one holds a decoder and a connection. */
    private val gate = Semaphore(2)

    /**
     * Videos no still can be had from: a codec this phone can't decode, a file the server won't
     * serve in ranges, one whose first frame lies further in than a thumbnail is worth
     * downloading. Remembered across launches, since asking again costs the same bytes for the
     * same nothing. Never for a failure of the connection, which says nothing about the video.
     */
    private val failed = LinkedHashSet<String>().apply { addAll(prefs.getString(NO_STILL, "").orEmpty().split(';').filter { it.isNotEmpty() }) }

    /** Width over height of the videos seen so far, latest last, so their tiles take the right shape next time. */
    private val shapes = LinkedHashMap<String, Float>().apply {
        prefs.getString(SHAPES, "").orEmpty().split(';').forEach { e ->
            val (id, ratio) = e.split('=').takeIf { it.size == 2 } ?: return@forEach
            ratio.toFloatOrNull()?.takeIf { it > 0f }?.let { put(id, it) }
        }
    }

    fun aspect(fileId: String): Float? = synchronized(shapes) { shapes[fileId] }

    private fun remember(fileId: String, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val saved = synchronized(shapes) {
            // Back in at the end, as the latest; the eldest go once there are too many.
            shapes.remove(fileId)
            shapes[fileId] = width.toFloat() / height
            val eldest = shapes.keys.iterator()
            while (shapes.size > MAX_KEPT) { eldest.next(); eldest.remove() }
            shapes.entries.joinToString(";") { "${it.key}=${it.value}" }
        }
        prefs.edit().putString(SHAPES, saved).apply()
    }

    private fun givenUpOn(fileId: String): Boolean = synchronized(failed) { fileId in failed }

    private fun giveUpOn(fileId: String) {
        val saved = synchronized(failed) {
            failed.remove(fileId)
            failed += fileId
            val eldest = failed.iterator()
            while (failed.size > MAX_KEPT) { eldest.next(); eldest.remove() }
            failed.joinToString(";")
        }
        prefs.edit().putString(NO_STILL, saved).apply()
    }

    fun forget() {
        synchronized(shapes) { shapes.clear() }
        synchronized(failed) { failed.clear() }
        prefs.edit().remove(SHAPES).remove(NO_STILL).apply()
    }

    /**
     * A frame of the video behind [source], or null when it has none to give. Throws when the
     * connection failed on the way: that is no verdict on the video, and it's tried again.
     */
    private fun grab(source: HttpRangeSource): Bitmap? {
        val retriever = MediaMetadataRetriever()
        val frame = try {
            retriever.setDataSource(source)
            // "Any representative frame", no bigger than a tile needs.
            retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, MAX_EDGE, MAX_EDGE) ?: retriever.frameAtTime
        } catch (e: RuntimeException) {
            null
        } finally {
            runCatching { retriever.release() }
            source.close()
        }
        if (source.interrupted) throw IOException("Couldn't read the video just now")
        // Cut short by the byte budget: what was decoded may be half a frame, and isn't kept.
        return frame.takeUnless { source.starved }
    }

    inner class FetcherFactory : Fetcher.Factory<VideoStill> {
        override fun create(data: VideoStill, options: Options, imageLoader: ImageLoader): Fetcher = Fetcher {
            val key = cacheKey(data)
            val cache = imageLoader.diskCache
            cache?.openSnapshot(key)?.let { snap ->
                // A still kept from before the shape memory let this video go: measured again (its header only).
                if (aspect(data.fileId) == null) runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(snap.data.toString(), bounds)
                    remember(data.fileId, bounds.outWidth, bounds.outHeight)
                }
                return@Fetcher SourceFetchResult(ImageSource(snap.data, cache.fileSystem, key, snap), "image/jpeg", DataSource.DISK)
            }
            if (givenUpOn(data.fileId)) throw IOException("No still for this video")
            val frame = gate.withPermit {
                if (!TransportPolicy.isAllowed(data.url)) null else coroutineScope {
                    val source = HttpRangeSource(http(), data.url)
                    val work = async(Dispatchers.IO) { grab(source) }
                    // The tile scrolled away: the read stops there, and the slot is free for one on screen.
                    try { work.await() } catch (e: CancellationException) { source.cancel(); throw e }
                }
            }
            if (frame == null) { giveUpOn(data.fileId); throw IOException("No still for this video") }
            remember(data.fileId, frame.width, frame.height)
            cache?.openEditor(key)?.let { editor ->
                try {
                    cache.fileSystem.write(editor.data) { frame.compress(Bitmap.CompressFormat.JPEG, 84, outputStream()) }
                    editor.commit()
                } catch (e: Exception) {
                    runCatching { editor.abort() }
                }
            }
            ImageFetchResult(frame.asImage(), isSampled = true, dataSource = DataSource.NETWORK) as FetchResult
        }
    }

    class StillKeyer : Keyer<VideoStill> {
        override fun key(data: VideoStill, options: Options): String = cacheKey(data)
    }

    companion object {
        private const val SHAPES = "video_shapes"
        private const val NO_STILL = "video_no_still"
        /** The latest few hundred are plenty: an older video is simply looked at again when it's next seen. */
        private const val MAX_KEPT = 400
        private const val MAX_EDGE = 960
        fun cacheKey(still: VideoStill) = "video-still:${still.fileId}"
    }
}

/**
 * A remote file read a block at a time, as the media framework asks for it: the index of an MP4
 * can sit at either end, so the decoder jumps about, and only what it touches is fetched. Gives
 * up (end of file) past [BUDGET] bytes, or when the server won't serve ranges, rather than pull
 * a whole video down for one frame.
 */
private class HttpRangeSource(private val http: OkHttpClient, private val url: String) : MediaDataSource() {
    private var total = -1L
    private var spent = 0L
    private var budget = BUDGET
    private var dead = false
    private val blocks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > KEPT
    }

    /** A request failed on the way (no connection, a timeout, a busy server): that says nothing about the video. */
    @Volatile var interrupted = false
        private set

    /** The decoder asked for more than a still is worth: whatever it made of the rest isn't to be trusted. */
    @Volatile var starved = false
        private set

    @Volatile private var cancelled = false
    @Volatile private var call: okhttp3.Call? = null

    /** Stops the read from any thread (never synchronized: [readAt] holds the lock while a block is on the wire). */
    fun cancel() {
        cancelled = true
        call?.cancel()
    }

    @Synchronized
    override fun getSize(): Long {
        if (total < 0 && !dead) block(0)
        return total
    }

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (dead || position < 0) return -1
        val index = position / BLOCK
        val data = block(index) ?: return -1
        val within = (position - index * BLOCK).toInt()
        val n = minOf(size, data.size - within)
        if (n <= 0) return -1
        System.arraycopy(data, within, buffer, offset, n)
        return n
    }

    private fun block(index: Long): ByteArray? {
        blocks[index]?.let { return it }
        val start = index * BLOCK
        if (cancelled || total in 0..start) return null
        if (spent >= budget) { starved = true; return null }
        val data = try { fetch(start) } catch (e: IOException) { interrupted = true; null }
        if (data == null) { dead = true; return null }
        // Matroska (WebM, MKV) isn't laid out for finding one frame: Android's reader walks it
        // cluster after cluster, megabytes of it, and often past the whole budget for nothing.
        // Its first frame is near the start or it isn't worth having.
        if (index == 0L && data.size >= 4 && data[0] == 0x1A.toByte() && data[1] == 0x45.toByte() && data[2] == 0xDF.toByte() && data[3] == 0xA3.toByte()) {
            budget = MATROSKA_BUDGET
        }
        spent += data.size
        blocks[index] = data
        return data
    }

    private fun fetch(start: Long): ByteArray? {
        val request = Request.Builder().url(url)
            .header("Range", "bytes=$start-${start + BLOCK - 1}")
            .build()
        val asked = http.newCall(request).also { call = it }
        if (cancelled) asked.cancel()
        asked.execute().use { resp ->
            val body = resp.body ?: return null
            when (resp.code) {
                206 -> {
                    // "bytes 0-262143/7340032"
                    resp.header("Content-Range")?.substringAfter('/', "")?.toLongOrNull()?.let { total = it }
                    return body.bytes()
                }
                // No ranges here: the start of the file is still the start, anything further isn't worth the whole download.
                200 -> {
                    if (start != 0L) return null
                    if (total < 0) total = body.contentLength()
                    val source = body.source()
                    val buffer = okio.Buffer()
                    while (buffer.size < BLOCK) { if (source.read(buffer, BLOCK - buffer.size) == -1L) break }
                    return buffer.readByteArray()
                }
                // Gone, or not this reader's to see: that won't change by asking again.
                403, 404, 410, 416 -> return null
                // Busy, signed out for a moment, a gateway hiccup: nothing to hold against the video.
                else -> { interrupted = true; return null }
            }
        }
    }

    @Synchronized
    override fun close() { blocks.clear(); dead = true }

    private companion object {
        const val BLOCK = 256L * 1024
        const val KEPT = 32
        const val BUDGET = 24L * 1024 * 1024
        /** Eight blocks: fewer than [KEPT], so nothing read is ever fetched twice. */
        const val MATROSKA_BUDGET = 2L * 1024 * 1024
    }
}
