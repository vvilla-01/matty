package ceo.villa.matty.data.media

/** What a file is, told from its first bytes: never from its name or the type another app claims for it. */
enum class MediaKind(val mime: String?) {
    Jpeg("image/jpeg"), Png("image/png"), Webp("image/webp"), Gif("image/gif"),
    /** MP4, MOV, 3GP, M4V and the rest of the ISO base media family, holding a movie (or sound). */
    IsoMovie(null),
    /** A picture of a kind that can only be drawn again from its pixels: HEIC, AVIF, DNG and other raw files, TIFF, BMP, ICO, JPEG XL, JPEG 2000, JPEG XR, MNG, PSD. */
    OtherPicture(null),
    Unknown(null);

    companion object {
        /** How many first bytes [of] wants to see. */
        const val HEAD = 256

        private val HEIF_BRANDS = setOf(
            "heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "mif2", "msf1",
            "avif", "avis", "avio", "avci", "avcs", "crx ", "jpeg", "jpgs",
        )
        private val MOVIE_BRANDS = setOf(
            "isom", "iso2", "iso3", "iso4", "iso5", "iso6", "iso8", "mp41", "mp42", "mp71", "avc1", "qt  ",
            "3gp4", "3gp5", "3gp6", "3gp7", "3gp8", "3gp9", "3g2a", "3g2b", "3g2c", "3gs7", "M4V ", "M4VH", "M4VP",
            "M4A ", "M4B ", "MSNV", "dash", "mmp4", "XAVC", "CAEP", "f4v ",
        )
        /** QuickTime files can start without ftyp. */
        private val BARE_MOVIE_BOXES = setOf("moov", "mdat", "wide", "free", "skip", "pnot")

        fun of(head: ByteArray): MediaKind {
            fun at(i: Int) = if (i < head.size) head[i].toInt() and 0xFF else -1
            fun text(from: Int, n: Int) = if (from + n <= head.size) String(head, from, n, Charsets.ISO_8859_1) else ""
            fun u32(i: Int): Long = if (i + 4 <= head.size) ((at(i).toLong() shl 24) or (at(i + 1).toLong() shl 16) or (at(i + 2).toLong() shl 8) or at(i + 3).toLong()) else -1
            fun u16le(i: Int) = if (i + 2 <= head.size) at(i) or (at(i + 1) shl 8) else -1
            return when {
                at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> Jpeg
                head.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> Png
                text(0, 4) == "RIFF" && text(8, 4) == "WEBP" -> Webp
                text(0, 6) == "GIF87a" || text(0, 6) == "GIF89a" -> Gif
                text(4, 4) == "ftyp" -> {
                    val size = u32(0).coerceIn(8, head.size.toLong()).toInt()
                    val major = text(8, 4)
                    val compatible = (16 until size step 4).map { text(it, 4) }
                    when {
                        major in HEIF_BRANDS -> OtherPicture
                        major !in MOVIE_BRANDS && compatible.any { it in HEIF_BRANDS } -> OtherPicture
                        else -> IsoMovie
                    }
                }
                text(4, 4) in BARE_MOVIE_BOXES && u32(0) >= 8 -> IsoMovie
                // TIFF and the raw formats built on it (DNG, NEF, ARW, CR2…), BigTIFF, Olympus and Panasonic raws.
                text(0, 4) == "II*\u0000" || text(0, 4) == "MM\u0000*" || text(0, 4) == "IIRO" || text(0, 4) == "IIU\u0000" -> OtherPicture
                text(0, 4) == "II+\u0000" || text(0, 4) == "MM\u0000+" -> OtherPicture
                // Fujifilm, Canon CRW, Minolta and Sigma raws.
                text(0, 15) == "FUJIFILMCCD-RAW" -> OtherPicture
                text(0, 2) == "II" && text(6, 8) == "HEAPCCDR" -> OtherPicture
                text(0, 4) == "\u0000MRM" || text(0, 4) == "FOVb" -> OtherPicture
                // JPEG XR (HD Photo: Windows' HDR screenshots), and PNG's cousins MNG and JNG.
                text(0, 3) == "II\u00BC" -> OtherPicture
                (at(0) == 0x8A && text(1, 7) == "MNG\r\n\u001A\n") || (at(0) == 0x8B && text(1, 7) == "JNG\r\n\u001A\n") -> OtherPicture
                // BMP, told by the size of the header that follows its own.
                text(0, 2) == "BM" && u16le(14) in setOf(12, 40, 52, 56, 64, 108, 124) && at(15) == 0 && at(16) == 0 && at(17) == 0 -> OtherPicture
                // ICO and CUR: a count of pictures, and a first entry whose reserved byte is zero.
                at(0) == 0 && at(1) == 0 && (at(2) == 1 || at(2) == 2) && at(3) == 0 && u16le(4) in 1..256 && at(9) == 0 -> OtherPicture
                // JPEG XL (bare and in its container), JPEG 2000, Photoshop.
                at(0) == 0xFF && at(1) == 0x0A -> OtherPicture
                u32(0) == 12L && (text(4, 4) == "JXL " || text(4, 4) == "jP  ") -> OtherPicture
                text(0, 4) == "8BPS" -> OtherPicture
                else -> Unknown
            }
        }
    }
}

