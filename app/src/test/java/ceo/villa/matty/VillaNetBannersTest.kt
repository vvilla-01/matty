package ceo.villa.matty

import ceo.villa.matty.plugins.villanet.BANNER_MAX_HEIGHT
import ceo.villa.matty.plugins.villanet.BANNER_MAX_WIDTH
import ceo.villa.matty.plugins.villanet.BANNER_MIN_SIDE
import ceo.villa.matty.plugins.villanet.BannerCut
import ceo.villa.matty.plugins.villanet.BannerEntry
import ceo.villa.matty.plugins.villanet.BannerShelf
import ceo.villa.matty.plugins.villanet.VillaNetApi
import ceo.villa.matty.plugins.villanet.VnBanner
import ceo.villa.matty.plugins.villanet.VnBanners
import ceo.villa.matty.plugins.villanet.bannerCut
import ceo.villa.matty.plugins.villanet.bannerDecodeScale
import ceo.villa.matty.plugins.villanet.bannerEntries
import ceo.villa.matty.plugins.villanet.bannersOffered
import ceo.villa.matty.plugins.villanet.BANNER_DECODE_MAX_PIXELS
import ceo.villa.matty.plugins.villanet.BANNER_FLATTEN_COLOR
import ceo.villa.matty.plugins.villanet.BANNER_MAX_BYTES
import ceo.villa.matty.plugins.villanet.hasTransparency
import ceo.villa.matty.plugins.villanet.sendsAsPng
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Profile banners in the app: when VillaNET is asked about them, who gets the button and what it
 * offers, and how a picked picture is cut and scaled before it is sent.
 */
class VillaNetBannersTest {
    private class Fixture(scope: CoroutineScope) {
        val held = MutableStateFlow<VnBanners?>(null)
        var allowed = true

        /** Each request's tag, as it is made. */
        val calls = Channel<String>(Channel.UNLIMITED)
        var banners = VnBanners("w1", mapOf("u-bob" to VnBanner("aaaaaaaaaaaaaaaa", "image/gif")))
        var tag = "\"t1\""
        /** While set, a request waits for it before it is answered. */
        var gate: CompletableDeferred<Unit>? = null
        val shelf = BannerShelf(
            scope = scope,
            fetch = { sent ->
                calls.send(sent ?: "(no tag)")
                val answer = if (sent == tag) null else VillaNetApi.Tagged(banners, tag)
                gate?.await()
                answer
            },
            held = held, allowed = { allowed }, jitter = { 0.0 }, spreadMs = 1,
        )

        suspend fun nextCall(): String = withTimeout(2_000) { calls.receive() }
        suspend fun noCallWithin(ms: Long) = assertNull("an unexpected read of the banners", withTimeoutOrNull(ms) { calls.receive() })
        suspend fun settle() { repeat(5) { yield() }; delay(5) }
    }

    @Test
    fun readWhenAProfileOpensWithNoCopyOrABehindOne() = runBlocking {
        val f = Fixture(this)
        // Snapshots come and go with their word: nobody has opened a profile.
        f.shelf.follow("w1")
        f.noCallWithin(30)
        assertNull(f.held.value)

        // A profile opens with no copy: read, whole.
        val open = launch { f.shelf.profileOpen() }
        assertEquals("(no tag)", f.nextCall())
        f.settle()
        assertEquals("image/gif", f.held.value?.people?.get("u-bob")?.type)
        open.cancel()

        // Opened again with the word unchanged: the copy is shown, and nothing is asked.
        val again = launch { f.shelf.profileOpen() }
        f.noCallWithin(30)
        again.cancel()

        // The word moves with nobody looking: nothing is read until a profile opens.
        f.banners = VnBanners("w2", emptyMap()); f.tag = "\"t2\""
        f.shelf.follow("w2")
        f.noCallWithin(30)
        val third = launch { f.shelf.profileOpen() }
        assertEquals("with the tag of the copy in hand", "\"t1\"", f.nextCall())
        f.settle()
        assertEquals(emptyMap<String, VnBanner>(), f.held.value?.people)
        third.cancel()
    }

    @Test
    fun anOpenProfileReadsAgainWhenTheWordMoves() = runBlocking {
        val f = Fixture(this)
        val open = launch { f.shelf.profileOpen() }
        f.nextCall(); f.settle()
        f.shelf.follow("w1")
        f.noCallWithin(30)

        f.banners = VnBanners("w2", mapOf("u-dave" to VnBanner("bbbbbbbbbbbbbbbb", "image/png"))); f.tag = "\"t2\""
        f.shelf.follow("w2")
        // Said by every snapshot after it, still one read.
        f.shelf.follow("w2")
        assertEquals("\"t1\"", f.nextCall())
        f.settle()
        assertEquals(setOf("u-dave"), f.held.value?.people?.keys)
        f.noCallWithin(30)
        open.cancel()
    }

