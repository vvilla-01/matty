package ceo.villa.matty.plugins.villanet

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

// The arithmetic of choosing the part of a picture that shows as a banner. Nothing here touches
// Android, so every rule is held by a JVM test.

/** The first VillaNET that keeps the part of a banner that shows: an addition to contract 3. */
internal const val BANNER_CROP_SINCE = "3.8.0"

/**
 * Whether the part of a GIF that shows can be kept by the VillaNET that answered `GET /access`
 * with [version]. A still picture needs nothing from the server: it is cut on the phone.
 */
internal fun bannerCropOffered(version: String?): Boolean = bannersOffered(version) && !olderThan(version!!, BANNER_CROP_SINCE)

/** A banner's shape wherever it is chosen and drawn: three wide by one high. */
internal const val BANNER_ASPECT = 3.0

/** A part of a picture as fractions of its own width and height, (x, y) its top left corner. */
internal data class CropRect(val x: Double, val y: Double, val w: Double, val h: Double)

/** The part [bannerCut] takes of a picture [width] by [height]: its middle, three by one. */
internal fun middleCrop(width: Int, height: Int): CropRect {
    val c = bannerCut(width, height)
    return CropRect(c.x.toDouble() / width, c.y.toDouble() / height, c.cutWidth.toDouble() / width, c.cutHeight.toDouble() / height)
}

// ---- the crop on the wire -------------------------------------------------------------------

/** A crop as VillaNET keeps it: whole ten-thousandths of the picture's width and height. */
internal data class WireCrop(val x: Int, val y: Int, val w: Int, val h: Int) {
    /** As the query and the body write it: plain decimals, at most four places, no trailing zeros. */
    val text: String get() = listOf(x, y, w, h).joinToString(",") { wireDecimal(it) }
    val rect: CropRect get() = CropRect(x / 1e4, y / 1e4, w / 1e4, h / 1e4)
}

/** [tenThousandths] (0 to 10000) as the contract writes a number: 0, 1, 0.5, 0.3333. */
internal fun wireDecimal(tenThousandths: Int): String {
    require(tenThousandths in 0..10_000) { "a fraction of the picture" }
    if (tenThousandths == 10_000) return "1"
    if (tenThousandths == 0) return "0"
    return "0." + tenThousandths.toString().padStart(4, '0').trimEnd('0')
}

/**
 * [r] in four places, inside the picture: a rectangle that rounding would carry past the edge
 * is moved back, so it keeps its size.
 */
internal fun wireCrop(r: CropRect): WireCrop {
    fun part(d: Double) = (d * 10_000).roundToLong().coerceIn(0, 10_000).toInt()
    val w = part(r.w).coerceAtLeast(1)
    val h = part(r.h).coerceAtLeast(1)
    return WireCrop(part(r.x).coerceAtMost(10_000 - w), part(r.y).coerceAtMost(10_000 - h), w, h)
}

/**
 * The body of `PUT /me/banner/crop`: [crop], or null to clear it, written as the contract writes
 * it (never through a floating-point print), and [v], the picture the part was chosen for (VillaNET refuses it with `conflict` when the
 * banner has changed since). The `v` came from the server's list, so the JSON library writes it.
 */
internal fun bannerCropBody(crop: WireCrop?, v: String?): String {
    val part = crop?.let { "[${it.text}]" } ?: "null"
    return "{\"crop\":$part" + (v?.let { ",\"v\":${JsonPrimitive(it)}" } ?: "") + "}"
}

/**
 * Whether a Use refused with [code] closes the crop screen: adjusting, a banner that changed
 * since Adjust opened (`conflict`) or is gone (`not_found`) can't take the part however often it
 * is tried. Anything else (too often, the network, the store) leaves it open to try again.
 */
internal fun bannerUseRefusalCloses(code: String, adjusting: Boolean): Boolean =
    adjusting && (code == "conflict" || code == "not_found")

/** The smallest side of a crop VillaNET keeps, in ten-thousandths. */
internal const val BANNER_CROP_MIN_PART = 500

/**
 * Whether VillaNET keeps [c] for a picture [width] by [height], by the rules it checks: each
 * number 0 to 1, each side at least 0.05, no further than the edge (with one ten-thousandth for
 * rounding), and in the picture's own pixels between 2.5 and 3.5 times as wide as it is high.
 */
