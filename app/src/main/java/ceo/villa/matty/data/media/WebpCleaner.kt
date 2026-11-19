package ceo.villa.matty.data.media

/**
 * A WebP (still or animated) without its metadata, losslessly: its chunks are walked, EXIF, XMP
 * and every chunk this doesn't know go, and so does anything after the file's stated end. The
 * picture's own chunks (VP8, VP8L, alpha, animation frames) and its colour profile stay byte for
 * byte; the header's flags are put right for what is left. As with a JPEG, a picture that isn't
 * stored upright keeps an EXIF chunk holding only its Orientation.
 */
internal object WebpCleaner {
    private val KEEP = setOf("VP8X", "ICCP", "ANIM", "ANMF", "ALPH", "VP8 ", "VP8L")
    private const val FLAG_EXIF = 0x08
    private const val FLAG_XMP = 0x04

    /** Null when the file can't be followed. */
    fun clean(src: Source): Cleaned? = try { plan(src) } catch (e: NotFollowed) { null }

    private fun plan(src: Source): Cleaned {
        val c = Cursor(src)
        if (String(c.bytes(4), Charsets.ISO_8859_1) != "RIFF") throw NotFollowed("not RIFF")
        val end = 8 + c.u32le()
        if (String(c.bytes(4), Charsets.ISO_8859_1) != "WEBP") throw NotFollowed("not a WebP")
        if (end > src.size) throw NotFollowed("ends early")
        val kept = PlanBuilder()
        var vp8x: ByteArray? = null
        var orientation = 1
        var exifSeen = false
        var image = false
        var first = true
        while (c.pos < end) {
            if (end - c.pos < 8) throw NotFollowed("a chunk without its header")
            val start = c.pos
            val type = String(c.bytes(4), Charsets.ISO_8859_1)
            val length = c.u32le()
            val padded = length + (length and 1)
            if (padded > end - c.pos) throw NotFollowed("chunk runs past the end")
            if (first) {
                if (type != "VP8X" && type != "VP8 " && type != "VP8L") throw NotFollowed("no picture first")
                first = false
            }
            when (type) {
                "VP8X" -> {
                    if (vp8x != null || length != 10L) throw NotFollowed("a bad extended header")
                    vp8x = c.bytes(10)
                }
                "EXIF" -> {
                    if (!exifSeen && length <= 256 * 1024) {
                        val e = c.bytes(length.toInt())
                        // Some writers put JPEG's "Exif" prefix in front of the TIFF block.
                        val tiff = if (e.startsWith(ascii("Exif\u0000\u0000"))) e.copyOfRange(6, e.size) else e
                        orientation = Orientation.read(tiff)
                    }
                    exifSeen = true
                }
                in KEEP -> kept.copy(start, 8 + padded)
                else -> Unit // XMP and anything unknown: dropped
            }
            if (type == "VP8 " || type == "VP8L" || type == "ANMF") image = true
            c.pos = start + 8 + padded
            // A simple WebP is its one picture chunk and nothing else.
            if (vp8x == null && image) break
        }
        if (!image) throw NotFollowed("no picture")
        val header = vp8x
        val exif = if (header != null && orientation != 1) Orientation.tiff(orientation) else null
        val out = PlanBuilder()
        val tail = exif?.let { chunk("EXIF", it) } ?: ByteArray(0)
        val size = 4 + (if (header != null) 18 else 0) + kept.build().size + tail.size
        out.put(ascii("RIFF") + le32(size) + ascii("WEBP"))
        if (header != null) {
            val flags = (header[0].toInt() and (FLAG_EXIF or FLAG_XMP).inv()) or (if (exif != null) FLAG_EXIF else 0)
            out.put(ascii("VP8X") + le32(10) + byteArrayOf(flags.toByte()) + header.copyOfRange(1, 10))
        }
        out.addAll(kept)
        // EXIF belongs after the picture's data.
        out.put(tail)
        return out.build()
    }

    private fun chunk(type: String, data: ByteArray): ByteArray =
        ascii(type) + le32(data.size.toLong()) + data + (if (data.size % 2 == 1) byteArrayOf(0) else ByteArray(0))

    private fun le32(n: Long) = byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte())
}
