package ceo.villa.matty

import ceo.villa.matty.plugins.villanet.BANNER_DECODE_MAX_PIXELS
import ceo.villa.matty.plugins.villanet.BANNER_MAX_WIDTH
import ceo.villa.matty.plugins.villanet.BANNER_MIN_SIDE
import ceo.villa.matty.plugins.villanet.BANNER_PREVIEW_MAX_PIXELS
import ceo.villa.matty.plugins.villanet.BANNER_PREVIEW_MAX_SIDE
import ceo.villa.matty.plugins.villanet.BannerCropSession
import ceo.villa.matty.plugins.villanet.BannerCut
import ceo.villa.matty.plugins.villanet.BannerEntry
import ceo.villa.matty.plugins.villanet.CropFrame
import ceo.villa.matty.plugins.villanet.CropKey
import ceo.villa.matty.plugins.villanet.CropLimits
import ceo.villa.matty.plugins.villanet.CropRect
import ceo.villa.matty.plugins.villanet.CropView
import ceo.villa.matty.plugins.villanet.GIF_CROP_MIN_PART
import ceo.villa.matty.plugins.villanet.WireCrop
import ceo.villa.matty.plugins.villanet.bannerCropBody
import ceo.villa.matty.plugins.villanet.bannerCropDecodeScale
import ceo.villa.matty.plugins.villanet.bannerCropOf
import ceo.villa.matty.plugins.villanet.bannerCropOffered
import ceo.villa.matty.plugins.villanet.bannerCut
import ceo.villa.matty.plugins.villanet.bannerCutFor
import ceo.villa.matty.plugins.villanet.bannerEntries
import ceo.villa.matty.plugins.villanet.bannerPreviewScale
import ceo.villa.matty.plugins.villanet.bannerUseRefusalCloses
import ceo.villa.matty.plugins.villanet.cropAccepted
import ceo.villa.matty.plugins.villanet.cropFrameIn
import ceo.villa.matty.plugins.villanet.cropOffset
import ceo.villa.matty.plugins.villanet.cropScale
import ceo.villa.matty.plugins.villanet.gifCropPossible
import ceo.villa.matty.plugins.villanet.gifScreenSize
import ceo.villa.matty.plugins.villanet.middleCrop
import ceo.villa.matty.plugins.villanet.wireCrop
import ceo.villa.matty.plugins.villanet.wireDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Choosing the part of a picture that shows as a banner: the window and its limits on the crop
 * screen, what each gesture and key does, the rectangle sent (VillaNET 3.8.0) and the pixels cut
 * from a still, and how a crop read from the list is drawn.
 */
class VillaNetBannerCropTest {
    private val eps = 1e-6

    private fun near(want: Double, got: Double, why: String = "", tol: Double = eps) =
        assertTrue("$why: wanted $want, got $got", abs(want - got) <= tol)

    private fun near(want: CropRect, got: CropRect, tol: Double = 1e-6) {
        near(want.x, got.x, "x", tol); near(want.y, got.y, "y", tol); near(want.w, got.w, "w", tol); near(want.h, got.h, "h", tol)
    }

    /** A phone held upright: 1080 pixels wide, a 1048 by 349.33 window 16 pixels from its sides. */
    private val phone = cropFrameIn(16.0, 200.0, 1048.0, 1800.0)

    // ---- the gate -------------------------------------------------------------------------------

    @Test
    fun theCropIsOfferedFromVillaNet380() {
        // Fails if the gate is the banners' own (3.7.0) or the contract's revision.
        for (old in listOf(null, "", "3.6.1", "3.7.0", "3.7.3", "nonsense")) assertFalse(old.toString(), bannerCropOffered(old))
        for (new in listOf("3.8.0", "3.8.1", "3.10.0", "4.0.0", "v3.8.0", "3.8.0+standin")) assertTrue(new, bannerCropOffered(new))
    }

    @Test
    fun adjustIsOfferedOnlyForAnAdjustableBannerOfYourOwn() {
        assertEquals(listOf(BannerEntry.Choose, BannerEntry.Adjust, BannerEntry.RemoveMine), bannerEntries(mine = true, hasBanner = true, admin = false, guest = false, adjustable = true))
        // A still (or an older VillaNET): no Adjust, the menu as it was.
        assertEquals(listOf(BannerEntry.Choose, BannerEntry.RemoveMine), bannerEntries(mine = true, hasBanner = true, admin = false, guest = false, adjustable = false))
        assertEquals(listOf(BannerEntry.Choose), bannerEntries(mine = true, hasBanner = false, admin = false, guest = false, adjustable = true))
        // VillaNET refuses a guest's crop as it refuses a guest's picture.
        assertEquals(listOf(BannerEntry.RemoveMine), bannerEntries(mine = true, hasBanner = true, admin = false, guest = true, adjustable = true))
        // Never on somebody else's, an admin's included.
        assertEquals(listOf(BannerEntry.RemoveTheirs), bannerEntries(mine = false, hasBanner = true, admin = true, guest = false, adjustable = true))
    }