    @Test
    fun oneReadAtATime() = runBlocking {
        val f = Fixture(this)
        f.gate = CompletableDeferred()
        val one = launch { f.shelf.profileOpen() }
        val two = launch { f.shelf.profileOpen() }
        assertEquals("(no tag)", f.nextCall())
        f.shelf.lookedAt()
        f.noCallWithin(30)
        f.gate!!.complete(Unit)
        f.settle()
        assertEquals("w1", f.held.value?.version)
        f.noCallWithin(30)
        one.cancel(); two.cancel()
    }

    @Test
    fun yourOwnChangeShowsAtOnceAndTheListIsReadBehindIt() = runBlocking {
        val f = Fixture(this)
        val open = launch { f.shelf.profileOpen() }
        f.nextCall(); f.settle()

        f.gate = CompletableDeferred()
        f.banners = VnBanners("w2", mapOf("u-bob" to VnBanner("aaaaaaaaaaaaaaaa", "image/gif"), "u-me" to VnBanner("cccccccccccccccc", "image/jpeg"))); f.tag = "\"t2\""
        f.shelf.took("u-me", VnBanner("cccccccccccccccc", "image/jpeg"))
        // Before the server has said anything more.
        assertEquals("cccccccccccccccc", f.held.value?.people?.get("u-me")?.v)
        assertEquals("\"t1\"", f.nextCall())
        // Changed again while that read is out: exactly one more read follows it.
        f.shelf.took("u-me", null)
        assertNull(f.held.value?.people?.get("u-me"))
        f.gate!!.complete(Unit)
        assertEquals("\"t2\"", f.nextCall())
        f.settle()
        f.noCallWithin(30)
        open.cancel()
    }

    @Test
    fun aChangeRefusedAsBehindReadsTheListAgain() = runBlocking {
        val f = Fixture(this)
        val open = launch { f.shelf.profileOpen() }
        f.nextCall(); f.settle()

        // Bob's banner changed from another device, and no snapshot has said so yet: an Adjust
        // refused for it reads again at once, with the tag of the copy in hand.
        f.banners = VnBanners("w2", mapOf("u-bob" to VnBanner("bbbbbbbbbbbbbbbb", "image/png"))); f.tag = "\"t2\""
        f.shelf.behind()
        assertEquals("\"t1\"", f.nextCall())
        f.settle()
        assertEquals("bbbbbbbbbbbbbbbb", f.held.value?.people?.get("u-bob")?.v)
        // A copy that wasn't behind costs a 304 and stays as it is.
        f.shelf.behind()
        assertEquals("\"t2\"", f.nextCall())
        f.settle()
        assertEquals("w2", f.held.value?.version)
        // With a read out, one more follows it.
        f.gate = CompletableDeferred()
        f.shelf.behind()
        f.nextCall()
        f.shelf.behind()
        f.gate!!.complete(Unit)
        f.nextCall()
        f.settle()
        f.noCallWithin(30)
        open.cancel()
    }

    @Test
    fun clearedNothingStaysAndNothingIsAskedWhileNotAllowed() = runBlocking {
        val f = Fixture(this)
        val open = launch { f.shelf.profileOpen() }
        f.nextCall(); f.settle()
        f.shelf.clear()
        assertNull(f.held.value)

        // A VillaNET too old for banners (or none): asked nothing, whatever the snapshot says.
        f.allowed = false
        f.shelf.follow("w9")
        f.shelf.lookedAt()
        f.noCallWithin(30)

        // In step again: the next look reads whole, with no tag from the server that went.
        f.allowed = true
        f.shelf.lookedAt()
        assertEquals("(no tag)", f.nextCall())
        open.cancel()
    }

    @Test
    fun bannersComeWithVillaNet370() {
        assertTrue(bannersOffered("3.7.0"))
        assertTrue(bannersOffered("3.7.1"))
        assertTrue(bannersOffered("3.10.0"))
        assertTrue(bannersOffered("4.0.0"))
        assertTrue(bannersOffered("3.7.0-rc1"))
        assertFalse("the pair before them", bannersOffered("3.6.1"))
        assertFalse(bannersOffered("3.6.0"))
        // Not before VillaNET has said which it is, nor for a version that can't be read.
        assertFalse(bannersOffered(null))
        assertFalse(bannersOffered(""))
        assertFalse(bannersOffered("latest"))
    }

    @Test
    fun theButtonOffersWhatThisReaderMayDo() {
        val choose = BannerEntry.Choose
        // Your own profile.
        assertEquals(listOf(choose), bannerEntries(mine = true, hasBanner = false, admin = false, guest = false))
        assertEquals(listOf(choose, BannerEntry.RemoveMine), bannerEntries(mine = true, hasBanner = true, admin = false, guest = false))
        assertEquals("an admin's own is like anybody's", listOf(choose, BannerEntry.RemoveMine), bannerEntries(mine = true, hasBanner = true, admin = true, guest = false))
        // A guest can't have one, but can still take one away.
        assertEquals(emptyList<BannerEntry>(), bannerEntries(mine = true, hasBanner = false, admin = false, guest = true))
        assertEquals(listOf(BannerEntry.RemoveMine), bannerEntries(mine = true, hasBanner = true, admin = false, guest = true))
        // A system admin on somebody else's profile: only one with a banner.
        assertEquals(listOf(BannerEntry.RemoveTheirs), bannerEntries(mine = false, hasBanner = true, admin = true, guest = false))
        assertEquals(emptyList<BannerEntry>(), bannerEntries(mine = false, hasBanner = false, admin = true, guest = false))
        // Anybody else: no button at all.
        assertEquals(emptyList<BannerEntry>(), bannerEntries(mine = false, hasBanner = true, admin = false, guest = false))
        assertEquals(emptyList<BannerEntry>(), bannerEntries(mine = false, hasBanner = false, admin = false, guest = false))
    }

