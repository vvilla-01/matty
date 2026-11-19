package ceo.villa.matty.data.media

/**
 * The one piece of EXIF a cleaned picture keeps: which way up it is. A camera saves the sensor's
 * pixels as they came and notes the turn here; dropping the note would show the photo on its side.
 */
internal object Orientation {
    /** The Orientation tag (1 to 8) in a TIFF block (EXIF without its "Exif" prefix); 1 when unsure. Never throws. */
    fun read(tiff: ByteArray): Int {
        if (tiff.size < 8) return 1
        val little = when {
            tiff[0] == 'I'.code.toByte() && tiff[1] == 'I'.code.toByte() -> true
            tiff[0] == 'M'.code.toByte() && tiff[1] == 'M'.code.toByte() -> false
            else -> return 1
        }
        fun u16(at: Int) = if (little) (tiff[at].toInt() and 0xFF) or ((tiff[at + 1].toInt() and 0xFF) shl 8)
        else ((tiff[at].toInt() and 0xFF) shl 8) or (tiff[at + 1].toInt() and 0xFF)
        fun u32(at: Int): Long = if (little) u16(at).toLong() or (u16(at + 2).toLong() shl 16) else (u16(at).toLong() shl 16) or u16(at + 2).toLong()
        if (u16(2) != 42) return 1
        val ifd = u32(4)
        if (ifd < 8 || ifd + 2 > tiff.size) return 1
        val count = u16(ifd.toInt())
        for (i in 0 until count) {
            val entry = ifd.toInt() + 2 + 12 * i
            if (entry + 12 > tiff.size) break
            if (u16(entry) != 0x0112) continue
            // SHORT, one value: kept in the entry itself.
            if (u16(entry + 2) != 3 || u32(entry + 4) != 1L) return 1
            return u16(entry + 8).takeIf { it in 1..8 } ?: 1
        }
        return 1
    }

    /** A TIFF block holding nothing but [orientation]: what a cleaned picture carries when it isn't upright as stored. */
    fun tiff(orientation: Int): ByteArray = byteArrayOf(
        'M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8, // big-endian, first directory at 8
        0, 1, // one entry
        0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0, // Orientation, SHORT, 1 value
        0, 0, 0, 0, // no further directory
    )
}
