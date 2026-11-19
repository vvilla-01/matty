package ceo.villa.matty.data.media

import java.util.zip.CRC32

/**
 * A PNG (or an animated PNG) without its metadata, losslessly: its chunks are walked and only
 * those that draw the picture are kept, each byte for byte with its own checksum. Text (tEXt,
 * zTXt, iTXt, where XMP lives), eXIf, tIME and every chunk this doesn't know go, and so does
 * anything after the end. Colour (gAMA, cHRM, sRGB, iCCP, cICP and the HDR notes), transparency
 * and animation stay. As with a JPEG, a picture that isn't stored upright keeps an eXIf holding
 * only its Orientation.
 */
internal object PngCleaner {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val KEEP = setOf(
        "IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "cICP", "mDCV", "cLLI",
        "sBIT", "bKGD", "pHYs", "acTL", "fcTL", "fdAT",
    )

    /** Null when the file can't be followed. */
    fun clean(src: Source): Cleaned? = try { plan(src) } catch (e: NotFollowed) { null }

    private fun plan(src: Source): Cleaned {
        val c = Cursor(src)
        if (!c.bytes(8).contentEquals(SIGNATURE)) throw NotFollowed("not a PNG")
        var orientation = 1
        var exifSeen = false
        var idat = false
        // The signature and the header (13 bytes of data, 25 with its length, type and checksum).
        if (c.u32be() != 13L || String(c.bytes(4), Charsets.ISO_8859_1) != "IHDR") throw NotFollowed("no header")
        c.skip(13 + 4)
        val kept = PlanBuilder()
        while (true) {
            val start = c.pos
            val length = c.u32be()
            if (length > Int.MAX_VALUE) throw NotFollowed("chunk too long")
            val type = String(c.bytes(4), Charsets.ISO_8859_1)
            if (!type.all { it in 'A'..'Z' || it in 'a'..'z' }) throw NotFollowed("not a chunk")
            if (length + 4 > c.remaining) throw NotFollowed("chunk runs past the end")
            when {
                type == "IHDR" -> throw NotFollowed("a second header")
                type in KEEP -> kept.copy(start, length + 12)
                type == "eXIf" -> {
                    // Read only for its Orientation, and only when it is of a sensible size.
                    if (!exifSeen && length <= 256 * 1024) orientation = Orientation.read(c.bytes(length.toInt()))
                    exifSeen = true
                }
                type[0] in 'A'..'Z' -> throw NotFollowed("a critical chunk this doesn't know")
                else -> Unit // metadata and anything unknown: dropped
            }
            if (type == "IDAT") idat = true
            c.pos = start + 8 + length + 4
            if (type == "IEND") break
        }
        if (!idat) throw NotFollowed("no picture")
        val out = PlanBuilder()
        out.copy(0, 8 + 25)
        // eXIf has to come before the picture's data: straight after the header.
        if (orientation != 1) out.put(chunk("eXIf", Orientation.tiff(orientation)))
        out.addAll(kept)
        return out.build()
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val t = ascii(type)
        val crc = CRC32().apply { update(t); update(data) }.value
        val n = data.size
        return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + t + data +
            byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
    }
}
