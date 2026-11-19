package ceo.villa.matty.plugins.villanet

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.net.Uri
import ceo.villa.matty.data.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** The first VillaNET with profile banners: an addition to contract 3, so the version says, never the contract. */
internal const val BANNERS_SINCE = "3.7.0"

/** Whether the VillaNET that answered `GET /access` with [version] keeps banners. Not before it has said. */
internal fun bannersOffered(version: String?): Boolean = !version.isNullOrBlank() && !olderThan(version, BANNERS_SINCE)

/** After the snapshot says the banners have changed, an open profile reads them a random share of this later, so everybody's apps don't ask at once. */
internal const val BANNERS_SPREAD_MS = 3_000L

/**
 * The app's copy of who has a banner (`GET /banners`), for the profile sheet.
 *
 * Nothing is read until a profile opens, and then only when there is no copy or the snapshot's
 * word for the banners ([follow]) is not the copy's version. While a profile stays open, a word
 * that moves reads again a moment later, so a banner set meanwhile shows. One read at a time,
 * with the tag of the copy in hand. A read that fails keeps what there is: the coloured band shows
 * where a banner would have been.
 *
 * [allowed] is whether VillaNET may be asked about banners at all (a server whose VillaNET is too
 * old for them is never asked). Not thread-safe: everything happens on [scope]'s one thread.
 */
internal class BannerShelf(
    private val scope: CoroutineScope,
    private val fetch: suspend (tag: String?) -> VillaNetApi.Tagged<VnBanners>?,
    /** Written here, read by the UI. */
    private val held: MutableStateFlow<VnBanners?>,
    private val allowed: () -> Boolean = { true },
    private val jitter: () -> Double = { Math.random() },
    private val spreadMs: Long = BANNERS_SPREAD_MS,
) {
    private var tag: String? = null

    /** The snapshot's word, as last shown; null before the first. */
    private var word: String? = null
    private var reading: Job? = null
    private var following: Job? = null

    /** Asked to read again while a read was out: one more follows it. */
    private var again = false
    private var open = 0

    /** A profile has opened: read when there is no copy, or the snapshot says it is behind. */
    fun lookedAt() {
        val copy = held.value
        if (copy == null || (word != null && word != copy.version)) read()
    }

    /** A profile is open: [lookedAt] as it opens, and [follow] reads for it while it stays. Returns only when cancelled. */
    suspend fun profileOpen() {
        open++
        try {
            lookedAt()
            awaitCancellation()
        } finally {
            open--
        }
    }

    /**
     * The snapshot's word for the banners, each time a snapshot is shown. Kept for the next
     * profile to open; with one open, read a moment later if the copy is not that version.
     */
    fun follow(version: String?) {
        if (version.isNullOrEmpty()) return
        word = version
        if (open == 0 || held.value?.version == version || following?.isActive == true) return
        following = scope.launch {
            delay((jitter().coerceIn(0.0, 1.0) * spreadMs).toLong())
            // A read already out may bring the word: wait for it, and read unless it did.
            reading?.join()
            if (open > 0 && held.value?.version != word) read()
        }
    }

    /**
     * The reader's own change (or an admin's), as its answer said it: shown at once, and the
     * list read again behind it, which brings the word the snapshot will say.
     */
    fun took(userId: String, banner: VnBanner?) {
        val copy = held.value ?: VnBanners()
        held.value = copy.copy(people = if (banner == null) copy.people - userId else copy.people + (userId to banner))
        behind()
    }

    /**
     * The copy in hand may be behind the server (a change made against it was refused because
     * the banner had changed): read again, with its tag, so it costs nothing when it isn't.
     */
    fun behind() {
        if (reading?.isActive == true) again = true else read()
    }

    private fun read() {
        if (reading?.isActive == true || !allowed()) return
        reading = scope.launch {
            do {
                again = false
                runCatchingCancellable { fetch(if (held.value != null) tag else null) }.onSuccess { fresh ->
                    if (fresh != null) {
                        tag = fresh.tag
                        held.value = fresh.value
                    }
                }
            } while (again && allowed())
        }
    }

    /** Nothing of the server just left, or of one found too old for banners, stays to be shown. Open profiles stay counted. */
    fun clear() {
        reading?.cancel()
        following?.cancel()
        reading = null; following = null
        tag = null
        word = null
        again = false
        held.value = null
    }
}

// ---- the button on a profile's banner ------------------------------------------------------

/** What the small button on a profile's banner offers. */
internal enum class BannerEntry { Choose, Adjust, RemoveMine, RemoveTheirs }

/**
 * The button's menu, or nothing (and no button): your own profile offers a new picture (unless
 * you are a guest, whom VillaNET refuses) and taking yours away when you have one; a system admin
 * on somebody else's profile who has a banner can take it away; anybody else gets nothing.
 * [adjustable] is a banner of yours whose shown part VillaNET keeps (a GIF, from 3.8.0): it can
 * be adjusted without sending it again. A still's other parts never left the phone.
 */