    @Test
    fun aPictureIsCutAboutItsCentreToThreeByOne() {
        // Wide: the full height, the middle of the width.
        assertEquals(BannerCut(x = 100, y = 0, cutWidth = 1200, cutHeight = 400, outWidth = 1200, outHeight = 400), bannerCut(1400, 400))
        // Tall (a phone's portrait): the full width, the middle of the height.
        assertEquals(BannerCut(x = 0, y = 840, cutWidth = 1080, cutHeight = 360, outWidth = 1080, outHeight = 360), bannerCut(1080, 2040))
        // Exactly three by one already: all of it.
        assertEquals(BannerCut(0, 0, 900, 300, 900, 300), bannerCut(900, 300))
        // Huge: cut, then scaled to the most sent.
        val huge = bannerCut(12000, 9000)
        assertEquals(BannerCut(0, 2500, 12000, 4000, BANNER_MAX_WIDTH, BANNER_MAX_HEIGHT), huge)
        assertEquals(BannerCut(1500, 0, 15000, 5000, BANNER_MAX_WIDTH, BANNER_MAX_HEIGHT), bannerCut(18000, 5000))
        // Small: scaled up to the least VillaNET takes, never sent under it.
        assertEquals(BannerCut(0, 33, 100, 33, 3 * BANNER_MIN_SIDE, BANNER_MIN_SIDE), bannerCut(100, 100))
        assertEquals(BannerCut(0, 0, 192, 64, 192, 64), bannerCut(192, 64))
        assertEquals(BannerCut(1, 0, 3, 1, 3 * BANNER_MIN_SIDE, BANNER_MIN_SIDE), bannerCut(5, 1))
    }

    @Test
    fun aBigPictureIsDecodedSmallerWithRoomForTheCut() {
        // Never scaled up as it decodes, and a picture the banner's size or smaller decodes whole.
        assertEquals(1.0, bannerDecodeScale(1400, 400), 0.0)
        assertEquals(1.0, bannerDecodeScale(100, 100), 0.0)
        // A 48-megapixel photo: a fraction of it, whichever way up it turns out to be.
        for ((w, h) in listOf(8000 to 6000, 6000 to 8000)) {
            val s = bannerDecodeScale(w, h)
            assertTrue("$w x $h decodes at most a quarter of each side", s <= 0.25 + 1e-9)
            for ((uw, uh) in listOf(w to h, h to w)) {
                val cut = bannerCut((uw * s).toInt(), (uh * s).toInt())
                assertTrue("$uw x $uh still cuts the full width at ${s}", cut.cutWidth >= BANNER_MAX_WIDTH - 1)
            }
        }
    }

    @Test
    fun aSeeThroughPictureStaysSeeThroughWhenItFits() {
        val opaque = IntArray(16) { 0xFF336699.toInt() }
        assertFalse(hasTransparency(opaque))
        assertTrue(hasTransparency(opaque.copyOf().also { it[7] = 0x00000000 }))
        assertTrue(hasTransparency(opaque.copyOf().also { it[0] = 0x80336699.toInt() }))
        // A PNG up to VillaNET's 2 MiB is sent as it is; over it, or with nothing see-through, a JPEG.
        assertTrue(sendsAsPng(true, (2 shl 20)))
        assertFalse(sendsAsPng(true, (2 shl 20) + 1))
        assertFalse(sendsAsPng(false, 1000))
        assertEquals(2L shl 20, BANNER_MAX_BYTES)
        // Flattened, it is laid on an opaque grey that is not black.
        assertEquals(0xFF, BANNER_FLATTEN_COLOR ushr 24)
        assertTrue((BANNER_FLATTEN_COLOR and 0xFFFFFF) != 0)
    }

    @Test
    fun aVeryLongPictureIsHeldToABudgetOfPixels() {
        // A scrolling screenshot keeps its whole width by the cut's rule: the budget bounds it.
        for ((w, h) in listOf(1080 to 20000, 20000 to 1000, 100000 to 300, 64 to 1000000)) {
            val s = bannerDecodeScale(w, h)
            assertTrue("$w x $h is decoded to at most the budget", (w * s) * (h * s) <= BANNER_DECODE_MAX_PIXELS + 1.0)
            val cut = bannerCut(maxOf(1, (w * s).toInt()), maxOf(1, (h * s).toInt()))
            assertTrue("$w x $h is still sent at least 64 high", cut.outHeight >= 64 && cut.outWidth <= BANNER_MAX_WIDTH)
        }
        // An ordinary photo is untouched by it.
        assertEquals(0.5, bannerDecodeScale(4000, 3000), 1e-9)
    }
}
