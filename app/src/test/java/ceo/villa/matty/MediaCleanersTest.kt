package ceo.villa.matty

import ceo.villa.matty.data.media.ArraySource
import ceo.villa.matty.data.media.Cleaned
import ceo.villa.matty.data.media.FileSource
import ceo.villa.matty.data.media.MediaKind
import ceo.villa.matty.data.media.Orientation
import ceo.villa.matty.data.media.Piece
import ceo.villa.matty.data.media.Route
import ceo.villa.matty.data.media.cleanLossless
import ceo.villa.matty.data.media.cleanMovie
import ceo.villa.matty.data.media.movieMime
import ceo.villa.matty.data.media.CLEANING_SPARE_BYTES
import ceo.villa.matty.data.media.noRoomToClean
import ceo.villa.matty.data.media.notAttached
import ceo.villa.matty.data.media.roomToClean
import ceo.villa.matty.data.media.withContextOrDiscard
import ceo.villa.matty.plugins.villanet.cleanBannerGif
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import javax.imageio.ImageIO

/**
 * Pictures and videos leave the phone without their hidden details. Each sample under
 * test/resources/media was made with exiftool, ffmpeg and ImageMagick and carries a position, a
 * camera, times, XMP, comments and the rest, with "Secret…" written into every field that holds
 * text: none of it may survive, and the picture must stay as it was.
 */
class MediaCleanersTest {
    private fun sample(name: String) = javaClass.getResourceAsStream("/media/$name")!!.use { it.readBytes() }

    private fun clean(kind: MediaKind, bytes: ByteArray): ByteArray? {
        val src = ArraySource(bytes)
        return cleanLossless(kind, src)?.toByteArray(src)
    }

    private fun movie(bytes: ByteArray): ByteArray? {
        val src = ArraySource(bytes)
        return cleanMovie(src)?.toByteArray(src)
    }