internal fun cropAccepted(c: WireCrop, width: Int, height: Int): Boolean {
    if (listOf(c.x, c.y, c.w, c.h).any { it !in 0..10_000 }) return false
    if (c.w < BANNER_CROP_MIN_PART || c.h < BANNER_CROP_MIN_PART) return false
    if (c.x + c.w > 10_001 || c.y + c.h > 10_001) return false
    val wide = 2L * c.w * width
    val high = c.h.toLong() * height
    return wide >= 5 * high && wide <= 7 * high
}

/**
 * Whether any part of a GIF [width] by [height] can be kept: its largest three-by-one part must
 * be at least 0.05 of it each way (a GIF more than about six and a half times as high as it is
 * wide has none, and goes as it is, the middle showing).
 */
internal fun gifCropPossible(width: Int, height: Int): Boolean =
    width > 0 && height > 0 && cropAccepted(wireCrop(middleCrop(width, height)), width, height)

/**
 * A crop from `GET /banners` (VillaNET 3.8.0): four numbers from 0 to 1, a rectangle with sides
 * inside the picture. Anything else (no crop, not four numbers, words, outside the picture) is
 * none, and the middle shows. A rectangle that rounding takes one ten-thousandth past the edge
 * stops at the edge.
 */
internal fun bannerCropOf(e: JsonElement?): CropRect? {
    val a = e as? JsonArray ?: return null
    if (a.size != 4) return null
    val n = a.map { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.doubleOrNull ?: return null }
    if (n.any { !it.isFinite() || it < 0.0 || it > 1.0 }) return null
    val (x, y, w, h) = n
    val edge = 1.0001 + 1e-9
    if (w <= 0.0 || h <= 0.0 || x + w > edge || y + h > edge) return null
    return CropRect(x, y, minOf(w, 1.0 - x), minOf(h, 1.0 - y)).takeIf { it.w > 0.0 && it.h > 0.0 }
}

// ---- the banner as drawn ----------------------------------------------------------------------

/**
 * Where a picture drawn [contentWidth] by [contentHeight] sits in a box [boxWidth] by
 * [boxHeight] so that the middle of [crop] (or of the picture, with none) is the middle of the
 * box: the offset of its top left corner, never so far that the box shows past its edge.
 */
internal fun cropOffset(contentWidth: Double, contentHeight: Double, boxWidth: Double, boxHeight: Double, crop: CropRect?): Pair<Double, Double> {
    val r = crop ?: CropRect(0.0, 0.0, 1.0, 1.0)
    val left = boxWidth / 2 - (r.x + r.w / 2) * contentWidth
    val top = boxHeight / 2 - (r.y + r.h / 2) * contentHeight
    return left.coerceIn(minOf(0.0, boxWidth - contentWidth), 0.0) to top.coerceIn(minOf(0.0, boxHeight - contentHeight), 0.0)
}

/**
 * How much a picture [width] by [height] is scaled so that [crop] covers a box [boxWidth] by
 * [boxHeight] (with none, the whole picture covers it, as a centre crop does). A crop a little
 * wider or higher than the box loses a sliver of its sides, never shows past them.
 */
internal fun cropScale(width: Double, height: Double, boxWidth: Double, boxHeight: Double, crop: CropRect?): Double {
    val r = crop ?: CropRect(0.0, 0.0, 1.0, 1.0)
    return maxOf(boxWidth / (r.w * width), boxHeight / (r.h * height))
}

// ---- the crop screen ----------------------------------------------------------------------------

/** The window on the crop screen, in screen pixels. */
internal data class CropFrame(val left: Double, val top: Double, val width: Double, val height: Double) {
    val right get() = left + width
    val bottom get() = top + height
    val centerX get() = left + width / 2
    val centerY get() = top + height / 2
}

/** The largest three-by-one window inside the area given, in its middle. */
internal fun cropFrameIn(left: Double, top: Double, width: Double, height: Double): CropFrame {
    val w = minOf(width, height * BANNER_ASPECT).coerceAtLeast(1.0)
    val h = w / BANNER_ASPECT
    return CropFrame(left + (width - w) / 2, top + (height - h) / 2, w, h)
}

/** The picture on the crop screen: screen pixels for one of its own, and where its top left corner is. */
internal data class CropView(val scale: Double, val left: Double, val top: Double)

/** How far every picture can be sized up from just filling the window, however few pixels it has. */
internal const val CROP_EVERY_PICTURE_ZOOM = 3.0

/** How far one zoom step of the keyboard (plus and minus) goes. */
internal const val CROP_KEY_ZOOM = 1.25

/** What a key does on the crop screen. */
internal enum class CropKey { Left, Right, Up, Down, In, Out }