internal fun bannerEntries(mine: Boolean, hasBanner: Boolean, admin: Boolean, guest: Boolean, adjustable: Boolean = false): List<BannerEntry> = when {
    mine -> listOfNotNull(
        BannerEntry.Choose.takeIf { !guest },
        BannerEntry.Adjust.takeIf { adjustable && hasBanner && !guest },
        BannerEntry.RemoveMine.takeIf { hasBanner },
    )
    admin && hasBanner -> listOf(BannerEntry.RemoveTheirs)
    else -> emptyList()
}

// ---- the picture sent ----------------------------------------------------------------------

/** The most a picture is sent as: three wide by one high, sharp at the widest phone. */
internal const val BANNER_MAX_WIDTH = 1500
internal const val BANNER_MAX_HEIGHT = 500

/** VillaNET refuses a side under this: a picture smaller than that is scaled up to it. */
internal const val BANNER_MIN_SIDE = 64

/** VillaNET's limit for a GIF, which is sent exactly as it is. */
internal const val BANNER_GIF_MAX_BYTES = 8L shl 20

/**
 * Where a picture [width] by [height] is cut ([x], [y], [cutWidth] by [cutHeight]: the widest
 * three-by-one window about its centre) and the size it is sent at: at most
 * [BANNER_MAX_WIDTH] by [BANNER_MAX_HEIGHT], and never under [BANNER_MIN_SIDE] high.
 */
internal data class BannerCut(val x: Int, val y: Int, val cutWidth: Int, val cutHeight: Int, val outWidth: Int, val outHeight: Int)

internal fun bannerCut(width: Int, height: Int): BannerCut {
    require(width > 0 && height > 0) { "a picture has sides" }
    val wide = width.toLong() >= 3L * height
    val cw = if (wide) 3 * height else width
    val ch = if (wide) height else maxOf(1, width / 3)
    val (ow, oh) = when {
        cw > BANNER_MAX_WIDTH -> BANNER_MAX_WIDTH to BANNER_MAX_HEIGHT
        ch < BANNER_MIN_SIDE -> 3 * BANNER_MIN_SIDE to BANNER_MIN_SIDE
        else -> cw to ch
    }
    return BannerCut((width - cw) / 2, (height - ch) / 2, cw, ch, ow, oh)
}

/**
 * How far a picture [width] by [height] is scaled down as it is decoded (at most 1), so that the
 * window [bannerCut] takes from it still has the banner's full width: a camera's picture is never
 * held whole. Taken the larger either way round, since a photo is turned upright as it decodes
 * and its sides may be said before that.
 */
internal fun bannerDecodeScale(width: Int, height: Int): Double {
    fun scale(w: Int, h: Int) = minOf(1.0, BANNER_MAX_WIDTH.toDouble() / bannerCut(w, h).cutWidth)
    // A very long picture (a scrolling screenshot, a stitched panorama) keeps its whole width by
    // the rule above however long it is: it is held to a budget of pixels as well.
    val budget = kotlin.math.sqrt(BANNER_DECODE_MAX_PIXELS.toDouble() / (width.toDouble() * height))
    return minOf(maxOf(scale(width, height), scale(height, width)), budget)
}

/** The most pixels a picked picture is decoded to: 16 million, 64 MB while it is held. */
internal const val BANNER_DECODE_MAX_PIXELS = 16_000_000L

/** VillaNET's limit for a JPEG or a PNG. */
internal const val BANNER_MAX_BYTES = 2L shl 20

/**
 * What a picture with see-through parts is laid on when it can't stay see-through (a JPEG has
 * no transparency): a neutral dark grey, so it reads in both themes, never black by accident.
 */
internal const val BANNER_FLATTEN_COLOR = 0xFF2E2E2E.toInt()

/** Whether any of these ARGB pixels lets something show through. */
internal fun hasTransparency(pixels: IntArray): Boolean = pixels.any { it ushr 24 != 0xFF }

/** Whether a picture with see-through parts, [pngBytes] long as a PNG, is sent as that PNG. */
internal fun sendsAsPng(transparent: Boolean, pngBytes: Int): Boolean = transparent && pngBytes <= BANNER_MAX_BYTES

/** A picked picture made ready to send, or why it can't be. */
internal sealed interface PreparedBanner {
    class Ready(val bytes: ByteArray, val type: String) : PreparedBanner
    class Refused(val text: String) : PreparedBanner
}

internal const val BANNER_GIF_TOO_LARGE = "That GIF is larger than 8 MB, the most a banner can be."
internal const val BANNER_UNREADABLE = "That picture can't be read. Try a JPEG, a PNG or a GIF."

/** A banner's GIF as it is sent: losslessly without its metadata, still animated; null when it can't be followed. */
internal fun cleanBannerGif(bytes: ByteArray): ByteArray? {
    val src = ceo.villa.matty.data.media.ArraySource(bytes)
    return ceo.villa.matty.data.media.cleanLossless(ceo.villa.matty.data.media.MediaKind.Gif, src)?.toByteArray(src)
}

/**
 * The GIF at [uri] as it is sent: as it is but for its metadata (so it stays animated), when it is
 * no larger than VillaNET keeps. Null when it is not a GIF, or is one that can't be followed,
 * which is then drawn again as a still, like any other picture. Blocking.
 */
