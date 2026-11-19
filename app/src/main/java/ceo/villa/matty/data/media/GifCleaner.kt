package ceo.villa.matty.data.media

/**
 * A GIF (still or animated) without its metadata, losslessly: its blocks are walked, comments and
 * every application block go except the loop count (NETSCAPE2.0 or ANIMEXTS1.0, written again
 * holding only that count), and so does anything after the trailer. Frames, their timing and
 * their colour tables stay byte for byte.
 */
internal object GifCleaner {
    private val LOOPS = setOf("NETSCAPE2.0", "ANIMEXTS1.0")

    /** Null when the file can't be followed. */
    fun clean(src: Source): Cleaned? = try { plan(src) } catch (e: NotFollowed) { null }

    private fun plan(src: Source): Cleaned {
        val c = Cursor(src)
        val signature = String(c.bytes(6), Charsets.ISO_8859_1)
        if (signature != "GIF87a" && signature != "GIF89a") throw NotFollowed("not a GIF")
        c.skip(4) // width, height
        val packed = c.u8()
        c.skip(2) // background, aspect
        if (packed and 0x80 != 0) c.skip(3L shl ((packed and 7) + 1))
        val out = PlanBuilder()
        out.copy(0, c.pos)
        var frames = 0
        var loopWritten = false
        while (true) {
            val start = c.pos
            when (c.u8()) {
                0x2C -> { // a frame
                    c.skip(8)
                    val local = c.u8()
                    if (local and 0x80 != 0) c.skip(3L shl ((local and 7) + 1))
                    c.skip(1) // LZW code size
                    skipBlocks(c)
                    out.copy(start, c.pos - start)
                    frames++
                }
                0x21 -> when (c.u8()) {
                    // Frame timing (four bytes, always), and plain text (a 12-byte header, then the
                    // words it draws): kept only in their own shape, so nothing else can travel as one.
                    0xF9 -> { if (c.u8() != 4) throw NotFollowed("a bad frame timing"); c.skip(4); if (c.u8() != 0) throw NotFollowed("a bad frame timing"); out.copy(start, c.pos - start) }
                    0x01 -> { if (c.u8() != 12) throw NotFollowed("a bad text block"); c.skip(12); skipBlocks(c); out.copy(start, c.pos - start) }
                    0xFF -> {
                        val idLength = c.u8()
                        val id = String(c.bytes(idLength), Charsets.ISO_8859_1)
                        var loops = -1
                        while (true) {
                            val n = c.u8()
                            if (n == 0) break
                            val blockAt = c.pos
                            if (n >= 3 && c.u8() == 1 && loops < 0) loops = c.u16le()
                            c.pos = blockAt
                            c.skip(n.toLong())
                        }
                        if (id in LOOPS && loops >= 0 && !loopWritten) {
                            loopWritten = true
                            out.put(byteArrayOf(0x21, 0xFF.toByte(), 11) + ascii("NETSCAPE2.0") +
                                byteArrayOf(3, 1, loops.toByte(), (loops shr 8).toByte(), 0))
                        }
                    }
                    else -> skipBlocks(c) // comments and anything else: dropped
                }
                0x3B -> {
                    if (frames == 0) throw NotFollowed("no picture")
                    out.put(byteArrayOf(0x3B))
                    return out.build()
                }
                else -> throw NotFollowed("unknown block")
            }
        }
    }

    private fun skipBlocks(c: Cursor) {
        while (true) {
            val n = c.u8()
            if (n == 0) return
            c.skip(n.toLong())
        }
    }
}
