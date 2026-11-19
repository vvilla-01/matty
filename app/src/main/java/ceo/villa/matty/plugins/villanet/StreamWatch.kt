package ceo.villa.matty.plugins.villanet

/** What a look at a playing stream says to do: see [StreamWatch.look]. */
internal data class Look(
    /** Whether the stream's picture is to be received from here on. */
    val picture: Boolean,
    /** Nothing has arrived for long enough that VillaNET is to be asked about the stream. */
    val ask: Boolean,
)

/**
 * What a playing stream is watched for, a look every few seconds at how much of it has arrived.
 *
 * **A stream that has stopped arriving.** The connection itself can outlive the broadcast (the
 * relay still answers, with nothing to relay), and with Matty minimised nobody is looking at the
 * panel to see it end, so this is what lets go of a dead stream. Silence alone doesn't say which:
 * a streamer who paused sends nothing either, says "Back soon", and comes back on this same
 * connection, so their viewers wait as they do on the web. So after [askAfter] looks with nothing
 * received VillaNET is asked (a push may have been missed while minimised), and again each time
 * that many more pass: a stream that has left the list is let go at once, a paused one is waited
 * for, and one still listed as on the air is only given up on after [deadAfter].
 *
 * **A picture nobody is looking at.** With Matty minimised only the sound is wanted, and
 * receiving the picture means decoding every frame of it. Once it has been unwanted for
 * [pictureOffAfterMs] (a glance at another app and back changes nothing) it is no longer
 * received, but only while the sound is seen arriving. A picture that isn't received counts no
 * bytes, so from then on the sound is all that says the stream is alive: a stream with no sound
 * keeps its picture, and the moment the sound stops the picture is received again before any
 * silence is counted. What isn't looked for is never taken for missing.
 *
 * Not thread-safe: one watch per connection, used from one thread.
 */
internal class StreamWatch(
    private val askAfter: Int,
    private val deadAfter: Int,
    private val pictureOffAfterMs: Long,
) {
    private var audio = -1L
    private var total = -1L
    private var quiet = 0

    /** The picture wasn't being received since the last look: nothing can be said of it. */
    private var blind = false

    /**
     * One look: the sound's and the picture's bytes received so far, and for how long the picture
     * has been unwanted (null while it is wanted).
     */
    fun look(audioBytes: Long, videoBytes: Long, unwantedMs: Long?): Look {
        // Heard between two looks, not just at some time: a stream whose sound has stopped
        // (or that never had any) is one whose picture is the only sign of life.
        val heard = audio >= 0 && audioBytes > audio
        audio = audioBytes
        val picture = unwantedMs == null || unwantedMs < pictureOffAfterMs || !heard

        val sum = audioBytes + videoBytes
        val arrived = sum != total
        total = sum
        val couldSee = !blind
        blind = !picture
        if (arrived || !couldSee) {
            quiet = 0
            return Look(picture, ask = false)
        }
        return Look(picture, ask = ++quiet % askAfter == 0)
    }

    /** What VillaNET said of the stream when asked ([Look.ask]): true when it is to be let go. */
    fun answered(onAir: OnAir): Boolean = when (onAir) {
        OnAir.Gone -> true
        OnAir.Paused -> { quiet = 0; false }
        OnAir.Live, OnAir.Unknown -> quiet >= deadAfter
    }
}