/** How a file the person picked is made ready to go up. */
sealed interface Route {
    /** Not a picture or a video this can see: it goes as it is, as every document does. */
    data object AsIs : Route
    /** Cleaned in its own format, losslessly; drawn again if that can't be done. */
    data class Lossless(val kind: MediaKind) : Route
    /** A video cleaned in place; not attached if that can't be done. */
    data object Movie : Route
    /**
     * Drawn again from its pixels. When that fails too, a picture by its bytes ([picture]) is not
     * attached; a file only another app called a picture goes as it is (an SVG, say).
     */
    data class Redraw(val picture: Boolean) : Route

    companion object {
        fun of(head: ByteArray, claimedMime: String): Route = when (val kind = MediaKind.of(head)) {
            MediaKind.Jpeg, MediaKind.Png, MediaKind.Webp, MediaKind.Gif -> Lossless(kind)
            MediaKind.IsoMovie -> Movie
            MediaKind.OtherPicture -> Redraw(picture = true)
            MediaKind.Unknown -> if (claimedMime.lowercase().startsWith("image/")) Redraw(picture = false) else AsIs
        }
    }
}

/**
 * The type a cleaned movie goes up as: the one another app gave it when that names a video or a
 * sound, else the one its brand says.
 */
fun movieMime(head: ByteArray, claimedMime: String): String {
    val claimed = claimedMime.lowercase()
    if (claimed.startsWith("video/") || claimed.startsWith("audio/")) return claimed
    val brand = if (head.size >= 12 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp") String(head, 8, 4, Charsets.ISO_8859_1) else "qt  "
    return when {
        brand == "qt  " -> "video/quicktime"
        brand.startsWith("3g2") -> "video/3gpp2"
        brand.startsWith("3g") -> "video/3gpp"
        brand == "M4A " || brand == "M4B " -> "audio/mp4"
        brand.startsWith("M4V") -> "video/x-m4v"
        else -> "video/mp4"
    }
}

/** The lossless cleaner for [kind], or null when that can't be done. */
fun cleanLossless(kind: MediaKind, src: Source): Cleaned? = when (kind) {
    MediaKind.Jpeg -> JpegCleaner.clean(src)
    MediaKind.Png -> PngCleaner.clean(src)
    MediaKind.Webp -> WebpCleaner.clean(src)
    MediaKind.Gif -> GifCleaner.clean(src)
    else -> null
}

/** A video cleaned in place, or null when it can't be followed. */
fun cleanMovie(src: Source): Cleaned? = IsoCleaner.clean(src)

/** What the person is told when a file goes nowhere because of what it carries. */
fun notAttached(name: String) = "$name wasn't attached because its hidden details couldn't be removed."

/** What the person is told when a file goes nowhere because the phone has no room to clean it. */
fun noRoomToClean(name: String) = "$name wasn't attached because the phone needs more free space to remove its hidden details."

/** Kept free beside the private copy a file is cleaned in, so attaching never fills the phone. */
const val CLEANING_SPARE_BYTES = 64L * 1024 * 1024

/** Whether a private copy of a file [length] bytes long fits in [usable] free bytes; a length the other app doesn't say is tried. */
fun roomToClean(length: Long, usable: Long) = length < 0 || length <= usable - CLEANING_SPARE_BYTES