/**
 * The limits of moving and sizing a picture [width] by [height] (its own pixels) behind [frame]:
 * never smaller than just filling the window; every picture up to [CROP_EVERY_PICTURE_ZOOM] times
 * that, however few pixels it has (it looks soft, as the screen shows, and that is the person's
 * choice), and one with pixels to spare further, until the window shows [leastPixels] of its own
 * pixels across, but never past eight times; never moved so that the window shows past its edge.
 * [minPart] is the smallest share of the picture the window may cover each way (VillaNET keeps no
 * crop under 0.05 of a GIF; a still is cut on the phone and has none).
 *
 * Every limit is a share of the picture, not of the window, so a part chosen in one window can be
 * shown exactly in any other: [leastPixels] is one figure for the phone however it is held (the
 * crop screen gives the screen's shorter side, at most a banner's width), where one of the
 * picture's pixels to one of the screen's would change as the window does.
 */
internal class CropLimits(val width: Int, val height: Int, val frame: CropFrame, minPart: Double = 0.0, leastPixels: Double = frame.width) {
    init { require(width > 0 && height > 0) { "a picture has sides" } }

    val minScale: Double = maxOf(frame.width / width, frame.height / height)
    val maxScale: Double = run {
        var most = minOf(minScale * 8, maxOf(frame.width / leastPixels.coerceAtLeast(1.0), minScale * CROP_EVERY_PICTURE_ZOOM))
        if (minPart > 0.0) most = minOf(most, frame.width / (minPart * width), frame.height / (minPart * height))
        maxOf(minScale, most)
    }

    /** Whether it can be sized up at all: a GIF whose largest three-by-one part is already the smallest VillaNET keeps can only be moved. */
    val sizable: Boolean get() = maxScale > minScale * 1.01

    fun clamp(v: CropView): CropView {
        val s = v.scale.coerceIn(minScale, maxScale)
        // At just filling the window the two ends meet, and rounding may cross them by a hair.
        return CropView(
            s,
            v.left.coerceIn(minOf(frame.right - width * s, frame.left), frame.left),
            v.top.coerceIn(minOf(frame.bottom - height * s, frame.top), frame.top),
        )
    }

    /** The view in which the middle of [r] is the middle of the window and [r] covers it. */
    fun viewOf(r: CropRect): CropView {
        val s = cropScale(width.toDouble(), height.toDouble(), frame.width, frame.height, r).coerceIn(minScale, maxScale)
        return clamp(CropView(s, frame.centerX - (r.x + r.w / 2) * width * s, frame.centerY - (r.y + r.h / 2) * height * s))
    }

    /** The part of the picture the window shows in [v]. */
    fun rectOf(v: CropView): CropRect = CropRect(
        (frame.left - v.left) / (v.scale * width),
        (frame.top - v.top) / (v.scale * height),
        frame.width / (v.scale * width),
        frame.height / (v.scale * height),
    )

    /** Dragged by ([dx], [dy]) screen pixels: it stops at the edges. */
    fun moved(v: CropView, dx: Double, dy: Double): CropView = clamp(v.copy(left = v.left + dx, top = v.top + dy))

    /** Sized by [factor] about the screen point ([fx], [fy]), which stays over the same part of the picture. */
    fun zoomed(v: CropView, factor: Double, fx: Double, fy: Double): CropView {
        val s = (v.scale * factor).coerceIn(minScale, maxScale)
        val k = s / v.scale
        return clamp(CropView(s, fx - (fx - v.left) * k, fy - (fy - v.top) * k))
    }

    /**
     * One step of a pinch: [zoom] about where the fingers were (the centroid now, [fx] and [fy],
     * less this step's [panX] and [panY]), then moved with them, so the part under the fingers
     * stays under them.
     */
    fun pinched(v: CropView, fx: Double, fy: Double, zoom: Double, panX: Double, panY: Double): CropView {
        val s = (v.scale * zoom).coerceIn(minScale, maxScale)
        val k = s / v.scale
        val px = fx - panX
        val py = fy - panY
        return clamp(CropView(s, px - (px - v.left) * k + panX, py - (py - v.top) * k + panY))
    }

    /** A double tap at ([x], [y]): just filling the window goes to twice that; anything larger goes back. */
    fun doubleTapped(v: CropView, x: Double, y: Double): CropView {
        val target = if (v.scale > minScale * 1.01) minScale else minOf(minScale * 2, maxScale)
        return zoomed(v, target / v.scale, x, y)
    }

