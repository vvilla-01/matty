package ceo.villa.matty.data.media

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Bytes that can be read anywhere, without holding them all: a file being cleaned (a video can be
 * gigabytes), or a small array in a test. Plain Kotlin, so the cleaners run on a computer too.
 */
interface Source {
    val size: Long

    /** Reads up to [len] bytes found at [at] into [into]; fewer only at the end. */
    fun read(at: Long, into: ByteArray, off: Int, len: Int): Int
}

class ArraySource(private val bytes: ByteArray) : Source {
    override val size get() = bytes.size.toLong()
    override fun read(at: Long, into: ByteArray, off: Int, len: Int): Int {
        if (at >= bytes.size) return 0
        val n = minOf(len.toLong(), bytes.size - at).toInt()
        System.arraycopy(bytes, at.toInt(), into, off, n)
        return n
    }
}

class FileSource(file: File) : Source, Closeable {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()
    override fun read(at: Long, into: ByteArray, off: Int, len: Int): Int {
        raf.seek(at)
        var done = 0
        while (done < len) {
            val n = raf.read(into, off + done, len - done)
            if (n < 0) break
            done += n
        }
        return done
    }
    override fun close() = raf.close()
}

/**
 * A file that breaks its format's rules where a cleaner has to follow them. It is never shown to
 * anyone: the picture is drawn again from its pixels instead, or not attached. No stack trace,
 * since it is expected and cheap.
 */
internal class NotFollowed(message: String) : Exception(message, null, false, false)

/** What the cleaned file is made of, in order: ranges of the original, and bytes of its own. */
sealed interface Piece {
    val length: Long
    class Copy(val at: Long, override val length: Long) : Piece
    class Put(val bytes: ByteArray) : Piece { override val length get() = bytes.size.toLong() }
    class Zeros(override val length: Long) : Piece
}

/** A cleaned file, described rather than written: it is read out of the original as it uploads. */
class Cleaned(val pieces: List<Piece>) {
    val size: Long = pieces.sumOf { it.length }

    /** The cleaned bytes, read from [src] (the original) as they are asked for. */
    fun open(src: Source): InputStream = PiecesStream(src, pieces)

    /** The whole cleaned file in memory: only for something small (a test, a profile picture). */
    fun toByteArray(src: Source): ByteArray = open(src).use { it.readBytes() }
}

private class PiecesStream(private val src: Source, private val pieces: List<Piece>) : InputStream() {
    private var index = 0
    private var done = 0L

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (index < pieces.size && done >= pieces[index].length) { index++; done = 0 }
        if (index >= pieces.size) return -1
        val p = pieces[index]
        val n = minOf(len.toLong(), p.length - done).toInt()
        when (p) {
            is Piece.Copy -> {
                val got = src.read(p.at + done, b, off, n)
                // The original got shorter while it was being sent: stop, rather than send zeros.
                if (got <= 0) throw java.io.IOException("The file changed while it was being sent")
                done += got
                return got
            }
            is Piece.Put -> System.arraycopy(p.bytes, done.toInt(), b, off, n)
            is Piece.Zeros -> java.util.Arrays.fill(b, off, off + n, 0)
        }
        done += n
        return n
    }
}

/**
 * Builds a [Cleaned]: neighbouring ranges of the original join into one. Held to [maxPieces] so a
 * file made of millions of tiny blocks can't make a list of millions.
 */
internal class PlanBuilder(private val maxPieces: Int = 100_000) {
    private val pieces = ArrayList<Piece>()

    fun copy(at: Long, length: Long) {
        if (length <= 0) return
        val last = pieces.lastOrNull()
        if (last is Piece.Copy && last.at + last.length == at) pieces[pieces.size - 1] = Piece.Copy(last.at, last.length + length)
        else add(Piece.Copy(at, length))
    }

    fun put(bytes: ByteArray) { if (bytes.isNotEmpty()) add(Piece.Put(bytes)) }

    fun zeros(length: Long) {
        if (length <= 0) return
        val last = pieces.lastOrNull()
        if (last is Piece.Zeros) pieces[pieces.size - 1] = Piece.Zeros(last.length + length) else add(Piece.Zeros(length))
    }

    fun addAll(other: PlanBuilder) = other.pieces.forEach { p ->
        when (p) {
            is Piece.Copy -> copy(p.at, p.length)
            is Piece.Put -> put(p.bytes)
            is Piece.Zeros -> zeros(p.length)
        }
    }

    private fun add(p: Piece) {
        if (pieces.size >= maxPieces) throw NotFollowed("too many pieces")
        pieces += p
    }

    fun build() = Cleaned(pieces.toList())
}

/**
 * Reads [Source] in order through a small buffer. Every read past the end, and every skip
 * further than what is left, is a [NotFollowed]: a length a file states is checked against the
 * bytes really there before anything is done with it.
 */
internal class Cursor(private val src: Source, start: Long = 0) {
    var pos: Long = start
    val size get() = src.size
    val remaining get() = src.size - pos
    private val buf = ByteArray(16 * 1024)
    private var bufAt = 0L
    private var bufLen = 0

    fun u8(): Int {
        if (pos < bufAt || pos >= bufAt + bufLen) {
            if (pos >= src.size) throw NotFollowed("ends early")
            bufAt = pos
            bufLen = src.read(pos, buf, 0, buf.size)
            if (bufLen <= 0) throw NotFollowed("ends early")
        }
        return buf[(pos++ - bufAt).toInt()].toInt() and 0xFF
    }

    fun u16be() = (u8() shl 8) or u8()
    fun u16le() = u8() or (u8() shl 8)
    fun u32be(): Long = (u16be().toLong() shl 16) or u16be().toLong()
    fun u32le(): Long = u16le().toLong() or (u16le().toLong() shl 16)
    fun u64be(): Long {
        val hi = u32be()
        if (hi ushr 31 != 0L) throw NotFollowed("size out of range")
        return (hi shl 32) or u32be()
    }

    /** [n] bytes, for headers and small segments only: the caller holds [n] to a bound of its own. */
    fun bytes(n: Int): ByteArray {
        if (n < 0 || n > remaining) throw NotFollowed("ends early")
        return ByteArray(n) { u8().toByte() }
    }

    fun skip(n: Long) {
        if (n < 0 || n > remaining) throw NotFollowed("ends early")
        pos += n
    }
}

internal fun ByteArray.startsWith(prefix: ByteArray, at: Int = 0): Boolean =
    size - at >= prefix.size && prefix.indices.all { this[at + it] == prefix[it] }

internal fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)
