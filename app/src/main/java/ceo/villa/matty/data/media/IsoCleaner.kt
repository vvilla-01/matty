package ceo.villa.matty.data.media

/**
 * A video of the ISO base media family (MP4, MOV, 3GP, M4V: what phones record) without its
 * metadata, and without re-encoding: its boxes are walked and every box of user data or
 * metadata (udta with its ©xyz position and maker, meta with Android's and Apple's keys, XMP and
 * other uuid boxes, QuickTime's preview picture) becomes a "free" box of zeros of the same size,
 * and the creation and modification times of the movie, its tracks and their media are zeroed.
 * Nothing moves, so every offset into the media data still holds: the picture, the sound, the
 * rotation matrix and the edit lists stay exactly as they were. Only the boxes' headers are read,
 * so a file of any size is cleaned without holding it.
 *
 * Not reached: samples of a timed-metadata track (a drone's or an action camera's GPS track),
 * which live among the media data like the picture's own; phones don't record those.
 */
internal object IsoCleaner {
    /** Top-level boxes a player needs; any other top-level box is blanked. */
    private val TOP_KEEP = setOf("ftyp", "styp", "moov", "mdat", "moof", "mfra", "sidx", "ssix", "free", "skip", "wide", "pdin")
    /** Inside the movie and its tracks: what is blanked wherever it sits. */
    private val BLANK = setOf("udta", "meta", "uuid", "XMP_", "loci", "pnot", "PICT", "prft")
    /** A fragmented recording keeps user data and metadata in each fragment (moof) and its tracks (traf) too. */
    private val DESCEND = setOf("moov", "trak", "mdia", "moof", "traf")
    private val TIMES = setOf("mvhd", "tkhd", "mdhd")
    /**
     * The walk reads only headers, so what it may read grows with the file: one box for every 16
     * bytes (a recording written one fragment per frame has seven boxes per frame, a few hundred
     * bytes), and never fewer than 200 000.
     */
    private const val MIN_BOXES = 200_000L
    private const val BYTES_PER_BOX = 16L
    /** What is held is the changes, so they have a bound of their own: the plan holds no more pieces than this anyway. */
    private const val MAX_EDITS = 100_000
    private const val MAX_DEPTH = 8

    /** Null when the file can't be followed. */
    fun clean(src: Source): Cleaned? = try { Walk(src).run() } catch (e: NotFollowed) { null }

    private class Walk(val src: Source) {
        val c = Cursor(src)
        /** Changes to the original, by where they start; they never overlap. */
        val edits = ArrayList<Pair<Long, Piece>>()
        var boxes = 0L
        val maxBoxes = maxOf(MIN_BOXES, src.size / BYTES_PER_BOX)
        var movie = false

        fun run(): Cleaned {
            val end = children(0, src.size, 0)
            if (!movie) throw NotFollowed("no movie")
            val out = PlanBuilder()
            var at = 0L
            for ((where, piece) in edits.sortedBy { it.first }) {
                out.copy(at, where - at)
                when (piece) {
                    is Piece.Put -> out.put(piece.bytes)
                    is Piece.Zeros -> out.zeros(piece.length)
                    is Piece.Copy -> Unit
                }
                at = where + piece.length
            }
            out.copy(at, end - at)
            return out.build()
        }

        /** Walks the boxes between [start] and [end]; returns where the file's boxes really end (top level only). */
        fun children(start: Long, end: Long, depth: Int): Long {
            if (depth > MAX_DEPTH) throw NotFollowed("too deep")
            c.pos = start
            while (c.pos < end) {
                if (++boxes > maxBoxes) throw NotFollowed("too many boxes")
                val at = c.pos
                if (end - at < 8) {
                    // QuickTime ends some lists with four zero bytes; after a movie, a few stray
                    // bytes at the very end of the file are simply left out.
                    if (depth == 0 && movie) return at
                    if (depth > 0 && end - at == 4L && c.u32be() == 0L) return end
                    throw NotFollowed("a box without its header")
                }
                val size32 = c.u32be()
                val typeBytes = c.bytes(4)
                val type = String(typeBytes, Charsets.ISO_8859_1)
                if (!typeBytes.all { (it.toInt() and 0xFF) in 0x20..0x7E || (it.toInt() and 0xFF) == 0xA9 }) {
                    // A vendor's trailer after the movie isn't a box: it goes.
                    if (depth == 0 && movie) return at
                    throw NotFollowed("not a box")
                }
                var header = 8L
                val size = when (size32) {
                    0L -> end - at
                    1L -> { header = 16; c.u64be() }
                    else -> size32
                }
                if (size < header || size > end - at) throw NotFollowed("box runs past its end")
                when {
                    type == "cmov" -> throw NotFollowed("compressed movie")
                    depth == 0 && type == "moov" -> { movie = true; children(at + header, at + size, 1) }
                    depth == 0 && type == "moof" -> children(at + header, at + size, 1)
                    depth == 0 && type !in TOP_KEEP -> blank(at, header, size)
                    depth > 0 && type in BLANK -> blank(at, header, size)
                    depth > 0 && type in DESCEND -> children(at + header, at + size, depth + 1)
                    depth > 0 && type in TIMES -> times(at + header, size - header)
                }
                c.pos = at + size
            }
            return end
        }

        /** The box at [at] becomes a "free" box of zeros: same size, so nothing after it moves. */
        fun blank(at: Long, header: Long, size: Long) {
            edit(at + 4, Piece.Put(ascii("free")))
            edit(at + header, Piece.Zeros(size - header))
        }

        fun edit(at: Long, piece: Piece) {
            if (edits.size >= MAX_EDITS) throw NotFollowed("too many changes")
            edits += at to piece
        }

        /** Zeroes the creation and modification times at the start of mvhd, tkhd or mdhd. */
        fun times(payload: Long, length: Long) {
            c.pos = payload
            val wide = c.u8() == 1
            val width = if (wide) 8L else 4L
            if (length < 4 + 2 * width) throw NotFollowed("a header too short")
            edit(payload + 4, Piece.Zeros(2 * width))
        }
    }
}
