package ceo.villa.matty.data.media

/**
 * A JPEG without its metadata, losslessly: its segments are walked and only what draws the
 * picture is kept (the tables, the frame, the scans and their compressed data byte for byte, the
 * colour profile and Adobe's colour note). EXIF (position, time, camera, maker notes and the
 * embedded thumbnail, which can show the uncropped original), XMP, IPTC / Photoshop blocks,
 * comments, MPF and every other application block go, and so does everything after the
 * picture's end: the video of a motion photo, an Ultra HDR gain map, vendor trailers. The
 * Orientation tag stays, alone in an EXIF block of its own, when the picture isn't stored upright.
 */
internal object JpegCleaner {
    private val JFIF = ascii("JFIF\u0000")
    private val EXIF = ascii("Exif\u0000")
    private val ICC = ascii("ICC_PROFILE\u0000")
    private val ADOBE = ascii("Adobe")

    /** Null when the file can't be followed (truncated, a marker this doesn't know). */
    fun clean(src: Source): Cleaned? = try { plan(src) } catch (e: NotFollowed) { null }

    private fun plan(src: Source): Cleaned {
        val c = Cursor(src)
        if (c.u8() != 0xFF || c.u8() != 0xD8) throw NotFollowed("not a JPEG")
        val body = PlanBuilder()
        var jfif: ByteArray? = null
        var exifSeen = false
        var orientation = 1
        var frame = false
        var scans = 0
        var marker = nextMarker(c)
        while (true) {
            if (marker == 0xD9) {
                if (!frame || scans == 0) throw NotFollowed("no picture")
                body.put(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
                break
            }
            // Markers without a length: restart markers (only meaningful inside a scan) and TEM.
            if (marker in 0xD0..0xD7 || marker == 0x01) {
                body.copy(c.pos - 2, 2)
                marker = nextMarker(c)
                continue
            }
            val start = c.pos - 2
            val length = c.u16be()
            if (length < 2 || length - 2 > c.remaining) throw NotFollowed("segment runs past the end")
            val payload = c.pos
            when (marker) {
                0xE0 -> if (jfif == null && length >= 16) {
                    val p = c.bytes(14)
                    // Only the version and pixel density are kept; its thumbnail, if any, goes.
                    if (p.startsWith(JFIF)) jfif = p.copyOf(14).also { it[12] = 0; it[13] = 0 }
                }
                0xE1 -> if (!exifSeen && length - 2 >= EXIF.size + 1) {
                    val p = c.bytes(length - 2)
                    if (p.startsWith(EXIF)) {
                        exifSeen = true
                        orientation = Orientation.read(p.copyOfRange(6.coerceAtMost(p.size), p.size))
                    }
                }
                0xE2 -> if (length - 2 >= ICC.size && c.bytes(ICC.size).startsWith(ICC)) body.copy(start, length + 2L)
                0xEE -> if (length == 14 && c.bytes(ADOBE.size).startsWith(ADOBE)) body.copy(start, length + 2L)
                in 0xE3..0xED, 0xEF, 0xFE -> Unit // other application blocks, comments: dropped
                // Tables, restarts, DNL, hierarchy: kept only when they are exactly as long as
                // what they hold says, so nothing else can travel inside one.
                0xDB, 0xC4, 0xCC, 0xDD, 0xDC, 0xDE, 0xDF -> {
                    if (!fits(marker, c.bytes(length - 2))) throw NotFollowed("a table of the wrong size")
                    body.copy(start, length + 2L)
                }
                in 0xC0..0xCF -> { // a frame; C4, C8 and CC are not frames
                    if (marker == 0xC8 || !fits(marker, c.bytes(length - 2))) throw NotFollowed("a bad frame")
                    frame = true
                    body.copy(start, length + 2L)
                }
                0xDA -> {
                    if (!frame || !fits(marker, c.bytes(length - 2))) throw NotFollowed("a bad scan")
                    body.copy(start, length + 2L)
                    c.pos = payload + length - 2
                    val data = c.pos
                    marker = scanEnd(c)
                    // The compressed data, up to the marker that ends it, exactly as it was.
                    body.copy(data, c.pos - 2 - data)
                    scans++
                    continue
                }
                else -> throw NotFollowed("unknown marker")
            }
            c.pos = payload + length - 2
            marker = nextMarker(c)
        }
        val out = PlanBuilder()
        out.put(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        jfif?.let { out.put(segment(0xE0, it)) }
        if (orientation != 1) out.put(segment(0xE1, EXIF + byteArrayOf(0) + Orientation.tiff(orientation)))
        out.addAll(body)
        return out.build()
    }

    /** Whether a table, frame or scan header [p] is exactly the size its own counts give. */
    private fun fits(marker: Int, p: ByteArray): Boolean {
        fun u(i: Int) = p[i].toInt() and 0xFF
        return when (marker) {
            0xDB -> { // quantisation tables: 65 or 129 bytes each
                var i = 0
                while (i < p.size) i += 1 + 64 * (1 + (u(i) shr 4))
                i == p.size && p.isNotEmpty()
            }
            0xC4 -> { // Huffman tables: 17 bytes and the codes they count
                var i = 0
                while (i + 17 <= p.size) i += 17 + (1..16).sumOf { u(i + it) }
                i == p.size && p.isNotEmpty()
            }
            0xCC -> p.isNotEmpty() && p.size % 2 == 0
            0xDD, 0xDC -> p.size == 2
            0xDF -> p.size == 1
            0xDA -> p.isNotEmpty() && u(0) in 1..4 && p.size == 4 + 2 * u(0)
            else -> p.size >= 6 && u(5) >= 1 && p.size == 6 + 3 * u(5) // a frame, or DHP
        }
    }

    /** The next marker's code; fill bytes (FF FF …) before it are allowed, anything else is not. */
    private fun nextMarker(c: Cursor): Int {
        if (c.u8() != 0xFF) throw NotFollowed("bytes between segments")
        var m = c.u8()
        while (m == 0xFF) m = c.u8()
        if (m == 0x00) throw NotFollowed("not a marker")
        return m
    }

    /**
     * Reads through a scan's compressed data, which ends at the first marker that isn't a stuffed
     * byte (FF 00) or a restart (FF D0 to D7). Leaves [c] just past that marker and returns it.
     */
    private fun scanEnd(c: Cursor): Int {
        while (true) {
            if (c.u8() != 0xFF) continue
            var m = c.u8()
            while (m == 0xFF) m = c.u8()
            if (m == 0x00 || m in 0xD0..0xD7) continue
            // Point at the marker's own FF, so fill bytes before it stay with the scan.
            return m
        }
    }

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }
}
