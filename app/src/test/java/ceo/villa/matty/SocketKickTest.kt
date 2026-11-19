package ceo.villa.matty

import ceo.villa.matty.data.api.MattermostSocket
import ceo.villa.matty.data.api.SocketState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a kick (the network back, the app in front) starts the WebSocket again, and when it leaves it be. */
class SocketKickTest {
    @Test fun anAttemptThatHasJustBegunIsLeftToFinish() {
        // The app comes to the foreground a moment after a cold start began connecting.
        assertFalse(MattermostSocket.kickReopens(SocketState.Connecting, 0))
        assertFalse(MattermostSocket.kickReopens(SocketState.Connecting, MattermostSocket.FRESH_ATTEMPT_MS - 1))
        // One that has hung that long is started again.
        assertTrue(MattermostSocket.kickReopens(SocketState.Connecting, MattermostSocket.FRESH_ATTEMPT_MS))
        assertTrue(MattermostSocket.kickReopens(SocketState.Disconnected, 0))
        assertFalse(MattermostSocket.kickReopens(SocketState.Connected, 60_000))
    }

    private val text = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
        .map { java.io.File(it, "app/src/main/java/ceo/villa/matty/data/api/MattermostSocket.kt") }.first { it.isFile }.readText()

    /** What lies between [from] and [to], both asserted to be there. */
    private fun between(from: String, to: String): String {
        assertTrue("'$from' is there", from in text)
        val after = text.substringAfter(from)
        assertTrue("'$to' is there after '$from'", to in after)
        return after.substringBefore(to)
    }

    // The socket needs a live connection to fail and back off, so these hold the source itself.
    @Test fun theKickAsksIt() {
        val kick = between("fun kick() {", "private fun open()")
        assertTrue("kickReopens(_state.value, clock() - openedAt)" in kick)
        assertTrue("openedAt = clock()" in between("private fun open()", "private val listener"))
    }

    @Test fun anAttemptsAgeCountsThePhonesSleep() {
        // System.nanoTime stops while the phone sleeps: an attempt begun before an hour's sleep would look fresh.
        val clock = between("class MattermostSocket(", ") {")
        assertTrue("private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }" in clock)
        assertFalse("nanoTime" in clock)
    }

    @Test fun aKickStartsTheBackoffAfreshEvenWhenItLeavesTheAttemptAlone() {
        val kick = between("fun kick() {", "private fun open()")
        assertTrue("attempts = 0" in kick)
        assertTrue("reset before the attempt may be left alone", kick.indexOf("attempts = 0") < kick.indexOf("if (!kickReopens("))
        assertTrue("if (!kickReopens(" in kick)
    }
}
