package ceo.villa.matty

import androidx.compose.ui.unit.dp
import ceo.villa.matty.ui.profile.PICTURE_OVERLAP
import ceo.villa.matty.ui.profile.BANNER_LINGER_MS
import ceo.villa.matty.ui.profile.bannerBandHeight
import ceo.villa.matty.ui.profile.bannerDrawn
import ceo.villa.matty.ui.profile.bannerPlays
import ceo.villa.matty.ui.profile.pictureTop
import ceo.villa.matty.ui.profile.youPageInView
import ceo.villa.matty.ui.profile.youPageKeepsProfileOver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The band across the top of a profile, drawn alike on the profile sheet and on the You page: its
 * shape, where the picture sits on it, which of the two keeps the crop screen, and when the You
 * page counts as seen. How the You page uses them is held by the source itself (as PushWiringTest
 * holds push's wiring).
 */
class ProfileBandTest {
    private val main = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "app/src/main/java/ceo/villa/matty") }.first { it.isDirectory }

    /** The lines of [text] that are code, without the comments. */
    private fun code(text: String): List<String> = text.lines().map { it.trim() }
        .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
        .map { it.substringBefore("//").trim() }

    private fun body(text: String, signature: String): String {
        val start = text.indexOf(signature).also { assertTrue("$signature is there", it >= 0) }
        val open = text.indexOf('{', start)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) { '{' -> depth++; '}' -> if (--depth == 0) return text.substring(open, i + 1) }
        }
        error("unbalanced $signature")
    }

    private val youScreen by lazy { code(body(File(main, "ui/profile/Profile.kt").readText(), "fun YouScreen(")) }

    @Test
    fun theBandIsThreeWideByOneHighAtEveryWidth() {
        // A phone's sheet, the You page's card inside its 16dp margins, a tablet.
        for (width in listOf(360.dp, 328.dp, 411.dp, 840.dp)) {
            assertEquals("$width", width.value / 3, bannerBandHeight(width).value, 0.001f)
        }
    }

    @Test
    fun thePictureOverlapsTheBandsFootByTheSameAmount() {
        assertEquals(48.dp, PICTURE_OVERLAP)
        assertEquals(72.dp, pictureTop(120.dp))
        assertEquals(bannerBandHeight(328.dp) - 48.dp, pictureTop(bannerBandHeight(328.dp)))
    }

    @Test
    fun oneCropScreenWhileYourOwnSheetIsOpenOverTheYouPage() {
        assertTrue("no sheet open", youPageKeepsProfileOver("victor", null))
        assertTrue("somebody else's sheet", youPageKeepsProfileOver("victor", "alice"))
        assertFalse("your own sheet keeps it", youPageKeepsProfileOver("victor", "victor"))
    }

    @Test
    fun theYouPageIsSeenOnItsTabWithNothingOverItAndTheAppInFront() {
        assertTrue(youPageInView(youTab = true, routesAbove = 0, inFront = true))
        assertFalse("another tab", youPageInView(youTab = false, routesAbove = 0, inFront = true))
        assertFalse("Settings over it", youPageInView(youTab = true, routesAbove = 1, inFront = true))
        assertFalse("the app in the background", youPageInView(youTab = true, routesAbove = 0, inFront = false))
    }

    @Test
    fun theYouPageDrawsTheBandAndKeepsTheCropScreenOnlyWhenTheSheetDoesNot() {
        assertTrue("the band, with whether it is seen", youScreen.any { it.startsWith("ProfileBand(u.id, nav, plugins, inView)") })
        assertTrue("seen as youPageInView says", youScreen.any { it.startsWith("val inView = youPageInView(nav.tab == HomeTab.You, nav.stack.size,") })
        val overs = youScreen.filter { "ProfileOvers(" in it }
        assertEquals("one call, guarded", listOf("if (youPageKeepsProfileOver(u.id, nav.profileUserId)) ProfileOvers(u.id, nav, plugins)"), overs)
    }

    @Test
    fun theYouPageIsNoWiderThanTheProfileSheet() {
        assertTrue(youScreen.any { "Modifier.widthIn(max = BottomSheetDefaults.SheetMaxWidth)" in it })
    }

    @Test
    fun theBannerIsOpenAndPlaysOnlyWhileItIsSeen() {
        val banner = code(body(File(main, "plugins/villanet/VillaNetBannerUi.kt").readText(), "internal fun VillaNetProfileBanner("))
        assertEquals(listOf("if (inView) LaunchedEffect(plugin, userId) { plugin.bannersProfileOpen() }"), banner.filter { "bannersProfileOpen" in it })
        assertTrue(banner.any { it == "val drawn = bannerDrawn(lifecycle.isAtLeast(Lifecycle.State.STARTED))" })
        assertTrue(banner.any { it == "val plays = bannerPlays(inView, recentlyInView)" })
        assertTrue(banner.any { it == "if (url != null && drawn) {" })
        // Seen a moment ago: set while seen, let go after the linger once not.
        val linger = banner.indexOfFirst { it == "LaunchedEffect(inView) {" }
        assertTrue(linger >= 0)
        assertEquals(listOf("if (inView) recentlyInView = true", "else {", "delay(BANNER_LINGER_MS)", "recentlyInView = false"), banner.subList(linger + 1, linger + 5))
        // The picture's own animation, taken from each success, on both paths, follows plays:
        // also one that arrives (and is started by Coil) while the page is covered.
        assertTrue(banner.any { it == "var motion by remember(url) { mutableStateOf<BannerMotion?>(null) }" })
        assertTrue(banner.any { it == "LaunchedEffect(motion, plays) { motion?.let { if (plays) it.animation.start() else it.animation.stop() } }" })
        assertTrue(banner.any { it == "val onShown = { s: AsyncImagePainter.State.Success -> motion = (s.result.image.asDrawable(res) as? Animatable)?.let { BannerMotion(it) } }" })
        assertEquals("the plain path and the cropped one", 2, banner.count { it.startsWith("AsyncImage(") && "onSuccess = onShown" in it })
        val sheet = code(body(File(main, "ui/profile/Profile.kt").readText(), "fun ProfileSheet("))
        assertTrue("the sheet is seen while it is open", sheet.any { it.startsWith("ProfileBand(userId, nav, plugins, inView = true)") })
    }

    @Test
    fun aBannerStaysDrawnUnderAViewAndAnAnimatedOneHoldsStillOnceItIsCovered() {
        // Drawn, still or animated, as long as the app is in front: nothing goes while a view comes or goes.
        assertTrue(bannerDrawn(inFront = true))
        assertFalse("minimised", bannerDrawn(inFront = false))
        // An animated one moves while seen and a moment after, then holds still.
        assertTrue(bannerPlays(inView = true, recentlyInView = true))
        assertTrue("seen at once, before the effect has run", bannerPlays(inView = true, recentlyInView = false))
        assertTrue("while a view comes in", bannerPlays(inView = false, recentlyInView = true))
        assertFalse("once it is covered", bannerPlays(inView = false, recentlyInView = false))
        assertTrue("longer than a view takes to come in, and short", BANNER_LINGER_MS in 500L..2_000L)
    }
}
