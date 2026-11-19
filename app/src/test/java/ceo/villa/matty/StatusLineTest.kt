package ceo.villa.matty

import ceo.villa.matty.plugins.UserActivity
import ceo.villa.matty.plugins.UserActivity.Kind.Playing
import ceo.villa.matty.plugins.UserActivity.Kind.Streaming
import ceo.villa.matty.ui.home.StatusLineContent
import ceo.villa.matty.ui.home.statusLineOf
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusLineTest {
    private val elden = UserActivity(Playing, "ELDEN RING", "Steam")
    private val streamed = UserActivity(Streaming, "ELDEN RING", "VillaNET")
    private val unnamed = UserActivity(Playing, "", "Steam")

    @Test
    fun liveAndPlayingShowsTheGameNotLiveNow() {
        // The LIVE ring already says they're on the air: the line says what they're playing.
        assertEquals(StatusLineContent.Activity("", elden), statusLineOf(null, null, live = true, activity = elden))
        assertEquals(StatusLineContent.Activity("", streamed), statusLineOf(null, null, live = true, activity = streamed))
        // In a game Steam doesn't name: still not "Live now".
        assertEquals(StatusLineContent.InGame(""), statusLineOf(null, null, live = true, activity = unnamed))
    }

    @Test
    fun liveWithNoGameStillSaysLiveNow() {
        assertEquals(StatusLineContent.Live(""), statusLineOf(null, null, live = true, activity = null))
        assertEquals(StatusLineContent.Live("🔥 "), statusLineOf("🔥", null, live = true, activity = null))
    }

    @Test
    fun onlyGamesNeverPresence() {
        assertEquals(StatusLineContent.None, statusLineOf(null, null, live = false, activity = null))
        assertEquals(StatusLineContent.Activity("", elden), statusLineOf(null, null, live = false, activity = elden))
    }

    @Test
    fun aStatusOfTheirOwnKeepsItsPlaceWhileLive() {
        // VillaNET's own live badge never reaches here: callers leave a held status out (ownStatus),
        // so a streamer's line is the game, or "Live now" once. What does reach here is theirs.
        assertEquals(StatusLineContent.Custom("🎧 Ranked, no DMs", elden), statusLineOf("🎧", "Ranked, no DMs", live = true, activity = elden))
        // VillaNET's cake wins over its live badge on their birthday, and isn't left out.
        assertEquals(StatusLineContent.Custom("🎂 Birthday", streamed), statusLineOf("🎂", "Birthday", live = true, activity = streamed))
        // An emoji-only status of their own still leads the game.
        assertEquals(StatusLineContent.Activity("🔥 ", elden), statusLineOf("🔥", null, live = true, activity = elden))
        // With the badge left out and no game: "Live now", said once.
        assertEquals(StatusLineContent.Live(""), statusLineOf(null, null, live = true, activity = null))
    }

    @Test
    fun offTheAirCustomStatusWordsKeepTheirPlaceAndAnEmojiLeadsTheGame() {
        assertEquals(StatusLineContent.Custom("🎮 Grinding ranked", elden), statusLineOf("🎮", "Grinding ranked", live = false, activity = elden))
        assertEquals(StatusLineContent.Activity("🔥 ", elden), statusLineOf("🔥", null, live = false, activity = elden))
        assertEquals(StatusLineContent.Custom("🔥", null), statusLineOf("🔥", null, live = false, activity = null))
    }
}