    private fun ByteArray.has(text: String) = indexOf(text.toByteArray(Charsets.ISO_8859_1)) >= 0
    private fun ByteArray.indexOf(needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun assertNoneOf(out: ByteArray, vararg planted: String) = planted.forEach { assertFalse("\"$it\" survived", out.has(it)) }

    private fun pixels(bytes: ByteArray): IntArray {
        val img = ImageIO.read(ByteArrayInputStream(bytes))!!
        return img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
    }

    // ---- JPEG -----------------------------------------------------------------------------

    /** The segments of a JPEG up to its first scan, as (marker, payload). */
    private fun jpegSegments(b: ByteArray): List<Pair<Int, ByteArray>> {
        val out = ArrayList<Pair<Int, ByteArray>>()
        var i = 2
        while (i + 4 <= b.size) {
            val m = b[i + 1].toInt() and 0xFF
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            out += m to b.copyOfRange(i + 4, i + 2 + len)
            if (m == 0xDA) break
            i += 2 + len
        }
        return out
    }

    /** Where the picture's first scan starts (past any thumbnail, which sits inside EXIF). */
    private fun scanAt(b: ByteArray): Int {
        var i = 2
        while (b[i + 1].toInt() and 0xFF != 0xDA) i += 2 + (((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF))
        return i
    }

    @Test fun `a JPEG loses its position, camera, times, XMP, IPTC, comment, thumbnail and trailer`() {
        val original = sample("photo.jpg")
        // The sample really does carry all of it.
        listOf("SecretMaker", "SecretModel", "SecretArtist", "SecretXmp", "SecretIptc", "SecretComment", "SecretTrailer", "Photoshop 3.0", "http://ns.adobe.com/xap/1.0/").forEach { assertTrue(it, original.has(it)) }
        val out = clean(MediaKind.Jpeg, original)!!
        assertNoneOf(out, "SecretMaker", "SecretModel", "SecretArtist", "SecretXmp", "SecretIptc", "SecretComment", "SecretTrailer", "Photoshop 3.0", "http://ns.adobe.com/xap/1.0/", "2026:11:10")
        // No embedded thumbnail: the only start of a JPEG is the picture's own.
        assertEquals(0, out.indexOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertEquals(-1, out.indexOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), 1))
        // Ends at the picture's end.
        assertEquals(0xD9, out.last().toInt() and 0xFF)
        // The compressed picture is byte for byte what it was, and it decodes to the same pixels.
        val scanIn = original.copyOfRange(scanAt(original), original.indexOf("SecretTrailer".toByteArray()))
        val scanOut = out.copyOfRange(scanAt(out), out.size)
        assertArrayEquals(scanIn, scanOut)
        assertArrayEquals(pixels(original), pixels(out))
    }

    @Test fun `a JPEG that isn't upright keeps an EXIF holding only its Orientation`() {
        val out = clean(MediaKind.Jpeg, sample("photo.jpg"))!!
        val app1 = jpegSegments(out).filter { it.first == 0xE1 }
        assertEquals(1, app1.size)
        val exif = app1[0].second
        assertTrue(exif.copyOfRange(0, 6).contentEquals("Exif\u0000\u0000".toByteArray()))
        val tiff = exif.copyOfRange(6, exif.size)
        assertEquals(6, Orientation.read(tiff))
        assertArrayEquals(Orientation.tiff(6), tiff)
        // JFIF stays, without a thumbnail.
        val jfif = jpegSegments(out).single { it.first == 0xE0 }.second
        assertEquals(14, jfif.size)
        assertEquals(0, jfif[12].toInt())
        assertEquals(0, jfif[13].toInt())
    }

    /** [base] with [segments] put straight after its start, and [tail] after its end. */
    private fun jpegWith(base: ByteArray, segments: List<Pair<Int, ByteArray>>, tail: ByteArray = ByteArray(0)): ByteArray {
        val extra = segments.flatMap { (m, p) -> listOf(0xFF.toByte(), m.toByte(), ((p.size + 2) shr 8).toByte(), (p.size + 2).toByte()) + p.toList() }
        return base.copyOfRange(0, 2) + extra.toByteArray() + base.copyOfRange(2, base.size) + tail
    }

    private val upright: ByteArray by lazy {
        // The sample without its trailer and with its EXIF turned upright, as a base to build on.
        val s = sample("photo.jpg")
        val src = ArraySource(s)
        val clean = cleanLossless(MediaKind.Jpeg, src)!!.toByteArray(src)
        val segs = jpegSegments(clean)
        // Drop the Orientation-only EXIF.
        val at = clean.indexOf(byteArrayOf(0xFF.toByte(), 0xE1.toByte()))
        val len = ((clean[at + 2].toInt() and 0xFF) shl 8) or (clean[at + 3].toInt() and 0xFF)
        assertTrue(segs.isNotEmpty())
        clean.copyOfRange(0, at) + clean.copyOfRange(at + 2 + len, clean.size)
    }

    @Test fun `a JPEG keeps its colour profile and Adobe note and loses MPF, extended XMP, JUMBF and a gain map`() {
        val icc = "ICC_PROFILE\u0000\u0001\u0001fakeprofile".toByteArray(Charsets.ISO_8859_1)
        val adobe = "Adobe".toByteArray() + byteArrayOf(0, 100, 0, 0, 0, 0, 1)
        val mpf = "MPF\u0000MMSecretMpf".toByteArray(Charsets.ISO_8859_1)
        val extXmp = "http://ns.adobe.com/xmp/extension/\u0000SecretExtended".toByteArray(Charsets.ISO_8859_1)
        val jumbf = "JPSecretJumbf".toByteArray()
        val gainMap = upright // a second JPEG after the end, as Ultra HDR stores its gain map
        val input = jpegWith(upright, listOf(0xE2 to icc, 0xEE to adobe, 0xE2 to mpf, 0xE1 to extXmp, 0xEB to jumbf, 0xFE to "SecretCom".toByteArray()), gainMap + "SecretAfter".toByteArray())
        val out = clean(MediaKind.Jpeg, input)!!
        assertNoneOf(out, "SecretMpf", "SecretExtended", "SecretJumbf", "SecretCom", "SecretAfter", "MPF", "Exif")
        val segs = jpegSegments(out)
        assertTrue(segs.any { it.first == 0xE2 && it.second.contentEquals(icc) })
        assertTrue(segs.any { it.first == 0xEE && it.second.contentEquals(adobe) })
        assertEquals(-1, out.indexOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte()), 1))
        // Upright as it is: no EXIF at all.
        assertTrue(segs.none { it.first == 0xE1 })
        assertArrayEquals(pixels(upright), pixels(out))
    }

    @Test fun `a JPEG that can't be followed is left to be drawn again`() {
        val good = sample("photo.jpg")
        // Cut inside the compressed picture: no end.
        assertNull(clean(MediaKind.Jpeg, good.copyOfRange(0, good.size - 400)))
        // A segment whose length runs past the end.
        assertNull(clean(MediaKind.Jpeg, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0xFF.toByte(), 0xFF.toByte(), 1, 2, 3)))
        // A length under its own two bytes.
        assertNull(clean(MediaKind.Jpeg, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDB.toByte(), 0, 1)))
        // An end with no picture before it.
        assertNull(clean(MediaKind.Jpeg, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())))
    }

    // ---- PNG ------------------------------------------------------------------------------

    private class Chunk(val type: String, val data: ByteArray, val crcOk: Boolean)

    private fun pngChunks(b: ByteArray): List<Chunk> {
        val out = ArrayList<Chunk>()
        var i = 8
        while (i + 12 <= b.size) {
            val n = ByteBuffer.wrap(b, i, 4).int
            val type = String(b, i + 4, 4, Charsets.ISO_8859_1)
            val data = b.copyOfRange(i + 8, i + 8 + n)
            val crc = CRC32().apply { update(b, i + 4, 4 + n) }.value.toInt()
            out += Chunk(type, data, crc == ByteBuffer.wrap(b, i + 8 + n, 4).int)
            i += 12 + n
        }
        return out
    }

    @Test fun `a PNG loses its text, XMP, eXIf and time, and keeps its colour and transparency`() {
        val original = sample("photo.png")
        val before = pngChunks(original).map { it.type }
        listOf("tEXt", "zTXt", "iTXt", "eXIf", "tIME").forEach { assertTrue(it, it in before) }
        val out = clean(MediaKind.Png, original)!!
        assertNoneOf(out, "SecretPngComment", "SecretPngArtist", "SecretXmp", "SecretMaker", "tEXt", "zTXt", "iTXt", "tIME")
        val chunks = pngChunks(out)
        assertTrue(chunks.all { it.crcOk })
        assertEquals(listOf("IHDR", "eXIf", "cHRM", "bKGD", "IDAT", "IEND"), chunks.map { it.type })
        // The one eXIf holds nothing but the Orientation (8 in the sample).
        assertArrayEquals(Orientation.tiff(8), chunks.single { it.type == "eXIf" }.data)
        assertArrayEquals(pngChunks(original).single { it.type == "IDAT" }.data, chunks.single { it.type == "IDAT" }.data)
        assertArrayEquals(pixels(original), pixels(out))
        // Still see-through where it was.
        assertTrue(pixels(out).any { it ushr 24 != 0xFF })
    }

    @Test fun `an animated PNG stays animated`() {
        val original = sample("anim.png")
        val out = clean(MediaKind.Png, original)!!
        assertNoneOf(out, "SecretApng", "SecretXmp")
        assertEquals(listOf("IHDR", "pHYs", "acTL", "fcTL", "IDAT", "fcTL", "fdAT", "IEND"), pngChunks(out).map { it.type })
        val frames = { b: ByteArray -> pngChunks(b).filter { it.type in setOf("acTL", "fcTL", "fdAT", "IDAT") }.map { it.data.toList() } }
        assertEquals(frames(original), frames(out))
    }

    @Test fun `a PNG with a critical chunk it doesn't know, or a length past the end, is left to be drawn again`() {
        val original = sample("photo.png")
        val iend = original.size - 12
        val bogus = byteArrayOf(0, 0, 0, 0) + "ZZZZ".toByteArray() + byteArrayOf(0, 0, 0, 0)
        assertNull(clean(MediaKind.Png, original.copyOfRange(0, iend) + bogus + original.copyOfRange(iend, original.size)))
        val huge = original.copyOf().also { ByteBuffer.wrap(it, 33, 4).putInt(0x7FFFFFFF) }
        assertNull(clean(MediaKind.Png, huge))
        val overflow = original.copyOf().also { ByteBuffer.wrap(it, 33, 4).putInt(-1) }
        assertNull(clean(MediaKind.Png, overflow))
        assertNull(clean(MediaKind.Png, original.copyOfRange(0, iend)))
    }

    // ---- WebP -----------------------------------------------------------------------------

    private fun webpChunks(b: ByteArray): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        var i = 12
        while (i + 8 <= b.size) {
            val n = ByteBuffer.wrap(b, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            out += String(b, i, 4, Charsets.ISO_8859_1) to b.copyOfRange(i + 8, i + 8 + n)
            i += 8 + n + (n and 1)
        }
        return out
    }

    @Test fun `a WebP loses its EXIF and XMP, keeping only its Orientation`() {
        val original = sample("photo.webp")
        val out = clean(MediaKind.Webp, original)!!
        assertNoneOf(out, "SecretMaker", "SecretXmp", "XMP ", "adobe")
        assertEquals(out.size - 8, ByteBuffer.wrap(out, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int)
        val chunks = webpChunks(out)
        assertEquals(listOf("VP8X", "VP8L", "EXIF"), chunks.map { it.first })
        assertArrayEquals(Orientation.tiff(3), chunks.last().second)
        // Alpha and EXIF are flagged, XMP no longer is.
        assertEquals(0x10 or 0x08, chunks[0].second[0].toInt() and 0x3E)
        assertArrayEquals(webpChunks(original).single { it.first == "VP8L" }.second, chunks[1].second)
    }

    @Test fun `an animated WebP stays animated and loses its metadata`() {
        val original = sample("anim.webp")
        val out = clean(MediaKind.Webp, original)!!
        assertNoneOf(out, "SecretMaker", "SecretXmp", "EXIF", "XMP ")
        val chunks = webpChunks(out)
        assertEquals(listOf("VP8X", "ANIM", "ANMF", "ANMF"), chunks.map { it.first })
        assertEquals(0x02, chunks[0].second[0].toInt() and 0x0E)
        assertEquals(webpChunks(original).filter { it.first.startsWith("AN") }.map { it.second.toList() }, chunks.drop(1).map { it.second.toList() })
        assertEquals(out.size - 8, ByteBuffer.wrap(out, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int)
    }

    @Test fun `a WebP whose sizes don't hold is left to be drawn again`() {
        val original = sample("photo.webp")
        assertNull(clean(MediaKind.Webp, original.copyOf().also { ByteBuffer.wrap(it, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(-2) }))
        assertNull(clean(MediaKind.Webp, original.copyOf().also { ByteBuffer.wrap(it, 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x7FFFFFF0) }))
        assertNull(clean(MediaKind.Webp, original.copyOfRange(0, 40)))
    }

    // ---- GIF ------------------------------------------------------------------------------

    @Test fun `a GIF loses its comment and XMP and keeps its frames and loop`() {
        val original = sample("anim.gif")
        assertTrue(original.has("SecretGifComment") && original.has("XMP DataXMP"))
        val out = clean(MediaKind.Gif, original)!!
        assertNoneOf(out, "SecretGifComment", "SecretXmp", "XMP DataXMP")
        assertTrue(out.has("NETSCAPE2.0"))
        assertEquals(0x3B, out.last().toInt())
        val frames = { b: ByteArray ->
            val r = ImageIO.getImageReadersByFormatName("gif").next()
            r.input = ImageIO.createImageInputStream(ByteArrayInputStream(b))
            (0 until r.getNumImages(true)).map { i -> r.read(i).let { it.getRGB(0, 0, it.width, it.height, null, 0, it.width).toList() } }
        }
        assertEquals(2, frames(out).size)
        assertEquals(frames(original), frames(out))
    }

    @Test fun `a banner's GIF goes through the same cleaner`() {
        val out = cleanBannerGif(sample("anim.gif"))!!
        assertNoneOf(out, "SecretGifComment", "SecretXmp")
        assertNull(cleanBannerGif("GIF89a".toByteArray()))
    }

    // ---- video ----------------------------------------------------------------------------

    /** Every box down the movie's tree, as (path, start, size, header). */
    private fun boxes(b: ByteArray, start: Int = 0, end: Int = b.size, path: String = ""): List<Triple<String, Int, Int>> {
        val out = ArrayList<Triple<String, Int, Int>>()
        var i = start
        while (i + 8 <= end) {
            val n = ByteBuffer.wrap(b, i, 4).int.let { if (it == 0) end - i else it }
            val t = String(b, i + 4, 4, Charsets.ISO_8859_1)
            out += Triple("$path/$t", i, n)
            if (t in setOf("moov", "trak", "mdia", "udta", "minf", "stbl", "moof", "traf")) out += boxes(b, i + 8, i + n, "$path/$t")
            i += n
        }
        return out
    }

    private fun checkMovie(name: String) {
        val original = sample(name)
        assertTrue(original.has("Secret"))
        val out = movie(original)!!
        // Nothing moves: same size, and the media data is byte for byte where it was.
        assertEquals(original.size, out.size)
        assertNoneOf(out, "Secret", "+48.8577", "48.8577", "com.apple.quicktime", "xmpmeta", "loci", "udta", "keys", "ilst")
        val inBoxes = boxes(original)
        val outBoxes = boxes(out)
        assertTrue(outBoxes.none { it.first.endsWith("/udta") || it.first.endsWith("/meta") || it.first.endsWith("/uuid") })
        val mdat = inBoxes.single { it.first == "/mdat" }
        assertArrayEquals(original.copyOfRange(mdat.second, mdat.second + mdat.third), out.copyOfRange(mdat.second, mdat.second + mdat.third))
        // Everything a player reads is untouched.
        for (keep in listOf("/ftyp", "/moov/trak/edts", "/moov/trak/mdia/minf", "/moov/trak/mdia/hdlr")) {
            inBoxes.filter { it.first.startsWith(keep) }.forEach { (_, at, n) -> assertArrayEquals(keep, original.copyOfRange(at, at + n), out.copyOfRange(at, at + n)) }
        }
        // Times are zero; everything else in those headers (duration, the rotation matrix) is as it was.
        for (head in outBoxes.filter { it.first.endsWith("/mvhd") || it.first.endsWith("/tkhd") || it.first.endsWith("/mdhd") }) {
            val at = head.second
            assertTrue(out.copyOfRange(at + 12, at + 20).all { it == 0.toByte() })
            assertFalse(original.copyOfRange(at + 12, at + 20).all { it == 0.toByte() })
            assertArrayEquals(original.copyOfRange(at + 20, at + head.third), out.copyOfRange(at + 20, at + head.third))
        }
        // The blanked boxes are free boxes of zeros.
        for (gone in inBoxes.filter { it.first.count { c -> c == '/' } <= 2 && (it.first.endsWith("/udta") || it.first.endsWith("/meta") || it.first.endsWith("/uuid")) }) {
            assertEquals("free", String(out, gone.second + 4, 4, Charsets.ISO_8859_1))
            assertTrue(out.copyOfRange(gone.second + 8, gone.second + gone.third).all { it == 0.toByte() })
        }
    }

    @Test fun `an MP4 loses its position, keys, XMP and times, in place`() = checkMovie("movie.mp4")

    @Test fun `a MOV loses its position, keys, XMP and times, in place`() = checkMovie("movie.mov")

    @Test fun `a recording made in fragments loses the user data and metadata in its fragments too`() {
        // An ffmpeg frag_keyframe+empty_moov MP4 with a moof/udta/©xyz position, a moof/traf/meta
        // with Android's keys and a moof/traf/udta maker added (trun offsets moved to match).
        val original = sample("fragmented.mp4")
        assertTrue(original.has("SecretMoofUdta+12.34-076.54/"))
        val out = movie(original)!!
        assertEquals(original.size, out.size)
        assertNoneOf(out, "Secret", "+12.34", "com.android", "\u00A9xyz")
        val inBoxes = boxes(original)
        val outBoxes = boxes(out)
        assertTrue(inBoxes.any { it.first == "/moof/udta" } && inBoxes.any { it.first == "/moof/traf/meta" } && inBoxes.any { it.first == "/moof/traf/udta" })
        assertTrue(outBoxes.none { it.first.endsWith("/udta") || it.first.endsWith("/meta") })
        for (gone in inBoxes.filter { it.first.startsWith("/moof") && (it.first.endsWith("/udta") || it.first.endsWith("/meta")) }) {
            assertEquals("free", String(out, gone.second + 4, 4, Charsets.ISO_8859_1))
        }
        // What plays the fragments is untouched: their headers, sample tables and media data.
        for ((path, at, n) in inBoxes.filter { it.first in setOf("/moof/mfhd", "/moof/traf/tfhd", "/moof/traf/tfdt", "/moof/traf/trun", "/mdat") }) {
            assertArrayEquals(path, original.copyOfRange(at, at + n), out.copyOfRange(at, at + n))
        }
    }

    @Test(timeout = 30_000) fun `a long recording made one fragment per frame is still cleaned`() {
        // ffmpeg's frag_every_frame writes seven boxes per frame (moof, mfhd, traf, tfhd, tfdt,
        // trun, mdat): 40 000 small fragments are 280 000 boxes, past a fixed 200 000.
        fun box(type: String, vararg parts: ByteArray): ByteArray {
            val body = parts.fold(ByteArray(0)) { acc, p -> acc + p }
            return ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray(Charsets.ISO_8859_1)).put(body).array()
        }
        fun fullBox(type: String, payload: Int) = box(type, ByteArray(4), ByteBuffer.allocate(4).putInt(payload).array())
        val out = java.io.ByteArrayOutputStream()
        out.write(box("ftyp", "isom".toByteArray(), ByteArray(4)))
        out.write(box("moov"))
        val frame = ByteArray(64) { it.toByte() }
        repeat(40_000) { i ->
            val traf = listOf(fullBox("tfhd", 1), fullBox("tfdt", i), box("trun", ByteArray(4), ByteBuffer.allocate(8).putInt(1).putInt(8).array())) +
                (if (i == 39_999) listOf(box("udta", "SecretLastFragment".toByteArray())) else emptyList())
            out.write(box("moof", fullBox("mfhd", i + 1), box("traf", *traf.toTypedArray())))
            out.write(box("mdat", frame))
        }
        val original = out.toByteArray()
        val cleaned = movie(original)
        assertNotNull("a long fragmented recording was refused", cleaned)
        assertEquals(original.size, cleaned!!.size)
        assertNoneOf(cleaned, "Secret")
        // Only the last fragment's user data changed; every frame and fragment header is as it was.
        val udta = original.indexOf("SecretLastFragment".toByteArray()) - 8
        assertArrayEquals(original.copyOfRange(0, udta - 4), cleaned.copyOfRange(0, udta - 4))
    }

    @Test fun `a movie read from a file is cleaned the same, and a vendor's trailer after it goes`() {
        val original = sample("movie.mp4")
        val file = kotlin.io.path.createTempFile("movie", ".mp4").toFile()
        try {
            file.writeBytes(original + byteArrayOf(0x01, 0x02, 0x03) + "\u0000\u0000\u0000\u0010SEFTSecretSamsung".toByteArray(Charsets.ISO_8859_1).let { byteArrayOf(-1, -1) + it })
            val out = FileSource(file).use { src -> cleanMovie(src)!!.toByteArray(src) }
            assertEquals(original.size, out.size)
            assertArrayEquals(movie(original), out)
        } finally {
            file.delete()
        }
    }

    @Test fun `a movie that can't be followed is refused rather than sent`() {
        val original = sample("movie.mp4")
        // The media data claims more than there is (a recording cut short).
        assertNull(movie(original.copyOfRange(0, original.size - 100)))
        // A box shorter than its own header.
        assertNull(movie(byteArrayOf(0, 0, 0, 4) + "ftyp".toByteArray() + ByteArray(8)))
        // A 64-bit size of exabytes.
        assertNull(movie(byteArrayOf(0, 0, 0, 1) + "mdat".toByteArray() + byteArrayOf(0x40, 0, 0, 0, 0, 0, 0, 0)))
        assertNull(movie(byteArrayOf(0, 0, 0, 1) + "mdat".toByteArray() + byteArrayOf(-1, -1, -1, -1, -1, -1, -1, -1)))
        // No movie at all.
        assertNull(movie(byteArrayOf(0, 0, 0, 16) + "ftypisom".toByteArray() + ByteArray(4) + byteArrayOf(0, 0, 0, 8) + "free".toByteArray()))
        // A compressed movie header.
        assertNull(movie(byteArrayOf(0, 0, 0, 16) + "moov".toByteArray() + byteArrayOf(0, 0, 0, 8) + "cmov".toByteArray()))
    }

    @Test(timeout = 10_000) fun `boxes nested past any sense, or by the hundred thousand, don't spin`() {
        // 100 moov-in-trak-in-mdia levels: deeper than the walk goes.
        var inner = byteArrayOf(0, 0, 0, 8) + "free".toByteArray()
        repeat(100) { inner = ByteBuffer.allocate(4).putInt(inner.size + 8).array() + "trak".toByteArray() + inner }
        assertNull(movie(ByteBuffer.allocate(4).putInt(inner.size + 8).array() + "moov".toByteArray() + inner))
        // 300 000 empty boxes inside the movie.
        val many = ByteArray(300_000 * 8) { i -> if (i % 8 == 3) 8 else if (i % 8 >= 4) "free"[i % 8 - 4].code.toByte() else 0 }
        assertNull(movie(ByteBuffer.allocate(4).putInt(many.size + 8).array() + "moov".toByteArray() + many))
    }

    // ---- everything, broken every way --------------------------------------------------------

    private val all = listOf(
        "photo.jpg" to MediaKind.Jpeg, "photo.png" to MediaKind.Png, "anim.png" to MediaKind.Png, "photo.webp" to MediaKind.Webp,
        "anim.webp" to MediaKind.Webp, "anim.gif" to MediaKind.Gif, "movie.mp4" to MediaKind.IsoMovie, "movie.mov" to MediaKind.IsoMovie,
        "fragmented.mp4" to MediaKind.IsoMovie,
    )

    private fun anyCleaner(kind: MediaKind, b: ByteArray) = if (kind == MediaKind.IsoMovie) movie(b) else clean(kind, b)

    @Test(timeout = 60_000) fun `every file cut short anywhere is cleaned or left alone, never a crash`() {
        for ((name, kind) in all) {
            val b = sample(name)
            for (n in 0 until b.size) anyCleaner(kind, b.copyOfRange(0, n))
        }
    }

    @Test(timeout = 60_000) fun `every file with bytes changed at random is cleaned or left alone, never a crash or a hang`() {
        val random = java.util.Random(23)
        for ((name, kind) in all) {
            val b = sample(name)
            repeat(1500) {
                val m = b.copyOf()
                repeat(1 + random.nextInt(4)) { m[random.nextInt(m.size)] = random.nextInt(256).toByte() }
                // Whatever comes out still carries none of what was planted in a field that was dropped.
                val out = anyCleaner(kind, m) ?: return@repeat
                assertFalse(name, out.has("SecretXmp"))
            }
        }
    }

    @Test fun `the Orientation reader takes any bytes`() {
        val random = java.util.Random(7)
        repeat(5000) { Orientation.read(ByteArray(random.nextInt(64)) { random.nextInt(256).toByte() }) }
        assertEquals(1, Orientation.read(ByteArray(0)))
        val lying = Orientation.tiff(6).also { it[7] = 0x7F; it[6] = 0x7F }
        assertEquals(1, Orientation.read(lying))
        assertEquals(1, Orientation.read(Orientation.tiff(9)))
        assertEquals(5, Orientation.read(Orientation.tiff(5)))
    }

    // ---- what a file is, and where it goes ---------------------------------------------------

    @Test fun `what a file is comes from its bytes, not its name or claimed type`() {
        assertEquals(Route.Lossless(MediaKind.Jpeg), Route.of(sample("photo.jpg"), "application/octet-stream"))
        assertEquals(Route.Lossless(MediaKind.Png), Route.of(sample("photo.png"), "text/plain"))
        assertEquals(Route.Lossless(MediaKind.Webp), Route.of(sample("photo.webp"), "image/jpeg"))
        assertEquals(Route.Lossless(MediaKind.Gif), Route.of(sample("anim.gif"), ""))
        assertEquals(Route.Movie, Route.of(sample("movie.mp4"), "application/octet-stream"))
        assertEquals(Route.Movie, Route.of(sample("movie.mov"), "video/quicktime"))
        fun ftyp(major: String, vararg compatible: String): ByteArray {
            val body = major.toByteArray() + ByteArray(4) + compatible.joinToString("").toByteArray()
            return ByteBuffer.allocate(4).putInt(8 + body.size).array() + "ftyp".toByteArray() + body
        }
        assertEquals(Route.Redraw(picture = true), Route.of(ftyp("heic", "mif1", "heic"), "image/heic"))
        assertEquals(Route.Redraw(picture = true), Route.of(ftyp("avif", "mif1"), "application/octet-stream"))
        assertEquals(Route.Redraw(picture = true), Route.of(ftyp("abcd", "mif1"), ""))
        assertEquals(Route.Movie, Route.of(ftyp("mp42", "isom", "mp42"), "image/heic"))
        assertEquals(Route.Movie, Route.of(ftyp("3gp4", "isom"), ""))
        assertEquals(Route.Redraw(picture = true), Route.of("II*\u0000\u0008\u0000\u0000\u0000".toByteArray(), "image/x-adobe-dng"))
        // Not pictures by their bytes: documents go as they are, whatever they are called.
        assertEquals(Route.AsIs, Route.of("%PDF-1.7".toByteArray(), "image/jpeg".replace("image/jpeg", "application/pdf")))
        assertEquals(Route.AsIs, Route.of(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + "webm".toByteArray(), "video/webm"))
        assertEquals(Route.AsIs, Route.of("BMW service notes".toByteArray(), "text/plain"))
        // Called a picture but not one by its bytes: drawn again if the phone can, else sent as it is.
        assertEquals(Route.Redraw(picture = false), Route.of("<svg xmlns=".toByteArray(), "image/svg+xml"))
        assertEquals(MediaKind.OtherPicture, MediaKind.of(byteArrayOf(0xFF.toByte(), 0x0A, 0, 0)))
        assertEquals(MediaKind.Unknown, MediaKind.of(ByteArray(0)))
    }

    @Test fun `pictures ImageDecoder can't read are still pictures by their bytes, so they are refused rather than sent`() {
        fun bytes(vararg parts: Any): ByteArray = parts.fold(ByteArray(0)) { acc, p ->
            acc + when (p) { is String -> p.toByteArray(Charsets.ISO_8859_1); is Int -> byteArrayOf(p.toByte()); else -> p as ByteArray }
        } + ByteArray(16)
        val pictures = mapOf(
            "BigTIFF, Intel" to bytes("II+\u0000", 8, 0, 0, 0),
            "BigTIFF, Motorola" to bytes("MM\u0000+", 0, 8, 0, 0),
            "Canon CRW" to bytes("II", 0x1A, 0, 0, 0, "HEAPCCDR"),
            "Minolta MRW" to bytes("\u0000MRM", 0, 0, 0, 0),
            "Sigma X3F" to bytes("FOVb", 0, 0, 2, 0),
            "JPEG XR" to bytes("II", 0xBC, 1, 8, 0, 0, 0),
            "MNG" to bytes(0x8A, "MNG\r\n\u001A\n"),
            "JNG" to bytes(0x8B, "JNG\r\n\u001A\n"),
        )
        for ((name, head) in pictures) {
            assertEquals(name, MediaKind.OtherPicture, MediaKind.of(head))
            // Whatever the Files picker called it, it is drawn again or not attached: never sent as it is.
            assertEquals(name, Route.Redraw(picture = true), Route.of(head, "image/tiff"))
        }
        // Near misses stay what they were.
        assertEquals(MediaKind.Unknown, MediaKind.of(bytes("II", 0x1A, 0, 0, 0, "HEAPXXXX")))
        assertEquals(MediaKind.Unknown, MediaKind.of(bytes(0x8A, "MNGxxxx")))
    }

    @Test fun `a cleaned movie goes as what it is`() {
        assertEquals("video/mp4", movieMime(sample("movie.mp4"), "application/octet-stream"))
        assertEquals("video/quicktime", movieMime(sample("movie.mov"), ""))
        assertEquals("video/3gpp", movieMime(byteArrayOf(0, 0, 0, 16) + "ftyp3gp4".toByteArray(), "application/octet-stream"))
        assertEquals("video/mp4", movieMime(sample("movie.mov"), "video/mp4"))
    }

    @Test fun `the person is told plainly why a file wasn't attached`() {
        assertEquals("IMG_1.dng wasn't attached because its hidden details couldn't be removed.", notAttached("IMG_1.dng"))
        assertEquals("VID_1.mp4 wasn't attached because the phone needs more free space to remove its hidden details.", noRoomToClean("VID_1.mp4"))
    }

    @Test fun `a file is copied to be cleaned only when the copy fits with room to spare`() {
        val gb = 1L shl 30
        // A 4 GB video with 3 GB free: turned down before any copying.
        assertFalse(roomToClean(4 * gb, 3 * gb))
        assertFalse(roomToClean(4 * gb, 4 * gb))
        assertTrue(roomToClean(4 * gb, 4 * gb + CLEANING_SPARE_BYTES))
        assertTrue(roomToClean(3_000_000, 10 * gb))
        // A length the other app doesn't say is tried.
        assertTrue(roomToClean(-1, 0))
    }

    @Test(timeout = 10_000) fun `a copy made just as the caller is cancelled is discarded, not left behind`() = runBlocking {
        val cleaning = CountDownLatch(1)
        val carryOn = CountDownLatch(1)
        val discarded = AtomicReference<String?>(null)
        val job = launch(Dispatchers.Default) {
            withContextOrDiscard(Dispatchers.IO, { discarded.set(it) }) {
                // The cleaning itself has no point to stop at: it finishes whatever happens meanwhile.
                cleaning.countDown()
                carryOn.await()
                "the private copy"
            }
            throw AssertionError("the result reached a caller that was cancelled")
        }
        cleaning.await()
        job.cancel()
        carryOn.countDown()
        job.join()
        assertEquals("the private copy", discarded.get())
        // A caller still there gets it, and nothing is discarded.
        discarded.set(null)
        assertEquals("kept", withContextOrDiscard(Dispatchers.IO, { discarded.set(it) }) { "kept" })
        assertNull(discarded.get())
    }

    @Test fun `a cleaned file is read out of the original piece by piece`() {
        val src = ArraySource("abcdefgh".toByteArray())
        val c = Cleaned(listOf(Piece.Copy(1, 3), Piece.Put("XY".toByteArray()), Piece.Zeros(2), Piece.Copy(6, 2)))
        assertEquals(9, c.size)
        assertArrayEquals("bcdXY".toByteArray() + byteArrayOf(0, 0) + "gh".toByteArray(), c.toByteArray(src))
        // One byte at a time reads the same.
        val stream = c.open(src)
        val bytes = generateSequence { stream.read().takeIf { it >= 0 } }.map { it.toByte() }.toList().toByteArray()
        assertArrayEquals(c.toByteArray(src), bytes)
        assertNotNull(c)
    }
}