    // ---- the crop on the wire -----------------------------------------------------------------

    @Test
    fun numbersAreWrittenAsTheContractWritesThem() {
        assertEquals("0", wireDecimal(0))
        assertEquals("1", wireDecimal(10_000))
        assertEquals("0.5", wireDecimal(5_000))
        assertEquals("0.3333", wireDecimal(3_333))
        assertEquals("0.01", wireDecimal(100))
        // Never 1.0E-4, as a floating-point print would write it.
        assertEquals("0.0001", wireDecimal(1))
        assertEquals("0,0.3333,1,0.3333", WireCrop(0, 3_333, 10_000, 3_333).text)
    }

    @Test
    fun theRectangleSentIsFourPlacesInsideThePicture() {
        assertEquals(WireCrop(1_235, 0, 5_000, 10_000), wireCrop(CropRect(0.12346, 0.0, 0.5, 1.0)))
        // Rounding that would carry it past the edge moves it back, keeping its size: fails if
        // the side is shortened instead, or nothing is done.
        assertEquals(WireCrop(6_666, 0, 3_334, 10_000), wireCrop(CropRect(0.66668, 0.0, 0.33336, 1.0)))
        assertEquals(WireCrop(0, 0, 10_000, 10_000), wireCrop(CropRect(-1e-9, -0.00001, 1.0000001, 1.0)))
        for (c in listOf(wireCrop(CropRect(0.66668, 0.0, 0.33336, 1.0)), wireCrop(CropRect(0.99999, 0.99999, 0.5, 0.5)))) {
            assertTrue(c.toString(), c.x + c.w <= 10_000 && c.y + c.h <= 10_000)
        }
    }

    @Test
    fun theRulesVillaNetChecksAreTheAppsToo() {
        // A picture 3000 by 1000: its whole is three to one.
        assertTrue(cropAccepted(WireCrop(0, 0, 10_000, 10_000), 3000, 1000))
        // Exactly 2.5 and 3.5 to one are kept, a hair beyond is not.
        assertTrue(cropAccepted(WireCrop(0, 0, 10_000, 4_000), 1000, 1000)) // 2.5
        assertFalse(cropAccepted(WireCrop(0, 0, 10_000, 4_001), 1000, 1000))
        assertTrue(cropAccepted(WireCrop(0, 0, 7_000, 2_000), 1000, 1000)) // 3.5
        assertFalse(cropAccepted(WireCrop(0, 0, 7_001, 2_000), 1000, 1000))
        // Measured in the picture's own pixels, not in fractions.
        assertTrue(cropAccepted(WireCrop(0, 0, 10_000, 10_000), 3000, 1000))
        assertFalse(cropAccepted(WireCrop(0, 0, 10_000, 10_000), 1000, 1000))
        // Sides from 0.05, and one ten-thousandth past the edge for rounding.
        assertTrue(cropAccepted(WireCrop(0, 0, 1_500, 500), 1000, 1000))
        assertFalse(cropAccepted(WireCrop(0, 0, 1_497, 499), 1000, 1000))
        assertTrue(cropAccepted(WireCrop(7_001, 0, 3_000, 1_000), 1000, 1000))
        assertFalse(cropAccepted(WireCrop(7_002, 0, 3_000, 1_000), 1000, 1000))
    }

    @Test
    fun aGifWithNoPartVillaNetWouldKeepGoesAsItIs() {
        assertTrue(gifCropPossible(300, 100))
        assertTrue(gifCropPossible(1000, 1000))
        // Its whole width, a third of it high, must be 0.05 of its height.
        assertTrue(gifCropPossible(100, 600))
        assertFalse(gifCropPossible(100, 700))
        assertFalse(gifCropPossible(64, 2400))
    }