internal fun readBannerGif(cr: ContentResolver, uri: Uri): PreparedBanner? {
    if (cr.getType(uri) != "image/gif") return null
    val out = ByteArrayOutputStream()
    val read = cr.openInputStream(uri)?.use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            // Read to the limit and a byte, and no further.
            if (out.size() + n > BANNER_GIF_MAX_BYTES) return PreparedBanner.Refused(BANNER_GIF_TOO_LARGE)
            out.write(buf, 0, n)
        }
        true
    }
    if (read != true) return PreparedBanner.Refused(BANNER_UNREADABLE)
    // Without its comments and application blocks (XMP among them).
    return cleanBannerGif(out.toByteArray())?.let { PreparedBanner.Ready(it, "image/gif") }
}

/**
 * The picture at [uri] as it is sent: a GIF by [readBannerGif]; anything else decoded at a sampled size, turned upright,
 * cut to three by one (about its centre, or about the middle of [crop], the part chosen on the crop screen),
 * scaled to at most 1500 by 500 and written as a JPEG
 * (or a PNG when it has see-through parts and that fits), neither carrying location or camera data. Blocking: run it off the main thread.
 */
internal fun prepareBanner(cr: ContentResolver, uri: Uri, crop: CropRect? = null): PreparedBanner {
    readBannerGif(cr, uri)?.let { return it }
    val decoded = try {
        // ImageDecoder applies the photo's EXIF rotation itself.
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            // Decoded once more for the cut, at the size the part chosen needs.
            val s = if (crop == null) bannerDecodeScale(w, h) else bannerCropDecodeScale(w, h, crop.w)
            if (s < 1.0) decoder.setTargetSize(maxOf(1, (w * s).roundToInt()), maxOf(1, (h * s).roundToInt()))
        }
    } catch (e: java.io.IOException) {
        return PreparedBanner.Refused(BANNER_UNREADABLE)
    } catch (e: IllegalArgumentException) {
        return PreparedBanner.Refused(BANNER_UNREADABLE)
    }
    // Fractions of the picture upright, so they hold at whatever size it was decoded.
    val cut = if (crop == null) bannerCut(decoded.width, decoded.height) else bannerCutFor(crop, decoded.width, decoded.height)
    val window = Bitmap.createBitmap(decoded, cut.x, cut.y, cut.cutWidth, cut.cutHeight)
    val sized = if (window.width == cut.outWidth && window.height == cut.outHeight) window
    else Bitmap.createScaledBitmap(window, cut.outWidth, cut.outHeight, true)
    // A picture with see-through parts (a logo, say) stays see-through as a PNG when that fits
    // VillaNET's limit. A JPEG has no transparency and would turn those parts black, so when the
    // PNG is too large it is laid on a neutral grey first.
    val transparent = sized.hasAlpha() && IntArray(sized.width * sized.height).let {
        sized.getPixels(it, 0, sized.width, 0, 0, sized.width, sized.height)
        hasTransparency(it)
    }
    if (transparent) {
        val png = ByteArrayOutputStream().also { sized.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        if (sendsAsPng(true, png.size)) {
            listOf(decoded, window, sized).distinct().forEach { it.recycle() }
            return PreparedBanner.Ready(png, "image/png")
        }
    }
    val opaque = if (!transparent) sized else Bitmap.createBitmap(sized.width, sized.height, Bitmap.Config.ARGB_8888).also { flat ->
        Canvas(flat).apply { drawColor(BANNER_FLATTEN_COLOR); drawBitmap(sized, 0f, 0f, null) }
    }
    val bytes = ByteArrayOutputStream().also { opaque.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
    listOf(decoded, window, sized, opaque).distinct().forEach { it.recycle() }
    return PreparedBanner.Ready(bytes, "image/jpeg")
}

/** A still picture as it is shown for choosing its part: [bitmap] at a size for the screen, [width] by [height] its own pixels upright. */
internal class BannerPreview(val bitmap: Bitmap, val width: Int, val height: Int)

/**
 * The still picture at [uri] decoded for the crop screen, on a screen whose longer side is
 * [screenPixels] (see [bannerPreviewScale]); null when it can't be read. Blocking.
 */
internal fun decodeBannerPreview(cr: ContentResolver, uri: Uri, screenPixels: Int): BannerPreview? {
    var said = 0 to 0
    val bitmap = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri)) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            said = w to h
            val s = bannerPreviewScale(w, h, screenPixels)
            if (s < 1.0) decoder.setTargetSize(maxOf(1, (w * s).roundToInt()), maxOf(1, (h * s).roundToInt()))
        }
    } catch (e: java.io.IOException) {
        return null
    } catch (e: IllegalArgumentException) {
        return null
    }
    val (w, h) = said
    if (w <= 0 || h <= 0) return null
    // The sides as said may be before the photo was turned upright: the decoded picture says which way round they are.
    val turned = w != h && (bitmap.width >= bitmap.height) != (w >= h)
    return if (turned) BannerPreview(bitmap, h, w) else BannerPreview(bitmap, w, h)
}