    /** A key: the arrows move the picture a twelfth of the window's width, plus and minus size it about the window's middle. */
    fun keyed(v: CropView, key: CropKey): CropView {
        val step = frame.width / 12
        return when (key) {
            CropKey.Left -> moved(v, -step, 0.0)
            CropKey.Right -> moved(v, step, 0.0)
            CropKey.Up -> moved(v, 0.0, -step)
            CropKey.Down -> moved(v, 0.0, step)
            CropKey.In -> zoomed(v, CROP_KEY_ZOOM, frame.centerX, frame.centerY)
            CropKey.Out -> zoomed(v, 1 / CROP_KEY_ZOOM, frame.centerX, frame.centerY)
        }
    }
}

/** The smallest share of a GIF the window may show each way, a little over VillaNET's 0.05 so rounding never falls under it. */
internal const val GIF_CROP_MIN_PART = 0.0502

// ---- the still picture cut on the phone ---------------------------------------------------------

/**
 * Where a decoded picture [width] by [height] is cut for [crop] (three by one, about the middle
 * of the part chosen) and the size it is sent at, by [bannerCut]'s rules. [middleCrop] cuts
 * exactly what [bannerCut] does.
 */
internal fun bannerCutFor(crop: CropRect, width: Int, height: Int): BannerCut {
    require(width > 0 && height > 0) { "a picture has sides" }
    val cw = (crop.w * width).roundToInt().coerceIn(1, minOf(width, 3 * height))
    val ch = (cw / 3).coerceIn(1, height)
    // By the middle of the part, rounded down as bannerCut's halves are.
    val x = floor((crop.x + crop.w / 2) * width - cw / 2.0 + 1e-6).toInt().coerceIn(0, width - cw)
    val y = floor((crop.y + crop.h / 2) * height - ch / 2.0 + 1e-6).toInt().coerceIn(0, height - ch)
    val (ow, oh) = when {
        cw > BANNER_MAX_WIDTH -> BANNER_MAX_WIDTH to BANNER_MAX_HEIGHT
        ch < BANNER_MIN_SIDE -> 3 * BANNER_MIN_SIDE to BANNER_MIN_SIDE
        else -> cw to ch
    }
    return BannerCut(x, y, cw, ch, ow, oh)
}

/**
 * How far a picture [width] by [height] is scaled down as it is decoded for cutting [part] of its
 * width (at most 1): the part keeps the banner's full width where it has it, within the pixel
 * budget [bannerDecodeScale] keeps. Taken the larger either way round, as there.
 */
internal fun bannerCropDecodeScale(width: Int, height: Int, part: Double): Double {
    fun scale(w: Int) = minOf(1.0, BANNER_MAX_WIDTH / (part.coerceIn(1e-4, 1.0) * w))
    val budget = sqrt(BANNER_DECODE_MAX_PIXELS.toDouble() / (width.toDouble() * height))
    return minOf(maxOf(scale(width), scale(height)), budget)
}

/** The most pixels the picture shown for choosing is decoded to: 8 million, and no side over 8192 (what a phone's graphics take). */
internal const val BANNER_PREVIEW_MAX_PIXELS = 8_000_000L
internal const val BANNER_PREVIEW_MAX_SIDE = 8192

/**
 * How far a picture [width] by [height] is scaled down to be shown for choosing on a screen whose
 * longer side is [screenPixels]: its middle part has twice the screen's pixels across, for
 * sizing up, within [BANNER_PREVIEW_MAX_PIXELS] and [BANNER_PREVIEW_MAX_SIDE].
 */
internal fun bannerPreviewScale(width: Int, height: Int, screenPixels: Int): Double {
    fun scale(w: Int, h: Int) = minOf(1.0, 2.0 * screenPixels / bannerCut(w, h).cutWidth)
    val budget = sqrt(BANNER_PREVIEW_MAX_PIXELS.toDouble() / (width.toDouble() * height))
    val side = BANNER_PREVIEW_MAX_SIDE.toDouble() / maxOf(width, height)
    return minOf(maxOf(scale(width, height), scale(height, width)), budget, side)
}

/** A GIF's size as its header says it (the logical screen, every frame inside it): what VillaNET measures. */
internal fun gifScreenSize(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 10) return null
    val sig = String(bytes, 0, 6, Charsets.ISO_8859_1)
    if (sig != "GIF87a" && sig != "GIF89a") return null
    val w = (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8)
    val h = (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
    return if (w > 0 && h > 0) w to h else null
}