    @Test
    fun aGifsSizeIsWhatItsHeaderSays() {
        val header = byteArrayOf('G'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), '8'.code.toByte(), '9'.code.toByte(), 'a'.code.toByte(), 0x2C, 0x01, 0x64, 0x00, 0, 0)
        assertEquals(300 to 100, gifScreenSize(header))
        assertNull(gifScreenSize(header.copyOf(9)))
        assertNull(gifScreenSize(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0)))
    }

    // ---- reading a crop from the list ---------------------------------------------------------

    private fun crop(json: String) = bannerCropOf(Json.parseToJsonElement(json))

    @Test
    fun aCropTheAppCannotUseIsNone() {
        assertEquals(CropRect(0.0, 0.3333, 1.0, 0.3333), crop("[0, 0.3333, 1, 0.3333]"))
        assertNull(bannerCropOf(null))
        assertNull(bannerCropOf(JsonNull))
        for (bad in listOf("[0, 0.3333, 1]", "[0, 0, 1, 0.3, 0.1]", "[\"0\", 0, 1, 0.3]", "[0, -0.1, 1, 0.3]", "[0, 0, 1.5, 0.3]",
            "[0, 0, 0, 0.3]", "[0.5, 0, 0.6, 0.3]", "[0, 0.8, 1, 0.3]", "{\"x\": 0}", "\"0,0,1,1\"", "[null, 0, 1, 1]", "[[0], 0, 1, 1]")) {
            assertNull(bad, crop(bad))
        }
        // One ten-thousandth past the edge is rounding: it stops at the edge.
        val r = crop("[0.6667, 0, 0.3334, 0.3333]")!!
        near(1.0, r.x + r.w, "to the edge and no further")
    }

    // ---- the banner as drawn -----------------------------------------------------------------

    @Test
    fun aCropFillsTheBannerAndNoMore() {
        // Alice's: a square picture, its middle third across its whole width, in a 1080 by 360 box.
        val r = CropRect(0.0, 0.3333, 1.0, 0.3333)
        val s = cropScale(1000.0, 1000.0, 1080.0, 360.0, r)
        near(1.08, s, "the crop's width is the box's", 1e-3)
        val (left, top) = cropOffset(1000 * s, 1000 * s, 1080.0, 360.0, r)
        near(0.0, left, "left", 0.5)
        // The box's top is the crop's top.
        near(-0.3333 * 1000 * s, top, "top", 0.5)
        // A crop a little wider than three to one loses a sliver of its sides; a little higher, of
        // its top and bottom; neither shows past the picture.
        for (c in listOf(CropRect(0.0, 0.7, 1.0, 0.3), CropRect(0.65, 0.0, 0.35, 0.1), CropRect(0.0, 0.0, 0.25, 0.1))) {
            val k = cropScale(1000.0, 1000.0, 1080.0, 360.0, c)
            val (l, t) = cropOffset(1000 * k, 1000 * k, 1080.0, 360.0, c)
            assertTrue("$c covers the box", l <= 0 && t <= 0 && l + 1000 * k >= 1080 - 1e-6 && t + 1000 * k >= 360 - 1e-6)
            // The box shows only what is inside the crop.
            assertTrue("$c: left", -l / k >= c.x * 1000 - 1e-6)
            assertTrue("$c: right", (-l + 1080) / k <= (c.x + c.w) * 1000 + 1e-6)
            assertTrue("$c: top", -t / k >= c.y * 1000 - 1e-6)
            assertTrue("$c: bottom", (-t + 360) / k <= (c.y + c.h) * 1000 + 1e-6)
        }
    }

    @Test
    fun noCropShowsTheMiddleAsBefore() {
        // As ContentScale.Crop with the centre aligned: a 4:3 photo in a 3:1 box.
        val s = cropScale(4000.0, 3000.0, 1080.0, 360.0, null)
        near(1080.0 / 4000, s, "fills the width")
        val (left, top) = cropOffset(4000 * s, 3000 * s, 1080.0, 360.0, null)
        near(0.0, left, "left")
        near((360 - 3000 * s) / 2, top, "centred")
    }

    // ---- the crop screen --------------------------------------------------------------------

    @Test
    fun theWindowIsThreeByOneInTheMiddleOfItsRoom() {
        near(1048.0, phone.width, "as wide as the room")
        near(1048.0 / 3, phone.height, "a third of that high")
        near(200 + (1800 - 1048.0 / 3) / 2, phone.top, "in the middle")
        // Turned on its side, the room is wide and low: the height decides.
        val side = cropFrameIn(16.0, 40.0, 2368.0, 600.0)
        near(1800.0, side.width, "three times the room's height")
        near(16 + (2368 - 1800.0) / 2, side.left, "in the middle")
    }

    @Test
    fun theLimitsOfSizing() {
        // A 12000 by 9000 photo: just filling the window is its width across the window.
        val photo = CropLimits(12_000, 9_000, phone)
        near(1048.0 / 12_000, photo.minScale, "the smallest just fills the window")
        near(8 * 1048.0 / 12_000, photo.maxScale, "eight times that, under one of its pixels a pixel")
        // A 6000 by 4000 picture has pixels to spare: past three times, it reaches its own pixels first.
        val spare = CropLimits(6000, 4000, phone)
        near(1.0, spare.maxScale, "one of its pixels to a pixel of the screen")
        // A 1500 by 1000 picture has fewer: three times, however soft. Fails without the three.
        val small = CropLimits(1500, 1000, phone)
        near(3 * small.minScale, small.maxScale, "three times just filling")
        // Smaller than the window, three times too. Fails if the largest is taken as eight times regardless.
        val tiny = CropLimits(600, 200, phone)
        near(3 * tiny.minScale, tiny.maxScale, "three times just filling")
        // The fewest pixels across given for the phone (its shorter side) rather than the window's own.
        near(1048.0 / 1080, CropLimits(6000, 4000, phone, leastPixels = 1080.0).maxScale, "1080 of its pixels across")
        near(3 * small.minScale, CropLimits(1500, 1000, phone, leastPixels = 1080.0).maxScale, "three times, past its pixels")
        // A GIF in a small window: VillaNET's 0.05 stops it before eight times or its pixels.
        // Fails without the minPart limit.
        val small300 = cropFrameIn(0.0, 0.0, 300.0, 300.0)
        val big = CropLimits(2400, 2400, small300, GIF_CROP_MIN_PART)
        assertTrue("${big.maxScale}", big.maxScale < minOf(8 * big.minScale, 1.0) - 0.05)
        val most = big.rectOf(big.clamp(CropView(99.0, small300.left, small300.top)))
        near(GIF_CROP_MIN_PART, most.h, "as small as VillaNET keeps")
        assertTrue(cropAccepted(wireCrop(most), 2400, 2400))
    }

    @Test
    fun itOpensWithTheMiddleFillingTheWindow() {
        for ((w, h) in listOf(4000 to 3000, 3000 to 1000, 6000 to 1000, 1080 to 20_000, 997 to 1013)) {
            val l = CropLimits(w, h, phone)
            val v = l.viewOf(middleCrop(w, h))
            near(l.minScale, v.scale, "$w x $h: just filling", 2e-3 * l.minScale)
            // What it sends untouched is what was sent before there was a choice.
            assertEquals("$w x $h", bannerCut(w, h), bannerCutFor(middleCrop(w, h), w, h))
            near(middleCrop(w, h), l.rectOf(v), 2e-3)
        }
    }

    @Test
    fun draggingStopsAtTheEdges() {
        val l = CropLimits(3000, 2000, phone)
        val start = l.viewOf(middleCrop(3000, 2000))
        // Sideways there is no room at its smallest: the window spans its width.
        assertEquals(start.left, l.moved(start, 300.0, 0.0).left, 1e-9)
        // Down as far as it goes: its top edge on the window's top, never past it.
        val down = l.moved(start, 0.0, 10_000.0)
        near(phone.top, down.top, "stops at the top edge")
        assertEquals(0.0, l.rectOf(down).y, 1e-9)
        val up = l.moved(start, 0.0, -10_000.0)
        near(1.0, l.rectOf(up).y + l.rectOf(up).h, "stops at the bottom edge")
        // A drag inside the room moves it by exactly that.
        val some = l.moved(start, 0.0, 40.0)
        near(start.top + 40, some.top, "moved by the drag")
    }

    @Test
    fun aPinchKeepsThePartUnderTheFingersUnderThem() {
        val l = CropLimits(4000, 3000, phone)
        val v = l.viewOf(middleCrop(4000, 3000))
        val fx = phone.left + 300
        val fy = phone.top + 100
        // The picture's point under the fingers.
        val u = (fx - v.left) / v.scale
        val w = (fy - v.top) / v.scale
        val after = l.pinched(v, fx, fy, 1.5, 0.0, 0.0)
        near(1.5 * v.scale, after.scale, "sized by the pinch")
        near(fx, after.left + u * after.scale, "x stays under the fingers", 1e-6)
        near(fy, after.top + w * after.scale, "y stays under the fingers", 1e-6)
        // Moved as it is sized: the point under where the fingers were goes with them.
        val pan = l.pinched(after, fx + 20, fy + 10, 1.1, 20.0, 10.0)
        val u2 = (fx - after.left) / after.scale
        near(fx + 20, pan.left + u2 * pan.scale, "with the fingers", 1e-6)
        // A finger landing or lifting is a step with no zoom and no pan: nothing moves, wherever
        // the centroid now is. (Compose's own sums leave a finger out of the step it lands or
        // lifts in.) Fails if the focus were taken as an absolute position.
        for (c in listOf(0.0 to 0.0, 900.0 to 1700.0)) {
            val same = l.pinched(after, c.first, c.second, 1.0, 0.0, 0.0)
            near(after.scale, same.scale, "scale"); near(after.left, same.left, "left"); near(after.top, same.top, "top")
        }
        // Never past the limits.
        assertEquals(l.maxScale, l.pinched(after, fx, fy, 100.0, 0.0, 0.0).scale, 1e-12)
        assertEquals(l.minScale, l.pinched(after, fx, fy, 0.001, 0.0, 0.0).scale, 1e-12)
    }

    @Test
    fun aDoubleTapStepsBetweenFittingAndTwiceThat() {
        val l = CropLimits(4000, 3000, phone)
        val fit = l.viewOf(middleCrop(4000, 3000))
        val x = phone.left + 200
        val y = phone.centerY
        val twice = l.doubleTapped(fit, x, y)
        near(2 * l.minScale, twice.scale, "twice fitting")
        // About the tap.
        near(x, twice.left + (x - fit.left) / fit.scale * twice.scale, "about the tap", 1e-6)
        near(l.minScale, l.doubleTapped(twice, x, y).scale, "and back")
        // Pinched larger than twice: back to fitting.
        val big = l.zoomed(fit, 5.0, x, y)
        near(l.minScale, l.doubleTapped(big, x, y).scale, "back from anywhere larger")
        // A small picture goes to twice too, being sizable to three times.
        val small = CropLimits(600, 200, phone)
        near(2 * small.minScale, small.doubleTapped(small.viewOf(middleCrop(600, 200)), x, y).scale, "twice fitting")
        // A picture that can't be sized up stays as it is: a GIF whose window already shows
        // VillaNET's smallest part.
        val tiny = CropLimits(300, 2000, phone, GIF_CROP_MIN_PART)
        val t = tiny.viewOf(middleCrop(300, 2000))
        val tapped = tiny.doubleTapped(t, x, y)
        near(t.scale, tapped.scale, "scale"); near(t.left, tapped.left, "left"); near(t.top, tapped.top, "top")
    }

    @Test
    fun keysMoveAndSize() {
        val l = CropLimits(4000, 3000, phone)
        val v = l.zoomed(l.viewOf(middleCrop(4000, 3000)), 2.0, phone.centerX, phone.centerY)
        val step = phone.width / 12
        near(v.left - step, l.keyed(v, CropKey.Left).left, "left")
        near(v.left + step, l.keyed(v, CropKey.Right).left, "right")
        near(v.top - step, l.keyed(v, CropKey.Up).top, "up")
        near(v.top + step, l.keyed(v, CropKey.Down).top, "down")
        near(v.scale * 1.25, l.keyed(v, CropKey.In).scale, "plus")
        near(v.scale / 1.25, l.keyed(v, CropKey.Out).scale, "minus")
        // About the window's middle: the part in the middle stays in the middle.
        val c0 = l.rectOf(v).let { it.x + it.w / 2 }
        val c1 = l.rectOf(l.keyed(v, CropKey.In)).let { it.x + it.w / 2 }
        near(c0, c1, "the middle stays")
    }

    @Test
    fun turningOrResizingKeepsThePartChosen() {
        // Chosen upright, sized up as far as it goes and moved off the middle, on a phone whose
        // shorter side is 1080 pixels.
        val upright = CropLimits(4000, 3000, phone, leastPixels = 1080.0)
        var v = upright.zoomed(upright.viewOf(middleCrop(4000, 3000)), 100.0, phone.centerX, phone.centerY)
        v = upright.moved(v, 220.0, -180.0)
        val chosen = upright.rectOf(v)
        near(1080.0 / 4000, chosen.w, "as far as it goes")
        // On its side the window is another size in another place; the part is the same, the
        // limits being shares of the picture. Fails with the limit at one pixel to a pixel.
        val side = CropLimits(4000, 3000, cropFrameIn(16.0, 40.0, 2368.0, 600.0), leastPixels = 1080.0)
        near(chosen, side.rectOf(side.viewOf(chosen)))
        // And back again.
        near(chosen, upright.rectOf(upright.viewOf(side.rectOf(side.viewOf(chosen)))))
        // A window of a split screen, too.
        val split = CropLimits(4000, 3000, cropFrameIn(0.0, 0.0, 300.0, 300.0), leastPixels = 1080.0)
        near(chosen, split.rectOf(split.viewOf(chosen)))
        val kept = split.rectOf(split.viewOf(chosen))
        near(chosen.x + chosen.w / 2, kept.x + kept.w / 2, "the middle", 1e-6)

        // A phone screenshot, with no pixels to spare, sized up three times and moved: the same
        // part in every window. Fails if the three were a share of the window.
        val shot = CropLimits(1080, 2400, phone, leastPixels = 1080.0)
        var s = shot.zoomed(shot.viewOf(middleCrop(1080, 2400)), 100.0, phone.centerX, phone.centerY)
        s = shot.moved(s, 150.0, -900.0)
        val part = shot.rectOf(s)
        near(1.0 / 3, part.w, "three times")
        for (frame in listOf(cropFrameIn(16.0, 40.0, 2368.0, 600.0), cropFrameIn(0.0, 0.0, 300.0, 300.0))) {
            val other = CropLimits(1080, 2400, frame, leastPixels = 1080.0)
            near(part, other.rectOf(other.viewOf(part)))
            near(part, shot.rectOf(shot.viewOf(other.rectOf(other.viewOf(part)))))
        }
    }

    @Test
    fun everyPartAGifCanBeMovedOrSizedToIsOneVillaNetKeeps() {
        // Whatever is done to it, by any picture and window: fails if the 0.05 limit or the clamp
        // to the edges is taken away.
        val rnd = Random(29)
        // Small GIFs, sizable to three times, among them: a typical one, two smaller, and a tall one near the limit.
        val small = listOf(498 to 280, 320 to 240, 200 to 200, 100 to 660, 360 to 2380)
        for ((w, h) in small) assertTrue("$w x $h", gifCropPossible(w, h))
        repeat(400 + 40 * small.size) { i ->
            val (w, h) = if (i < 400) rnd.nextInt(64, 2401) to rnd.nextInt(64, 2401) else small[(i - 400) / 40]
            if (!gifCropPossible(w, h)) return@repeat
            val frame = cropFrameIn(rnd.nextDouble(0.0, 50.0), rnd.nextDouble(0.0, 300.0), rnd.nextDouble(150.0, 2400.0), rnd.nextDouble(50.0, 1800.0))
            val l = CropLimits(w, h, frame, GIF_CROP_MIN_PART)
            var v = l.viewOf(middleCrop(w, h))
            repeat(20) {
                v = when (rnd.nextInt(4)) {
                    0 -> l.moved(v, rnd.nextDouble(-3000.0, 3000.0), rnd.nextDouble(-3000.0, 3000.0))
                    1 -> l.pinched(v, rnd.nextDouble(0.0, 2400.0), rnd.nextDouble(0.0, 2400.0), rnd.nextDouble(0.2, 5.0), rnd.nextDouble(-200.0, 200.0), rnd.nextDouble(-200.0, 200.0))
                    2 -> l.doubleTapped(v, rnd.nextDouble(0.0, 2400.0), rnd.nextDouble(0.0, 2400.0))
                    else -> l.keyed(v, CropKey.entries[rnd.nextInt(CropKey.entries.size)])
                }
                val c = wireCrop(l.rectOf(v))
                assertTrue("$w x $h in $frame: $c", cropAccepted(c, w, h))
            }
        }
    }

    // ---- the still cut on the phone -------------------------------------------------------------

    @Test
    fun aStillIsCutToThePartChosen() {
        // A 3000 by 2000 picture, its right half's middle band.
        assertEquals(BannerCut(1500, 750, 1500, 500, 1500, 500), bannerCutFor(CropRect(0.5, 0.375, 0.5, 0.25), 3000, 2000))
        // The same fractions on the picture decoded at half its size: the same part.
        assertEquals(BannerCut(750, 375, 750, 250, 750, 250), bannerCutFor(CropRect(0.5, 0.375, 0.5, 0.25), 1500, 1000))
        // Larger than the most a banner is: scaled to 1500 by 500. Fails if the cut were taken
        // about the centre instead of the part.
        val big = bannerCutFor(CropRect(0.0, 0.0, 0.75, 0.75 * 4000 / 3 / 3000), 4000, 3000)
        assertEquals(BannerCut(0, 0, 3000, 1000, 1500, 500), big)
        assertNotEquals(bannerCut(4000, 3000), big)
        // Never out of the picture, whatever rounding does.
        val edge = bannerCutFor(CropRect(0.9, 0.95, 0.1, 0.05), 1000, 600)
        assertTrue("$edge", edge.x + edge.cutWidth <= 1000 && edge.y + edge.cutHeight <= 600)
    }

    @Test
    fun aSmallStillSizedUpAllTheWayIsCutToABannerVillaNetTakes() {
        // A 450 by 300 picture at three times, moved to its bottom right corner: 150 by 50 of its
        // own pixels, sent at 192 by 64, three by one. Fails without the three (the window would
        // show its whole width).
        val l = CropLimits(450, 300, phone, leastPixels = 1080.0)
        val v = l.clamp(CropView(99.0, -1e6, -1e6))
        near(3 * l.minScale, v.scale, "three times")
        val r = l.rectOf(v)
        near(1.0 / 3, r.w, "a third of its width")
        val cut = bannerCutFor(r, 450, 300)
        assertEquals(BannerCut(300, 250, 150, 50, 3 * BANNER_MIN_SIDE, BANNER_MIN_SIDE), cut)
        assertEquals(3 * cut.outHeight, cut.outWidth)
    }

    @Test
    fun aStillIsDecodedForTheCutWithinTheBudget() {
        // A 50-megapixel photo, 8660 by 5773, an eighth of its width chosen.
        val s = bannerCropDecodeScale(8660, 5773, 1.0 / 8)
        assertTrue("$s", 8660.0 * s * 5773 * s <= BANNER_DECODE_MAX_PIXELS * 1.0001)
        // Its whole width chosen: the banner's width across the part, whichever way round the
        // photo turns out to be upright, and no more.
        val whole = bannerCropDecodeScale(8660, 5773, 1.0)
        near(BANNER_MAX_WIDTH.toDouble(), 5773 * whole, "the banner's width", 1.0)
        // A small part of a modest picture is decoded whole, so the part keeps every pixel it has.
        near(1.0, bannerCropDecodeScale(3000, 2000, 0.25), "whole")
        // A very long screenshot is held to the budget too.
        val long = bannerCropDecodeScale(1080, 60_000, 1.0)
        assertTrue(1080.0 * long * 60_000 * long <= BANNER_DECODE_MAX_PIXELS * 1.0001)
    }

    @Test
    fun aStillIsShownForChoosingAtASizeForTheScreen() {
        // A 50-megapixel photo on a 2400-pixel screen: twice that across its middle, at most.
        val photo = bannerPreviewScale(8660, 5773, 2400)
        assertTrue(8660 * photo <= 4800.0 + 1)
        assertTrue(8660.0 * photo * 5773 * photo <= BANNER_PREVIEW_MAX_PIXELS * 1.0001)
        // A very long screenshot: within the budget and no side over what graphics take.
        val long = bannerPreviewScale(1080, 60_000, 2400)
        assertTrue(1080.0 * long * 60_000 * long <= BANNER_PREVIEW_MAX_PIXELS * 1.0001)
        assertTrue(60_000 * long <= BANNER_PREVIEW_MAX_SIDE + 1e-6)
        // A small picture is shown at its own size.
        near(1.0, bannerPreviewScale(1200, 800, 2400), "own size")
    }

    @Test
    fun aWindowNeverShowsPastThePicture() {
        // Every view the limits give covers the window, for pictures of every shape.
        for ((w, h) in listOf(4000 to 3000, 3000 to 1000, 9000 to 600, 1080 to 20_000, 64 to 64)) {
            val l = CropLimits(w, h, phone)
            for (v in listOf(CropView(1e-6, 0.0, 0.0), CropView(1e6, 1e9, -1e9), CropView(l.minScale, phone.right, phone.bottom))) {
                val c = l.clamp(v)
                assertTrue("$w x $h $c", c.left <= phone.left + eps && c.top <= phone.top + eps)
                assertTrue("$w x $h $c", c.left + w * c.scale >= phone.right - eps && c.top + h * c.scale >= phone.bottom - eps)
            }
        }
        // And the frame's own arithmetic.
        val f = CropFrame(10.0, 20.0, 300.0, 100.0)
        near(310.0, f.right); near(120.0, f.bottom); near(160.0, f.centerX); near(70.0, f.centerY)
    }

    @Test
    fun useSendsThePartShownAfterTheWindowChangesSize() {
        // Folded: the screen's shorter side is 1080 pixels, and a 6000 by 4500 photo is sized up all the way.
        val s = BannerCropSession("victor", null, null, null, 6000, 4500)
        val folded = CropLimits(6000, 4500, phone, leastPixels = 1080.0)
        s.rect = folded.rectOf(folded.clamp(CropView(99.0, phone.left, phone.top)))
        near(1080.0 / 6000, s.rect!!.w, "1080 of its pixels across")
        // Unfolded: the limit is now 1500 pixels across, and the screen draws the part within it.
        val open = cropFrameIn(16.0, 200.0, 1800.0, 1800.0)
        val unfolded = CropLimits(6000, 4500, open, leastPixels = 1500.0)
        val shown = unfolded.rectOf(unfolded.viewOf(s.shown))
        near(1500.0 / 6000, shown.w, "the part on screen is wider")
        // Use settles first, so what is sent is what shows. Fails if settle leaves the rect as it was.
        s.settle(unfolded)
        near(shown, s.rect!!)
        assertTrue("${s.rect}", s.rect!!.w > 1080.0 / 6000 + 0.05)
        // Untouched stays untouched: the middle is then cut (or nothing sent on Adjust), as before.
        val fresh = BannerCropSession("victor", null, null, null, 6000, 4500)
        fresh.settle(unfolded)
        assertNull(fresh.rect)
    }

    @Test
    fun anUntouchedAdjustSendsThePartShownWhenTheWindowWidenedIt() {
        // Folded (shorter side 720), victor sized a 2400 by 1800 GIF all the way in and pressed Use.
        val folded = CropLimits(2400, 1800, phone, GIF_CROP_MIN_PART, 720.0)
        val kept = wireCrop(folded.rectOf(folded.clamp(CropView(99.0, phone.left - 300.0, phone.top - 400.0)))).rect
        near(0.3, kept.w, "720 of its pixels across")
        // Unfolded (shorter side 1500), Adjust opens: the window can't size it up that far (three
        // times is 800 of its pixels) and shows a wider part.
        val open = cropFrameIn(16.0, 200.0, 1800.0, 1800.0)
        val unfolded = CropLimits(2400, 1800, open, GIF_CROP_MIN_PART, 1500.0)
        val s = BannerCropSession("victor", null, null, ByteArray(1), 2400, 1800, adjusting = true, start = kept)
        val shown = unfolded.rectOf(unfolded.viewOf(s.shown))
        near(1.0 / 3, shown.w, "the part on screen")
        // Use without a touch sends that part. Fails if settle leaves an untouched Adjust alone.
        s.settle(unfolded)
        near(shown, s.rect!!)
        // In the window it was chosen in, it shows as the profile draws it: nothing to send. Fails if
        // the crop's own numbers are compared (four places are a hair off three by one).
        val same = BannerCropSession("victor", null, null, ByteArray(1), 2400, 1800, adjusting = true, start = kept)
        same.settle(folded)
        assertNull(same.rect)
        // One kept from a part chosen mid-way comes back a ten-thousandth wider on its own: still nothing to send.
        val odd = CropRect(0.3524, 0.1369, 0.3584, 0.1376)
        val oddLimits = CropLimits(3267, 2836, phone, GIF_CROP_MIN_PART, 1080.0)
        assertNotEquals(wireCrop(odd), wireCrop(oddLimits.rectOf(oddLimits.viewOf(odd))))
        val mid = BannerCropSession("victor", null, null, ByteArray(1), 3267, 2836, adjusting = true, start = odd)
        mid.settle(oddLimits)
        assertNull(mid.rect)
        // A crop the window can show as it is stays untouched in either window.
        val inside = wireCrop(unfolded.rectOf(unfolded.viewOf(CropRect(0.1, 0.3, 0.8, 0.3556)))).rect
        for (limits in listOf(folded, unfolded)) {
            val t = BannerCropSession("victor", null, null, ByteArray(1), 2400, 1800, adjusting = true, start = inside)
            t.settle(limits)
            assertNull(t.rect)
        }
    }

    @Test
    fun aCropScreenAPickLeftIsNotDrawnEvenForTheFirstFrame() {
        // Found on opening victor's profile: the effect cancels it after the first frame, which must not draw it.
        // Fails if the profile draws every session of its own.
        val left = BannerCropSession("victor", null, null, null, 4000, 3000)
        assertFalse(left.showsOn("victor", mine = true, left = left))
        // A pick made with the profile open is drawn, and nobody else's profile draws one.
        val picked = BannerCropSession("victor", null, null, null, 4000, 3000)
        assertTrue(picked.showsOn("victor", mine = true, left = left))
        assertTrue(picked.showsOn("victor", mine = true, left = null))
        assertFalse(picked.showsOn("alice", mine = false, left = null))
        assertFalse(picked.showsOn("victor", mine = false, left = null))
    }

    @Test
    fun aCropScreenAPickLeftAfterTheProfileClosedGoesOnOpeningIt() {
        // A pick that finished reading after victor's profile closed: nothing asked for it this time.
        // Fails if opening the profile keeps such a session.
        val s = BannerCropSession("victor", null, null, null, 4000, 3000)
        assertTrue(s.leftByPick("victor", busy = false))
        // A Use still on its way is left alone, and so is another person's profile.
        assertFalse(s.leftByPick("victor", busy = true))
        assertFalse(s.leftByPick("alice", busy = false))
        // A failed Use stays with its sentence, to be tried again.
        s.trouble = "That part can't be kept."
        assertFalse(s.leftByPick("victor", busy = false))
    }

    @Test
    fun aPictureTooSmallToSizeUpIsOnlyDragged() {
        // A 411dp phone: 1080 pixels on the shorter side, a window about 996 pixels wide.
        val window = cropFrameIn(0.0, 0.0, 996.0, 1800.0)
        // A GIF whose largest three-by-one part is already VillaNET's smallest: the hint says "Drag".
        // Fails if every picture counts as sizable.
        assertFalse(CropLimits(100, 660, window, GIF_CROP_MIN_PART, 1080.0).sizable)
        // A typical GIF and a phone screenshot have no more pixels across than the window, and can
        // be sized up all the same. Fails without the three.
        assertTrue(CropLimits(498, 280, window, GIF_CROP_MIN_PART, 1080.0).sizable)
        assertTrue(CropLimits(1080, 2400, window, 0.0, 1080.0).sizable)
        // A photo can be pinched. Fails if no picture counts as sizable.
        assertTrue(CropLimits(4000, 3000, window, 0.0, 1080.0).sizable)
        assertTrue(CropLimits(2400, 2400, window, GIF_CROP_MIN_PART, 1080.0).sizable)
    }

    // ---- Adjust names the picture it was opened for -----------------------------------------------

    @Test
    fun theCropsBodyNamesThePictureAsTheJsonLibraryWritesIt() {
        val c = WireCrop(1000, 2500, 5000, 3333)
        assertEquals("{\"crop\":[0.1,0.25,0.5,0.3333],\"v\":\"0123456789abcdef\"}", bannerCropBody(c, "0123456789abcdef"))
        assertEquals("{\"crop\":null,\"v\":\"0123456789abcdef\"}", bannerCropBody(null, "0123456789abcdef"))
        // Without a v, the body round 1 sent.
        assertEquals("{\"crop\":[0.1,0.25,0.5,0.3333]}", bannerCropBody(c, null))
        assertEquals("{\"crop\":null}", bannerCropBody(null, null))
        // A v that isn't what the list should hold still comes out one JSON string. Fails if it is
        // pasted between quotes.
        val hostile = "a\"b\\c\nd"
        val body = bannerCropBody(c, hostile)
        assertEquals("{\"crop\":[0.1,0.25,0.5,0.3333],\"v\":\"a\\\"b\\\\c\\nd\"}", body)
        val o = Json.parseToJsonElement(body).jsonObject
        assertEquals(setOf("crop", "v"), o.keys)
        assertEquals(hostile, o["v"]!!.jsonPrimitive.content)
    }

    @Test
    fun adjustingABannerThatChangedOrWentClosesTheScreen() {
        // Trying again can't work: the screen closes. Fails if it stays for every refusal.
        assertTrue(bannerUseRefusalCloses("conflict", adjusting = true))
        assertTrue(bannerUseRefusalCloses("not_found", adjusting = true))
        // Anything else stays to be tried again, and so does a picture just picked. Fails if every refusal closes it.
        for (code in listOf("too_often", "bad_crop", "store", "")) assertFalse(code, bannerUseRefusalCloses(code, adjusting = true))
        assertFalse(bannerUseRefusalCloses("conflict", adjusting = false))
        assertFalse(bannerUseRefusalCloses("not_found", adjusting = false))
    }
}
