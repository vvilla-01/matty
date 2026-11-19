package ceo.villa.matty

import ceo.villa.matty.plugins.villanet.Look
import ceo.villa.matty.plugins.villanet.OnAir
import ceo.villa.matty.plugins.villanet.StreamWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A playing stream, looked at every few seconds: when its picture stops being received (Matty
 * minimised, playing for the sound), and when silence means the stream is gone.
 */
class StreamWatchTest {
    /** A stream as the player sees it: what has arrived so far, and what the watch says of it. */
    private class Stream(askAfter: Int = 5, deadAfter: Int = 72) {
        val watch = StreamWatch(askAfter, deadAfter, pictureOffAfterMs = 10_000)
        var audio = 0L
        var video = 0L
        /** What the last look said: the player does as it says. */
        var picture = true
        var unwantedMs: Long? = null

        /**
         * Five seconds pass. [sound] and [frames] are what the stream sends meanwhile; a picture
         * that isn't being received counts nothing, whatever is sent.
         */
        fun look(sound: Boolean = true, frames: Boolean = true): Look {
            if (sound) audio += 40_000
            if (frames && picture) video += 1_500_000
            unwantedMs = unwantedMs?.plus(5_000)
            return watch.look(audio, video, unwantedMs).also { picture = it.picture }
        }

        fun minimise() { unwantedMs = 0 }
        fun comeBack() { unwantedMs = null; picture = true }
    }

    @Test
    fun minimisedTheSoundPlaysOnWithoutThePicture() {
        val s = Stream()
        repeat(3) { assertEquals("on screen: all of it", Look(picture = true, ask = false), s.look()) }

        s.minimise()
        assertTrue("a glance away changes nothing", s.look().picture)
        assertFalse("ten seconds unseen, and the sound arriving: the sound alone", s.look().picture)
        repeat(100) {
            val l = s.look()
            assertFalse("for as long as it's minimised", l.picture)
            assertFalse("and never taken for silent: the sound is arriving", l.ask)
        }

        s.comeBack()
        assertTrue(s.look().picture)
        assertEquals(Look(picture = true, ask = false), s.look())
    }

    @Test
    fun aGlanceAtAnotherAppAndBackChangesNothing() {
        val s = Stream()
        repeat(2) { s.look() }
        s.minimise()
        assertTrue(s.look().picture)
        s.comeBack()
        repeat(5) { assertEquals(Look(picture = true, ask = false), s.look()) }
    }

    @Test
    fun aConnectionMadeWhileMinimisedKeepsItsPictureUntilTheSoundIsSeenArriving() {
        // The connection dropped and came back with Matty long minimised: a new watch.
        val s = Stream()
        s.unwantedMs = 60_000
        assertTrue("some sound has arrived, but it hasn't been seen arriving", s.look().picture)
        assertFalse("more of it since the last look: it is", s.look().picture)
    }

    @Test
    fun aStreamWithNoSoundKeepsItsPicture() {
        val s = Stream()
        s.minimise()
        repeat(200) {
            val l = s.look(sound = false)
            assertTrue("its picture is the only sign of life", l.picture)
            assertFalse("and it is alive", l.ask)
        }
    }

    @Test
    fun theSoundStoppingBringsThePictureBackBeforeAnySilenceIsCounted() {
        val s = Stream()
        s.minimise()
        repeat(4) { s.look() }
        assertFalse(s.picture)

        // The streamer's sound goes (a muted source); the picture is still being sent.
        assertEquals("looked for again, and nothing concluded yet", Look(picture = true, ask = false), s.look(sound = false))
        repeat(200) {
            val l = s.look(sound = false)
            assertTrue(l.picture)
            assertFalse("a stream with a picture and no sound is not a dead one", l.ask)
        }

        // The sound comes back: the picture can go again.
        s.look()
        assertFalse(s.look().picture)
    }

    @Test
    fun aPausedStreamIsWaitedForAndAnEndedOneLetGo() {
        val s = Stream()
        s.minimise()
        repeat(4) { s.look() }
        assertFalse(s.picture)

        // The streamer pauses: nothing is sent at all.
        assertEquals("the picture is looked for first", Look(picture = true, ask = false), s.look(sound = false, frames = false))
        repeat(4) { assertFalse(s.look(sound = false, frames = false).ask) }
        assertTrue("25 seconds of nothing, all of it looked for: VillaNET is asked", s.look(sound = false, frames = false).ask)
        assertFalse("\"Back soon\": waited for", s.watch.answered(OnAir.Paused))

        // Still paused: asked again every so often, waited for every time.
        repeat(20) {
            repeat(4) { assertFalse(s.look(sound = false, frames = false).ask) }
            assertTrue(s.look(sound = false, frames = false).ask)
            assertFalse(s.watch.answered(OnAir.Paused))
        }

        // Back on the list as live, still silent: the pause started the count again, so it is
        // given the whole while again, not let go at the next ask.
        repeat(4) { assertFalse(s.look(sound = false, frames = false).ask) }
        assertTrue(s.look(sound = false, frames = false).ask)
        assertFalse("listed as live after a pause: the count started again", s.watch.answered(OnAir.Live))

        // They come back, on the same connection: all is well, and the picture goes again.
        assertEquals(Look(picture = false, ask = false), s.look())
        assertEquals(Look(picture = false, ask = false), s.look())

        // And then the stream ends.
        s.look(sound = false, frames = false)
        repeat(4) { assertFalse(s.look(sound = false, frames = false).ask) }
        assertTrue(s.look(sound = false, frames = false).ask)
        assertTrue("off the list: let go at once", s.watch.answered(OnAir.Gone))
    }

    @Test
    fun aSilentStreamStillListedIsOnlyGivenUpOnAfterALongWhile() {
        for (said in listOf(OnAir.Live, OnAir.Unknown)) {
            val s = Stream()
            s.look()
            var looks = 0
            var gone = false
            while (!gone && looks < 1000) {
                looks++
                if (s.look(sound = false, frames = false).ask) gone = s.watch.answered(said)
            }
            // Asked at every fifth look; the first of those at or past the 72nd is the end.
            assertEquals("six minutes and a bit, whatever a slow poll of Broadcast Box hasn't noticed yet ($said)", 75, looks)
        }
    }

    @Test
    fun onScreenSilenceIsCountedFromTheFirstLook() {
        val s = Stream()
        s.look()
        repeat(4) { assertFalse(s.look(sound = false, frames = false).ask) }
        assertTrue(s.look(sound = false, frames = false).ask)
        // A paused stream's count starts again; one that sends again is simply alive.
        assertFalse(s.watch.answered(OnAir.Paused))
        repeat(4) { assertFalse(s.look(sound = false, frames = false).ask) }
        assertFalse("something arrived", s.look().ask)
        repeat(4) { assertFalse(s.look(sound = false, frames = false).ask) }
        assertTrue(s.look(sound = false, frames = false).ask)
    }
}
