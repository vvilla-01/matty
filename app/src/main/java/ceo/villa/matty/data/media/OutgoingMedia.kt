package ceo.villa.matty.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The one stage every picture and video passes through before it goes to a server: attached to a
 * message (from the photo picker, the file picker or a share from another app) or made a profile
 * picture. What the file is comes from its first bytes. A JPEG, PNG, WebP or GIF is cleaned in its
 * own format, losslessly; a video of the MP4 family is cleaned in place; any other picture the
 * phone can read is drawn again from its pixels. A picture or video that can't be cleaned
 * either way doesn't go at all. Everything else (documents, WebM and other videos) goes as it is.
 */
object OutgoingMedia {
    /** The most pixels a picture is drawn again at: about 24 megapixels, under 100 MB while it is held. */
    const val REDRAW_MAX_PIXELS = 24_000_000L

    sealed interface Outcome {
        /** Not a picture or a video: send the file as it is. */
        data object AsIs : Outcome
        /** A picture or video that couldn't be cleaned: it isn't sent. */
        data object Refused : Outcome
        /** A picture or video too big for the phone's free space to hold its private copy: it isn't sent. */
        data object NoRoom : Outcome
        /**
         * Ready to send, as [mime], [size] bytes, read by [open]. The work lives in a private copy
         * that [discard] deletes, once it has been sent or isn't wanted any more.
         */
        class Ready(private val file: File, private val cleaned: Cleaned, val mime: String) : Outcome {
            val size get() = cleaned.size
            internal fun holds(f: File) = f == file
            fun open(): InputStream {
                val src = FileSource(file)
                val stream = cleaned.open(src)
                return object : java.io.FilterInputStream(stream) {
                    override fun close() { try { super.close() } finally { src.close() } }
                }
            }
            fun bytes(): ByteArray = open().use { it.readBytes() }
            fun discard() { file.delete() }
        }
    }

    private var swept = false

    /** Deletes what an earlier run left behind (it ended mid-upload), once, before this run's first copy. */
    @Synchronized private fun sweep(dir: File) {
        if (swept) return
        swept = true
        dir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Makes the file at [uri] ready to send. [claimedMime] is only a hint, for files whose bytes
     * don't say. Runs off the main thread; cancellable, and a copy made just as the caller was
     * cancelled is deleted too; throws when the file can't be read at all.
     */
    suspend fun prepare(context: Context, uri: Uri, claimedMime: String): Outcome = withContextOrDiscard<Outcome>(Dispatchers.IO, { (it as? Outcome.Ready)?.discard() }) {
        val cr = context.contentResolver
        val head = cr.openInputStream(uri)?.use { it.readNBytes(MediaKind.HEAD) } ?: throw java.io.IOException("Can't open the file")
        val route = Route.of(head, claimedMime)
        if (route == Route.AsIs) return@withContextOrDiscard Outcome.AsIs
        // A private copy, so what is cleaned is exactly what is sent: the other app can't change
        // the file between the two.
        val dir = File(context.cacheDir, "outgoing")
        sweep(dir)
        dir.mkdirs()
        // The whole file is copied first: on a nearly full phone that would fail half-way with a
        // disk error, so a file that can't fit is turned down plainly before it starts.
        val length = runCatching { cr.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
        if (!roomToClean(length, dir.usableSpace)) return@withContextOrDiscard Outcome.NoRoom
        val copy = File(dir, UUID.randomUUID().toString())
        try {
            (cr.openInputStream(uri) ?: throw java.io.IOException("Can't open the file")).use { input ->
                copy.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
            val copiedHead = FileSource(copy).use { s -> ByteArray(minOf(MediaKind.HEAD.toLong(), s.size).toInt()).also { s.read(0, it, 0, it.size) } }
            // Decided again on the copy: it is the copy that goes.
            val outcome = when (val r = Route.of(copiedHead, claimedMime)) {
                Route.AsIs -> Outcome.AsIs
                is Route.Lossless -> FileSource(copy).use { cleanLossless(r.kind, it) }?.let { Outcome.Ready(copy, it, r.kind.mime!!) }
                    ?: redraw(copy, dir) ?: Outcome.Refused
                Route.Movie -> FileSource(copy).use { cleanMovie(it) }?.let { Outcome.Ready(copy, it, movieMime(copiedHead, claimedMime)) }
                    ?: Outcome.Refused
                is Route.Redraw -> redraw(copy, dir) ?: if (r.picture) Outcome.Refused else Outcome.AsIs
            }
            // A picture drawn again lives in a file of its own.
            if (outcome !is Outcome.Ready || !outcome.holds(copy)) copy.delete()
            outcome
        } catch (e: Throwable) {
            copy.delete()
            throw e
        }
    }

    /**
     * The picture in [file] drawn again from its pixels, upright, as a JPEG (a PNG when it has
     * see-through parts), carrying nothing but the pixels and their colour profile. An HDR
     * picture goes as its ordinary picture, without its gain map. Null when the phone can't read it.
     */
    private suspend fun redraw(file: File, dir: File): Outcome.Ready? {
        val out = File(dir, UUID.randomUUID().toString())
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width.toLong()
                val h = info.size.height.toLong()
                // A very large picture is sampled down rather than failing for want of memory.
                if (w * h > REDRAW_MAX_PIXELS) {
                    val s = sqrt(REDRAW_MAX_PIXELS.toDouble() / (w * h))
                    decoder.setTargetSize(maxOf(1, (w * s).roundToInt()), maxOf(1, (h * s).roundToInt()))
                }
                // HDR transfer curves don't belong in an ordinary JPEG.
                val cs = info.colorSpace
                if (cs == ColorSpace.get(ColorSpace.Named.BT2020_HLG) || cs == ColorSpace.get(ColorSpace.Named.BT2020_PQ)) {
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
                }
            }
        } catch (e: java.io.IOException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
        try {
            currentCoroutineContext().ensureActive()
            // Otherwise an Ultra HDR picture would be written with its gain map again.
            bitmap.gainmap = null
            val png = bitmap.hasAlpha() && seeThrough(bitmap)
            val written = out.outputStream().use { bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it) }
            if (!written) { out.delete(); return null }
            return Outcome.Ready(out, Cleaned(listOf(Piece.Copy(0, out.length()))), if (png) "image/png" else "image/jpeg")
        } catch (e: Throwable) {
            out.delete()
            throw e
        } finally {
            bitmap.recycle()
        }
    }

    /** Whether any pixel lets something show through: read a row at a time. */
    private fun seeThrough(b: Bitmap): Boolean {
        val row = IntArray(b.width)
        for (y in 0 until b.height) {
            b.getPixels(row, 0, b.width, 0, y, b.width, 1)
            if (row.any { it ushr 24 != 0xFF }) return true
        }
        return false
    }
}

/**
 * [block] in [context], as withContext runs it, except that what it made is handed to [discard]
 * when the caller was cancelled meanwhile. withContext then throws instead of returning the
 * result, so the private copy that result holds would otherwise stay behind in the cache.
 */
internal suspend fun <T : Any> withContextOrDiscard(context: CoroutineContext, discard: (T) -> Unit, block: suspend CoroutineScope.() -> T): T {
    val made = AtomicReference<T?>(null)
    try {
        return withContext(context) { block().also { made.set(it) } }
    } catch (e: CancellationException) {
        made.getAndSet(null)?.let(discard)
        throw e
    }
}
