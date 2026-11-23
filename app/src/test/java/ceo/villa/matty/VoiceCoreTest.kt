package ceo.villa.matty

import ceo.villa.matty.plugins.villanet.MicPermission
import ceo.villa.matty.plugins.villanet.VnVoiceJoined
import ceo.villa.matty.plugins.villanet.VoiceCore
import ceo.villa.matty.plugins.villanet.VoiceJoin
import ceo.villa.matty.plugins.villanet.VoiceLink
import ceo.villa.matty.plugins.villanet.VoiceMessage
import ceo.villa.matty.plugins.villanet.VoicePort
import ceo.villa.matty.plugins.villanet.VoiceRefusal
import ceo.villa.matty.plugins.villanet.VoiceRetry
import ceo.villa.matty.plugins.villanet.VoiceSeat
import ceo.villa.matty.plugins.villanet.VoiceStateSender
import ceo.villa.matty.plugins.villanet.VoiceStatus
import ceo.villa.matty.plugins.villanet.voiceMessageOf
import ceo.villa.matty.plugins.villanet.voiceRefusalOf
import ceo.villa.matty.plugins.villanet.voiceShown
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The voice session's state machine, driven by a fake signalling side and a fake clock. */
class VoiceCoreTest {
    /** Records what the core asks, and runs its timers when the clock is moved on. */
    private class FakePort : VoicePort {
        var clock = 0L
        private val timers = mutableListOf<Pair<Long, () -> Unit>>()
        val calls = mutableListOf<String>()
        val joins = mutableListOf<Pair<Int, VoiceJoin>>()
        val states = mutableListOf<Pair<Boolean, Boolean>>()
        val moves = mutableListOf<Pair<Int, Int>>()
        val notices = mutableListOf<String>()
        var recording: Boolean? = null
        var speakerMuted: Boolean? = null
        var online = true
        /** How many of the next state sends the server answers with [stateRefusal] (reported back to [core]). */
        var failStates = 0
        var stateRefusal = VoiceRefusal(VoiceRetry.Wait, "Matty is not answering. Try again in a moment.")
        var core: VoiceCore? = null

        override fun now() = clock
        override fun wall() = 1_700_000_000_000L + clock
        override fun later(ms: Long, what: () -> Unit): () -> Unit {
            val t = (clock + ms) to what
            timers += t
            return { timers.remove(t) }
        }
        override fun begin() { calls += "begin" }
        override fun connect(attempt: Int, join: VoiceJoin) { joins += attempt to join; calls += "connect" }
        override fun disconnect() { calls += "disconnect" }
        override fun end(tell: Boolean) { calls += if (tell) "end+leave" else "end" }
        override fun recording(on: Boolean) { recording = on }
        override fun speakerMuted(on: Boolean) { speakerMuted = on }
        override fun sendState(attempt: Int, muted: Boolean, deafened: Boolean) {
            states += muted to deafened
            if (failStates > 0) { failStates--; core?.stateUnsent(attempt, stateRefusal) } else core?.stateSent(attempt)
        }
        override fun sendMove(attempt: Int, channel: Int) { moves += attempt to channel }
        override fun online() = online
        override fun micGranted() { calls += "micGranted" }
        override fun notice(text: String) { notices += text }
        /** What was kept of a refusal for good, in order. */
        val refusals = mutableListOf<Boolean>()
        override fun rememberRefusal(forGood: Boolean) { refusals += forGood }

        /** Moves the clock on by [ms], running every timer that falls due, in order. */
        fun advance(ms: Long) {
            val until = clock + ms
            while (true) {
                val next = timers.filter { it.first <= until }.minByOrNull { it.first } ?: break
                timers.remove(next)
                clock = next.first
                next.second()
            }
            clock = until
        }

        val lastAttempt get() = joins.last().first
    }

    private val joined = VnVoiceJoined(sdp = "v=0", session = 12, channel = 1, name = "dave (Matty)")

    private fun connected(port: FakePort = FakePort(), mic: Boolean = true, muted: Boolean = false): Pair<VoiceCore, FakePort> {
        val core = VoiceCore(port)
        port.core = core
        core.micChecked(mic)
        core.join(1, muted, "u-dave")
        core.answered(port.lastAttempt, joined)
        core.link(port.lastAttempt, VoiceLink.Connected)
        return core to port
    }

    @Test
    fun aJoinConnectsAndIsConnectedOnceAnsweredAndLinked() {
        val port = FakePort()
        val core = VoiceCore(port)
        core.micChecked(true)
        core.join(1, muted = false, userId = "u-dave")
        assertEquals(VoiceStatus.Connecting, core.status.value)
        assertEquals(listOf("begin", "connect"), port.calls)
        assertEquals(VoiceJoin(channel = 1, muted = false, deafened = false, record = true), port.joins.single().second)
        assertEquals(true, port.recording)

        core.answered(port.lastAttempt, joined)
        assertEquals("answered is not yet heard", VoiceStatus.Connecting, core.status.value)
        assertEquals(VoiceSeat(12, "dave (Matty)", 1, "u-dave"), core.seat.value)
        core.link(port.lastAttempt, VoiceLink.Connected)
        assertEquals(VoiceStatus.Connected, core.status.value)
        assertEquals(port.wall(), core.since.value)
        assertTrue("nothing changed while joining: nothing more to tell", port.states.isEmpty())
    }

    @Test
    fun aJoinWithoutTheMicrophoneIsMutedAndAsksForIt() {
        val port = FakePort()
        val core = VoiceCore(port)
        core.join(1, muted = false, userId = "u-dave")
        assertTrue(core.micWanted.value)
        assertTrue("muted until the microphone is allowed", core.muted.value)
        assertFalse(port.joins.single().second.record)
        assertTrue(port.joins.single().second.muted)
        core.answered(port.lastAttempt, joined)
        core.link(port.lastAttempt, VoiceLink.Connected)

        core.micAnswered(true, forGood = false)
        assertFalse(core.micWanted.value)
        assertEquals(MicPermission.Granted, core.mic.value)
        assertTrue("the service takes the microphone", "micGranted" in port.calls)
        assertFalse(core.muted.value)
        assertEquals(true, port.recording)
        assertEquals(false to false, port.states.last())
    }

    @Test
    fun aRefusedMicrophoneLeavesThePersonInListening() {
        val port = FakePort()
        val core = VoiceCore(port)
        core.join(1, muted = false, userId = "u-dave")
        core.answered(port.lastAttempt, joined)
        core.link(port.lastAttempt, VoiceLink.Connected)
        core.micAnswered(false, forGood = true)
        assertEquals(MicPermission.Refused, core.mic.value)
        assertEquals(VoiceStatus.Connected, core.status.value)
        assertTrue(core.muted.value && core.selfMuted.value)
        assertEquals(false, port.recording)
        // Unmuting again asks again.
        core.setMuted(false)
        assertTrue(core.micWanted.value)
        assertTrue(core.muted.value)
    }

    @Test
    fun aJoinMutedNeverOpensTheMicrophone() {
        val port = FakePort()
        val core = VoiceCore(port)
        core.micChecked(true)
        core.join(1, muted = true, userId = "u-dave")
        assertFalse(core.micWanted.value)
        assertEquals(VoiceJoin(1, muted = true, deafened = false, record = false), port.joins.single().second)
        assertEquals(false, port.recording)
    }

    @Test
    fun muteAndDeafenStopTheMicrophoneAndTheSoundAndTellTheServer() {
        val (core, port) = connected()
        core.setMuted(true)
        assertEquals(false, port.recording)
        assertEquals(true to false, port.states.last())

        core.setDeafened(true)
        assertEquals(true, port.speakerMuted)
        assertTrue(core.muted.value && core.deafened.value)
        assertEquals(true to true, port.states.last())

        // Undeafened, the mute chosen before stands.
        core.setDeafened(false)
        assertEquals(false, port.speakerMuted)
        assertTrue(core.muted.value)
        assertEquals(true to false, port.states.last())

        // Unmuting while deafened undeafens too.
        core.setDeafened(true)
        core.setMuted(false)
        assertFalse(core.deafened.value || core.muted.value)
        assertEquals(true, port.recording)
        assertEquals(false to false, port.states.last())
    }

    @Test
    fun aChangeWhileTheJoinIsOnItsWayIsToldOnceAnswered() {
        val port = FakePort()
        val core = VoiceCore(port)
        core.micChecked(true)
        core.join(1, muted = false, userId = "u-dave")
        core.setMuted(true)
        assertTrue("no session to tell yet", port.states.isEmpty())
        core.answered(port.lastAttempt, joined)
        assertEquals(true to false, port.states.single())
    }

    @Test
    fun aPhoneCallDeafensAndMutesUntilItEnds() {
        val (core, port) = connected()
        core.held(true)
        assertTrue(core.deafened.value && core.muted.value)
        assertFalse("the person's own choices stand", core.selfMuted.value || core.selfDeafened.value)
        assertEquals(false, port.recording)
        assertEquals(true, port.speakerMuted)
        assertEquals(true to true, port.states.last())
        core.held(false)
        assertFalse(core.deafened.value || core.muted.value)
        assertEquals(true, port.recording)
        assertEquals(false to false, port.states.last())
    }

    @Test
    fun theServersWordOnTheDataChannel() {
        val (core, port) = connected()
        val a = port.lastAttempt
        core.message(a, VoiceMessage.Talking(setOf(12L, 15L)))
        assertEquals(setOf(12L, 15L), core.talking.value)

        // An admin moved and muted the person.
        core.message(a, VoiceMessage.Self(channel = 2, muted = true, suppressed = false))
        assertEquals(2, core.channel.value)
        assertEquals(2, core.seat.value?.channel)
        assertTrue(core.serverMuted.value)
        assertEquals("muted by the server, the microphone is let go", false, port.recording)
        core.message(a, VoiceMessage.Self(channel = 2, muted = false, suppressed = true))
        assertTrue(core.suppressed.value)
        assertEquals(false, port.recording)
        core.message(a, VoiceMessage.Self(channel = 2, muted = false, suppressed = false))
        assertEquals(true, port.recording)

        core.message(a, VoiceMessage.End("kicked", "You were kicked by an admin."))
        assertEquals(VoiceStatus.Failed("You were kicked by an admin."), core.status.value)
        assertEquals("end", port.calls.last())
        assertTrue(core.talking.value.isEmpty())
        assertNull(core.seat.value)

        // Leaving from Failed only forgets it: everything was taken down already.
        core.leave()
        assertEquals(VoiceStatus.Idle, core.status.value)
        assertEquals("end", port.calls.last())
    }

    @Test
    fun mutedByTheVoiceServerIsSeenMutedAndATapStillChangesTheOwnMute() {
        val (core, port) = connected()
        val a = port.lastAttempt
        core.message(a, VoiceMessage.Talking(setOf(12L, 15L)))
        assertFalse(core.seenMuted.value)

        // An admin of the voice server mutes the person: shown muted, as everybody else hears them.
        core.message(a, VoiceMessage.Self(channel = 1, muted = true, suppressed = false))
        assertTrue(core.seenMuted.value)
        assertFalse("the person's own mute is as it was", core.shownMuted.value)
        assertEquals("no ring of their own", setOf(15L), core.talking.value)

        // A tap changes the person's own mute, and the server mute still holds.
        core.setMuted(!core.shownMuted.value)
        assertTrue(core.selfMuted.value)
        assertEquals(true to false, port.states.last())
        assertTrue(core.seenMuted.value)
        core.setMuted(!core.shownMuted.value)
        assertFalse(core.selfMuted.value)
        assertEquals(false to false, port.states.last())
        assertTrue(core.seenMuted.value)

        // Suppressed likewise; let go, the person is heard as they asked.
        core.message(a, VoiceMessage.Self(channel = 1, muted = false, suppressed = true))
        assertTrue(core.seenMuted.value)
        assertEquals(setOf(15L), core.talking.value)
        core.message(a, VoiceMessage.Self(channel = 1, muted = false, suppressed = false))
        assertFalse(core.seenMuted.value)
        assertEquals(setOf(12L, 15L), core.talking.value)
        core.setMuted(true)
        assertTrue("muted by the person", core.seenMuted.value)
        core.setMuted(false)

        // The server's hold goes with the session.
        core.message(a, VoiceMessage.Self(channel = 1, muted = true, suppressed = false))
        assertTrue(core.seenMuted.value)
        core.leave()
        assertFalse(core.seenMuted.value)
    }

    @Test
    fun anEndWithNoSentenceIsGivenOne() {
        val (core, port) = connected()
        core.message(port.lastAttempt, VoiceMessage.End("replaced", ""))
        assertEquals(VoiceStatus.Failed("You joined voice from somewhere else."), core.status.value)
    }

    @Test
    fun theVoiceServerGoingIsTriedAgainAsALostConnectionIs() {
        val (core, port) = connected()
        core.message(port.lastAttempt, VoiceMessage.End("mumble", "The voice server went away."))
        assertEquals(VoiceStatus.Reconnecting, core.status.value)
        assertEquals("disconnect", port.calls.last())
        port.advance(1_000)
        assertEquals(2, port.joins.size)
    }

    @Test
    fun aLostConnectionIsTriedAgainOnTheSchedule() {
        val (core, port) = connected()
        val at = mutableListOf<Long>()
        core.link(port.lastAttempt, VoiceLink.Failed)
        assertEquals(VoiceStatus.Reconnecting, core.status.value)
        repeat(6) {
            val before = port.joins.size
            val start = port.clock
            while (port.joins.size == before) port.advance(100)
            at += port.clock - start
            // Each try fails before it connects.
            core.link(port.lastAttempt, VoiceLink.Failed)
        }
        assertEquals(listOf(1_000L, 2_000L, 5_000L, 10_000L, 15_000L, 15_000L), at)
        assertEquals(VoiceStatus.Reconnecting, core.status.value)
    }

    @Test
    fun aConnectedLinkPutsTheScheduleBackToTheStart() {
        val (core, port) = connected()
        core.link(port.lastAttempt, VoiceLink.Failed)
        port.advance(1_000)
        core.link(port.lastAttempt, VoiceLink.Failed)
        port.advance(2_000)
        core.answered(port.lastAttempt, joined)
        core.link(port.lastAttempt, VoiceLink.Connected)
        assertEquals(VoiceStatus.Connected, core.status.value)
        val joins = port.joins.size
        core.link(port.lastAttempt, VoiceLink.Failed)
        port.advance(1_000)
        assertEquals(joins + 1, port.joins.size)
    }

    @Test
    fun aRejoinCarriesTheMuteAsItIsNow() {
        val (core, port) = connected()
        core.setDeafened(true)
        core.link(port.lastAttempt, VoiceLink.Failed)
        port.advance(1_000)
        assertEquals(VoiceJoin(1, muted = true, deafened = true, record = false), port.joins.last().second)
    }

    @Test
    fun disconnectedForFiveSecondsIsLostAndLessIsNot() {
        val (core, port) = connected()
        core.link(port.lastAttempt, VoiceLink.Disconnected)
        port.advance(4_000)
        core.link(port.lastAttempt, VoiceLink.Connected)
        port.advance(10_000)
        assertEquals(VoiceStatus.Connected, core.status.value)
        assertEquals(1, port.joins.size)

        core.link(port.lastAttempt, VoiceLink.Disconnected)
        port.advance(5_000)
        assertEquals(VoiceStatus.Reconnecting, core.status.value)
    }

    @Test
    fun theDataChannelClosingWithoutAnEndIsALostConnection() {
        val (core, port) = connected()
        core.dataClosed(port.lastAttempt)
        assertEquals(VoiceStatus.Reconnecting, core.status.value)
    }

    @Test
    fun aJoinThatNeverConnectsIsTriedAgain() {
        val port = FakePort()
        val core = VoiceCore(port)
        core.micChecked(true)
        core.join(1, false, "u-dave")
        port.advance(VoiceCore.CONNECT_MS)
        assertEquals(VoiceStatus.Reconnecting, core.status.value)
    }

    @Test
    fun refusalsThatAreAboutTimeWaitAndTheRestStop() {
        val port = FakePort()
        val core = VoiceCore(port)
        core.micChecked(true)
        core.join(1, false, "u-dave")
        // The plugin's budget of connections to the voice server is used up for 40 s.
        core.refused(port.lastAttempt, voiceRefusalOf(503, "Too many people joined voice just now.", 40_000, "Matty"))
        assertEquals(VoiceStatus.Reconnecting, core.status.value)
        port.advance(39_000)
        assertEquals("never sooner than the server asked", 1, port.joins.size)
        port.advance(1_000)
        assertEquals(2, port.joins.size)

        core.refused(port.lastAttempt, voiceRefusalOf(429, "Too many tries.", 0, "Matty"))
        assertEquals(VoiceStatus.Reconnecting, core.status.value)

        port.advance(2_000)
        core.refused(port.lastAttempt, voiceRefusalOf(403, "That channel is full.", 0, "Matty"))
        assertEquals(VoiceStatus.Failed("That channel is full."), core.status.value)
        assertEquals("end", port.calls.last())
        port.advance(60_000)
        assertEquals("nothing more is tried", 3, port.joins.size)
    }

    /**
     * A join the server refused with its own sentence: the sentence is there for as long as the
     * wait lasts, and the retries, their times and the status are those of a refusal without one.
     */
    @Test
    fun theServersSentenceIsThereWhileItsRefusalIsWaitedOutAndNothingElseChanges() {
        val busy = "Too many new connections to voice just now. Try again in a minute."
        fun run(said: Boolean): Pair<List<Long>, List<String?>> {
            val port = FakePort()
            val core = VoiceCore(port)
            port.core = core
            core.micChecked(true)
            core.join(1, false, "u-dave")
            val at = mutableListOf<Long>()
            val words = mutableListOf<String?>()
            val refusals = listOf(
                voiceRefusalOf(429, if (said) busy else null, 81_000, "Matty"),
                voiceRefusalOf(503, if (said) "Talk is off." else null, 0, "Matty"),
                voiceRefusalOf(null, null, 0, "Matty"),
            )
            for (r in refusals) {
                val before = port.joins.size
                core.refused(port.lastAttempt, r)
                assertEquals(VoiceStatus.Reconnecting, core.status.value)
                words += core.waitWords.value
                val start = port.clock
                while (port.joins.size == before) {
                    port.advance(100)
                    if (port.joins.size == before) assertEquals("for as long as the wait lasts", words.last(), core.waitWords.value)
                }
                at += port.clock - start
                assertNull("gone when the join is tried again", core.waitWords.value)
            }
            core.refused(port.lastAttempt, voiceRefusalOf(429, if (said) busy else null, 0, "Matty"))
            words += core.waitWords.value
            port.advance(10_000)
            core.answered(port.lastAttempt, joined)
            core.link(port.lastAttempt, VoiceLink.Connected)
            assertEquals(VoiceStatus.Connected, core.status.value)
            assertNull("gone once voice connects", core.waitWords.value)
            core.link(port.lastAttempt, VoiceLink.Failed)
            port.advance(1_000)
            core.refused(port.lastAttempt, voiceRefusalOf(429, if (said) busy else null, 0, "Matty"))
            words += core.waitWords.value
            core.leave()
            assertNull("gone on leaving", core.waitWords.value)
            return at to words
        }
        val (withWords, shown) = run(said = true)
        val (without, none) = run(said = false)
        assertEquals("the same retries at the same times", without, withWords)
        assertEquals(listOf(81_000L, 2_000L, 5_000L), withWords)
        assertEquals(listOf(busy, "Talk is off.", null, busy, busy), shown)
        assertEquals("a refusal with no sentence of the server's shows nothing", listOf<String?>(null, null, null, null, null), none)
    }

    @Test
    fun withoutANetworkTheTryWaitsForOne() {
        val (core, port) = connected()
        port.online = false
        core.link(port.lastAttempt, VoiceLink.Failed)
        port.advance(60_000)
        assertEquals(1, port.joins.size)
        port.online = true
        core.network(true)
        assertEquals(2, port.joins.size)
        assertEquals(VoiceStatus.Reconnecting, core.status.value)
    }

    /**
     * The phone has no network at all: a connected or connecting voice is lost at once, with
     * nobody shown talking, and joins again when a network comes. A loss while another network
     * has taken over already is not a loss.
     */
    @Test
    fun withNoNetworkAtAllTheConnectionIsLostAtOnceAndJoinsWhenOneComes() {
        val (core, port) = connected()
        core.message(port.lastAttempt, VoiceMessage.Talking(setOf(12L, 15L)))
        core.network(false)
        assertEquals("another network has taken over: nothing", VoiceStatus.Connected, core.status.value)
        assertEquals(setOf(12L, 15L), core.talking.value)
        assertEquals(1, port.joins.size)

        port.online = false
        core.network(false)
        assertEquals("at once", VoiceStatus.Reconnecting, core.status.value)
        assertTrue("nobody is shown talking", core.talking.value.isEmpty())
        assertEquals("disconnect", port.calls.last())
        port.advance(60_000)
        assertEquals("no join without a network", 1, port.joins.size)
        port.online = true
        core.network(true, 101)
        assertEquals("the join goes when a network comes", 2, port.joins.size)
        core.answered(port.lastAttempt, joined)
        core.link(port.lastAttempt, VoiceLink.Connected)
        assertEquals(VoiceStatus.Connected, core.status.value)

        // Connecting counts as well.
        val port2 = FakePort()
        val joining = VoiceCore(port2)
        port2.core = joining
        joining.micChecked(true)
        joining.join(1, false, "u-dave")
        port2.online = false
        joining.network(false)
        assertEquals(VoiceStatus.Reconnecting, joining.status.value)
        port2.advance(60_000)
        assertEquals(1, port2.joins.size)
        port2.online = true
        joining.network(true, 101)
        assertEquals(2, port2.joins.size)

        // Reconnecting already, or not in voice: as before.
        val (again, port3) = connected()
        again.link(port3.lastAttempt, VoiceLink.Failed)
        port3.online = false
        again.network(false)
        port3.online = true
        port3.advance(1_000)
        assertEquals("the try goes on its schedule", 2, port3.joins.size)
        val idle = VoiceCore(FakePort().apply { online = false })
        idle.network(false)
        assertEquals(VoiceStatus.Idle, idle.status.value)
    }

    /**
     * A join again that is on its way when the network goes is lost at once too, and joins when
     * a network comes; one that waits for its turn, or for a network, is left to its wait.
     */
    @Test
    fun aJoinAgainOnItsWayIsLostAtOnceWhenTheNetworkGoes() {
        val (core, port) = connected()
        port.online = false
        core.network(false)
        port.advance(60_000)
        port.online = true
        core.network(true, 101)
        assertEquals("the join again is on its way", 2, port.joins.size)
        assertEquals(VoiceStatus.Reconnecting, core.status.value)

        port.online = false
        core.network(false)
        val calls = port.calls.size
        assertEquals("lost at once: the attempt is given up", "disconnect", port.calls.last())
        port.advance(60_000)
        assertEquals("no join without a network", 2, port.joins.size)
        // Waiting for a network now: another loss changes nothing.
        core.network(false)
        assertEquals(VoiceStatus.Reconnecting, core.status.value)
        port.online = true
        core.network(true, 102)
        assertEquals("the join goes when a network comes", 3, port.joins.size)
        core.answered(port.lastAttempt, joined)
        core.link(port.lastAttempt, VoiceLink.Connected)
        assertEquals(VoiceStatus.Connected, core.status.value)
        assertTrue(port.calls.size > calls)
    }

    /**
     * While the link is disconnected and [VoiceCore.DROP_MS] runs, the screen is told the link is
     * down and nobody is shown talking, this phone's own session included; the status and every
     * timer are as they are without it.
     */
    @Test
    fun aLinkThatIsDownShowsNobodyTalkingUntilItIsBackOrLost() {
        val (core, port) = connected()
        val a = port.lastAttempt
        core.message(a, VoiceMessage.Talking(setOf(12L, 15L)))
        assertFalse(core.linkDown.value)

        core.link(a, VoiceLink.Disconnected)
        assertTrue(core.linkDown.value)
        assertEquals("the status is as it was", VoiceStatus.Connected, core.status.value)
        assertTrue("nobody is shown talking, the person included", core.talking.value.isEmpty())
        port.advance(4_000)
        assertTrue(core.linkDown.value)
        assertTrue(core.talking.value.isEmpty())

        core.link(a, VoiceLink.Connected)
        assertFalse("back: connected again", core.linkDown.value)
        assertEquals(VoiceStatus.Connected, core.status.value)
        assertEquals("who the server last named is shown again: it names them only when that changes", setOf(12L, 15L), core.talking.value)
        core.message(a, VoiceMessage.Talking(setOf(15L)))
        assertEquals(setOf(15L), core.talking.value)
        port.advance(10_000)
        assertEquals(1, port.joins.size)

        core.link(a, VoiceLink.Disconnected)
        assertTrue(core.linkDown.value)
        // A word heard while the link is down shows once it is back.
        core.message(a, VoiceMessage.Talking(setOf(12L)))
        assertTrue(core.talking.value.isEmpty())
        port.advance(VoiceCore.DROP_MS)
        assertEquals(VoiceStatus.Reconnecting, core.status.value)
        assertFalse("lost: the status says it now", core.linkDown.value)
        assertTrue(core.talking.value.isEmpty())
        assertEquals(1, port.joins.size)
        port.advance(1_000)
        assertEquals("the retry is where it always was", 2, port.joins.size)
        assertFalse(core.linkDown.value)

        // Gone on leaving, and on a renew.
        val (other, port2) = connected()
        other.link(port2.lastAttempt, VoiceLink.Disconnected)
        other.renew()
        assertFalse(other.linkDown.value)
        other.answered(port2.lastAttempt, joined)
        other.link(port2.lastAttempt, VoiceLink.Connected)
        other.link(port2.lastAttempt, VoiceLink.Disconnected)
        other.leave()
        assertFalse(other.linkDown.value)
    }

    /**
     * The default network becomes another one while voice is connected: the core joins again at
     * once. The first network after the watch is registered, and the same one again, are no change;
     * while joining, trying again or waiting for a network, the rules are as they are.
     */
    @Test
    fun anotherDefaultNetworkWhileConnectedJoinsAgainAtOnce() {
        val (core, port) = connected()
        core.network(true, 100)
        assertEquals("the first network is no change", 1, port.joins.size)
        core.network(true, 100)
        core.network(true)
        assertEquals("the same network again is no change", 1, port.joins.size)
        assertEquals(VoiceStatus.Connected, core.status.value)

        core.message(port.lastAttempt, VoiceMessage.Talking(setOf(12L, 15L)))
        core.network(true, 101)
        assertEquals("joined again at once", 2, port.joins.size)
        assertEquals("disconnect", port.calls[port.calls.size - 2])
        assertEquals(VoiceStatus.Reconnecting, core.status.value)
        assertTrue(core.talking.value.isEmpty())
        core.answered(port.lastAttempt, joined)
        core.link(port.lastAttempt, VoiceLink.Connected)
        assertEquals(VoiceStatus.Connected, core.status.value)
        core.network(true, 101)
        assertEquals(2, port.joins.size)
        core.network(true, 100)
        assertEquals("and back again", 3, port.joins.size)

        // While joining, a new network only counts as the one in use.
        val port2 = FakePort()
        val joining = VoiceCore(port2)
        port2.core = joining
        joining.micChecked(true)
        joining.join(1, false, "u-dave")
        joining.network(true, 100)
        joining.network(true, 101)
        assertEquals(1, port2.joins.size)
        assertEquals(VoiceStatus.Connecting, joining.status.value)
        joining.answered(port2.lastAttempt, joined)
        joining.link(port2.lastAttempt, VoiceLink.Connected)
        joining.network(true, 101)
        assertEquals("the one it joined on is no change", 1, port2.joins.size)

        // While trying again, the try keeps its time.
        joining.link(port2.lastAttempt, VoiceLink.Failed)
        joining.network(true, 102)
        assertEquals(1, port2.joins.size)
        port2.advance(1_000)
        assertEquals(2, port2.joins.size)

        // A new session's first network is no change, whatever the last one was.
        joining.leave()
        joining.join(1, false, "u-dave")
        joining.answered(port2.lastAttempt, joined)
        joining.link(port2.lastAttempt, VoiceLink.Connected)
        joining.network(true, 200)
        assertEquals(3, port2.joins.size)
        assertEquals(VoiceStatus.Connected, joining.status.value)
    }

    @Test
    fun leavingTellsTheServerAndIgnoresWhatComesAfter() {
        val (core, port) = connected()
        val a = port.lastAttempt
        core.leave()
        assertEquals(VoiceStatus.Idle, core.status.value)
        assertEquals("end+leave", port.calls.last())
        assertNull(core.channel.value)
        // Late answers of the session that was left change nothing.
        core.link(a, VoiceLink.Failed)
        core.message(a, VoiceMessage.Talking(setOf(3L)))
        port.advance(60_000)
        assertEquals(VoiceStatus.Idle, core.status.value)
        assertTrue(core.talking.value.isEmpty())
        assertEquals(1, port.joins.size)
    }

    @Test
    fun movingWhileConnectedAndWhileJoining() {
        val (core, port) = connected()
        core.move(2)
        assertEquals(2, core.moving.value)
        assertEquals(port.lastAttempt to 2, port.moves.single())
        core.moved(port.lastAttempt, 2)
        assertNull(core.moving.value)
        assertEquals(2, core.channel.value)

        // A refused move says why, and the person stays where they were.
        core.move(4)
        core.moveRefused(port.lastAttempt, "That channel is full.")
        assertEquals(2, core.channel.value)
        assertEquals(listOf("That channel is full."), port.notices)

        // Joining another channel while in one is a move.
        core.join(3, false, "u-dave")
        assertEquals(3, port.moves.last().second)
        assertEquals(1, port.joins.size)
    }

    @Test
    fun aMoveAskedWhileTheJoinIsAnsweredFollowsItOnceConnected() {
        val port = FakePort()
        val core = VoiceCore(port)
        core.micChecked(true)
        core.join(1, false, "u-dave")
        core.move(2)
        assertTrue(port.moves.isEmpty())
        core.answered(port.lastAttempt, joined)
        core.link(port.lastAttempt, VoiceLink.Connected)
        assertEquals(2, port.moves.single().second)
    }

    @Test
    fun aStreamOnTheSpeakerMutesTheMicrophone() {
        val (core, port) = connected()
        core.streamStarted(privateOutput = true)
        assertFalse(core.muted.value)
        core.streamStarted(privateOutput = false)
        assertTrue(core.muted.value)
        assertEquals(false, port.recording)
    }

    @Test
    fun aMicrophoneRefusedOnceIsAskedForAgainAndSaidApart() {
        val port = FakePort()
        val core = VoiceCore(port)
        core.join(1, muted = false, userId = "u-dave")
        core.answered(port.lastAttempt, joined)
        core.link(port.lastAttempt, VoiceLink.Connected)
        core.micAnswered(false, forGood = false)
        assertEquals("refused once: Android asks again", MicPermission.Declined, core.mic.value)
        assertTrue(core.muted.value)
        core.setMuted(false)
        assertTrue(core.micWanted.value)
        // Allowed in Android's settings meanwhile, and read again when Matty is back or a sheet opens.
        core.micChecked(true)
        assertEquals(MicPermission.Granted, core.mic.value)
        // Read again with no call on, too.
        core.leave()
        core.micChecked(false)
        assertEquals(MicPermission.NotAsked, core.mic.value)
        core.micChecked(true)
        assertEquals(MicPermission.Granted, core.mic.value)
    }

    @Test
    fun aStateTheServerDidNotTakeIsSentAgainOnTheRetrySteps() {
        val (core, port) = connected()
        port.failStates = 1
        core.setMuted(true)
        assertEquals(listOf(true to false), port.states)
        port.advance(999)
        assertEquals("not before the first step", 1, port.states.size)
        port.advance(1)
        assertEquals("sent again", listOf(true to false, true to false), port.states)
        port.advance(60_000)
        assertEquals("taken: nothing more", 2, port.states.size)

        // Failing again and again: 1, 2 and 5 s, and never sooner than the server asked.
        port.failStates = 3
        port.stateRefusal = voiceRefusalOf(429, "Too many tries.", 3_000, "Matty")
        core.setMuted(false)
        val at = mutableListOf<Long>()
        val start = port.clock
        while (port.states.size < 6) { port.advance(100); if (port.states.size > 3 + at.size) at += port.clock - start }
        assertEquals(listOf(3_000L, 6_000L, 11_000L), at)
        assertEquals("the newest state each time", false to false, port.states.last())
    }

    @Test
    fun aStateChangedWhileItWaitsGoesAtOnceAndOnlyTheNewestIsSentAgain() {
        val (core, port) = connected()
        port.failStates = 1
        core.setMuted(true)
        core.setMuted(false)
        assertEquals(listOf(true to false, false to false), port.states)
        port.advance(60_000)
        assertEquals("the change went, and was taken: the wait is over", 2, port.states.size)
    }

    @Test
    fun aStateRefusedForAnotherReasonIsLeftToTheRejoin() {
        val (core, port) = connected()
        port.failStates = 1
        port.stateRefusal = voiceRefusalOf(403, "Unlock Matty to join voice.", 0, "Matty")
        core.setMuted(true)
        port.advance(60_000)
        assertEquals(1, port.states.size)

        // A wait that outlives its connection sends nothing: the rejoin carries the state.
        port.failStates = 1
        port.stateRefusal = voiceRefusalOf(503, null, 0, "Matty")
        core.setMuted(false)
        core.link(port.lastAttempt, VoiceLink.Failed)
        port.advance(1_000)
        assertEquals(VoiceJoin(1, muted = false, deafened = false, record = true), port.joins.last().second)
        port.advance(60_000)
        assertEquals(2, port.states.size)
    }

    @Test
    fun theChannelOfAFailedSessionIsKeptUntilAJoinOrALeave() {
        val (core, port) = connected()
        core.message(port.lastAttempt, VoiceMessage.Self(channel = 2, muted = false, suppressed = false))
        core.message(port.lastAttempt, VoiceMessage.End("kicked", ""))
        assertNull(core.channel.value)
        assertEquals(2, core.lastChannel.value)
        core.leave()
        assertNull(core.lastChannel.value)

        val (again, port2) = connected()
        again.refused(port2.lastAttempt, voiceRefusalOf(404, null, 0, "Matty", code = "not_found"))
        assertEquals(1, again.lastChannel.value)
        again.join(4, false, "u-dave")
        assertNull("a join clears it", again.lastChannel.value)
        assertEquals(4, again.channel.value)
        assertNull("connected: no last channel", connected().first.lastChannel.value)
    }

    @Test
    fun theServerRestartingIsReconnectingNotAFailure() {
        val (core, port) = connected()
        val end = voiceMessageOf("""{"end":"mumble","reason":"Voice is restarting on this server."}""")
        assertEquals(VoiceMessage.End("mumble", "Voice is restarting on this server."), end)
        core.message(port.lastAttempt, end!!)
        assertEquals(VoiceStatus.Reconnecting, core.status.value)
        assertNull(core.lastChannel.value)
        assertEquals(1, core.channel.value)
        port.advance(1_000)
        assertEquals(2, port.joins.size)
        assertEquals(1, port.joins.last().second.channel)

        // The try lands while the plugin is not running: Mattermost's own 404, which is waited out.
        core.refused(port.lastAttempt, voiceRefusalOf(404, null, 0, "Matty", code = ""))
        assertEquals(VoiceStatus.Reconnecting, core.status.value)
        assertNull(core.lastChannel.value)
        port.advance(2_000)
        assertEquals(3, port.joins.size)
        core.answered(port.lastAttempt, joined)
        core.link(port.lastAttempt, VoiceLink.Connected)
        assertEquals(VoiceStatus.Connected, core.status.value)
    }

    @Test
    fun theMicrophoneAllowedDuringTheCallRenewsTheConnection() {
        val port = FakePort()
        val core = VoiceCore(port)
        core.join(1, muted = false, userId = "u-dave")
        assertFalse("made without the permission: nothing records", port.joins.single().second.record)
        core.answered(port.lastAttempt, joined)
        core.link(port.lastAttempt, VoiceLink.Connected)
        core.move(2)
        core.micAnswered(true, forGood = false)

        core.renew()
        assertEquals("the old connection goes", "disconnect", port.calls[port.calls.size - 2])
        assertEquals(2, port.joins.size)
        assertEquals("the new one records from the start", VoiceJoin(2, muted = false, deafened = false, record = true), port.joins.last().second)
        assertEquals(VoiceStatus.Reconnecting, core.status.value)
        assertNull("the move goes with the new join", core.moving.value)
        // The old connection's late word changes nothing.
        core.link(port.joins.first().first, VoiceLink.Failed)
        assertEquals(VoiceStatus.Reconnecting, core.status.value)
        core.answered(port.lastAttempt, joined.copy(channel = 2))
        core.link(port.lastAttempt, VoiceLink.Connected)
        assertEquals(VoiceStatus.Connected, core.status.value)
        assertEquals(2, core.channel.value)
        port.advance(60_000)
        assertEquals("nothing more is tried", 2, port.joins.size)

        // While joining it is made again at once too; while trying again, the next try is new anyway.
        val port2 = FakePort()
        val joining = VoiceCore(port2)
        joining.join(1, muted = false, userId = "u-dave")
        joining.micAnswered(true, forGood = false)
        joining.renew()
        assertEquals(VoiceStatus.Connecting, joining.status.value)
        assertEquals(VoiceJoin(1, muted = false, deafened = false, record = true), port2.joins.last().second)
        joining.link(port2.lastAttempt, VoiceLink.Failed)
        joining.renew()
        assertEquals(2, port2.joins.size)
        port2.advance(1_000)
        assertEquals(3, port2.joins.size)
        assertNull("idle: nothing", VoiceCore(FakePort()).apply { renew() }.channel.value)
    }

    @Test
    fun aRefusalForGoodIsKeptOverARestartUntilTheMicrophoneIsFoundGranted() {
        val port = FakePort()
        val core = VoiceCore(port)
        core.micAnswered(false, forGood = false)
        assertTrue("refused once is not kept", port.refusals.isEmpty())
        core.micAnswered(false, forGood = true)
        assertEquals(listOf(true), port.refusals)

        // Matty starts again: refused for good from the start, and read again it stays so.
        val after = VoiceCore(FakePort(), refusedForGood = true)
        assertEquals(MicPermission.Refused, after.mic.value)
        after.micChecked(false)
        assertEquals(MicPermission.Refused, after.mic.value)

        // Allowed in Settings: found granted, and forgotten.
        val port3 = FakePort()
        val allowed = VoiceCore(port3, refusedForGood = true)
        allowed.micChecked(true)
        assertEquals(MicPermission.Granted, allowed.mic.value)
        assertEquals(listOf(false), port3.refusals)
        allowed.micChecked(true)
        assertEquals("forgotten once", listOf(false), port3.refusals)
        val port4 = FakePort()
        VoiceCore(port4, refusedForGood = true).micAnswered(true, forGood = false)
        assertEquals(listOf(false), port4.refusals)
    }

    @Test
    fun aStateSendStillOutFromAnEarlierConnectionNeverSwallowsTheNewest() {
        val posts = mutableListOf<Pair<Pair<Boolean, Boolean>, CompletableDeferred<VoiceRefusal?>>>()
        val heard = mutableListOf<Pair<Int, VoiceRefusal?>>()
        val sender = VoiceStateSender(
            CoroutineScope(Dispatchers.Unconfined),
            post = { m, d -> CompletableDeferred<VoiceRefusal?>().also { posts += (m to d) to it }.await() },
            done = { on, r -> heard += on to r },
        )
        // Unmuted on the first connection; the request hangs as the connection goes.
        sender.send(1, muted = false, deafened = false)
        assertEquals(1, posts.size)
        // The same state is sent on the second connection while that request is still out.
        sender.send(2, muted = false, deafened = false)
        assertEquals("one request at a time", 1, posts.size)
        posts[0].second.complete(voiceRefusalOf(null, null, 0, "Matty"))
        assertTrue("the old request's answer is not heard", heard.isEmpty())
        assertEquals("sent again, on the new connection", 2, posts.size)
        assertEquals(false to false, posts[1].first)
        posts[1].second.complete(null)
        assertEquals(listOf(2 to null), heard)

        // A newer state waiting goes next, and only its answer is heard.
        sender.send(2, muted = true, deafened = false)
        sender.send(2, muted = false, deafened = false)
        posts[2].second.complete(null)
        assertEquals(false to false, posts[3].first)
        posts[3].second.complete(null)
        assertEquals(listOf(2 to null, 2 to null), heard)

        // Stopped: what was out says nothing.
        sender.send(3, muted = true, deafened = false)
        sender.stop()
        assertEquals(5, posts.size)
        assertEquals(2, heard.size)
    }

    @Test
    fun shownMutedIsWhatIsWantedOrTheServersWordOnceItHasHadTwoSeconds() {
        assertTrue(voiceShown(wanted = true, serverSays = false, sinceChangeMs = 0))
        assertFalse("a tap shows at once", voiceShown(wanted = false, serverSays = true, sinceChangeMs = 0))
        assertFalse(voiceShown(wanted = false, serverSays = true, sinceChangeMs = VoiceCore.SERVER_WORD_MS - 1))
        assertTrue(voiceShown(wanted = false, serverSays = true, sinceChangeMs = VoiceCore.SERVER_WORD_MS))
        assertFalse(voiceShown(wanted = false, serverSays = false, sinceChangeMs = 60_000))
    }

    @Test
    fun aMuteTheVoiceServerHoldsIsShownAfterTwoSecondsAndATapAsksAgain() {
        val (core, port) = connected()
        val a = port.lastAttempt
        // Joined unmuted; the voice server holds the session self muted (it dropped the unmute).
        core.message(a, VoiceMessage.Talking(setOf(12L, 15L)))
        core.message(a, VoiceMessage.Self(channel = 1, muted = false, suppressed = false, selfMuted = true))
        assertFalse("the join was just answered", core.shownMuted.value)
        assertFalse(core.muted.value)
        assertEquals(setOf(12L, 15L), core.talking.value)
        port.advance(VoiceCore.SERVER_WORD_MS - 1)
        assertFalse(core.shownMuted.value)
        port.advance(1)
        assertTrue("the server's word, after two seconds", core.shownMuted.value)
        assertFalse("what the person wants is unchanged", core.muted.value)
        assertFalse(core.shownDeafened.value)
        assertEquals("no ring for this phone while shown muted", setOf(15L), core.talking.value)
        assertEquals("the microphone still records: it is the server that holds it", true, port.recording)

        // The tap acts on what is shown: unmute, sent again although it is what was asked already.
        val sent = port.states.size
        core.setMuted(!core.shownMuted.value)
        assertEquals(sent + 1, port.states.size)
        assertEquals(false to false, port.states.last())
        assertFalse("shown at once", core.shownMuted.value)
        assertEquals(setOf(12L, 15L), core.talking.value)

        // The server's word doesn't follow: muted again two seconds after the tap.
        port.advance(VoiceCore.SERVER_WORD_MS)
        assertTrue(core.shownMuted.value)
        // It follows: shown unmuted, and it stays so.
        core.setMuted(false)
        core.message(a, VoiceMessage.Self(channel = 1, muted = false, suppressed = false, selfMuted = false))
        port.advance(60_000)
        assertFalse(core.shownMuted.value)
        assertEquals(setOf(12L, 15L), core.talking.value)
    }

    @Test
    fun aTapShowsAtOnceAndTheServersOlderWordWaitsItsTwoSeconds() {
        val (core, port) = connected(muted = true)
        val a = port.lastAttempt
        core.message(a, VoiceMessage.Self(channel = 1, muted = false, suppressed = false, selfMuted = true))
        port.advance(5_000)
        assertTrue(core.shownMuted.value)
        core.setMuted(false)
        assertFalse("the tap shows at once, before the server's word follows", core.shownMuted.value)
        assertEquals(false to false, port.states.last())
        port.advance(1_000)
        core.message(a, VoiceMessage.Self(channel = 1, muted = false, suppressed = false, selfMuted = false))
        port.advance(5_000)
        assertFalse(core.shownMuted.value)

        // Muting needs no server word: shown at once, and the server saying unmuted changes nothing.
        core.setMuted(true)
        assertTrue(core.shownMuted.value)
        port.advance(5_000)
        assertTrue(core.shownMuted.value)
    }

    @Test
    fun aDeafenTheVoiceServerHoldsIsShownLikewise() {
        val (core, port) = connected()
        val a = port.lastAttempt
        core.message(a, VoiceMessage.Self(channel = 1, muted = false, suppressed = false, selfMuted = true, selfDeafened = true))
        port.advance(VoiceCore.SERVER_WORD_MS)
        assertTrue(core.shownDeafened.value)
        assertTrue("deafened is muted too", core.shownMuted.value)
        assertFalse(core.deafened.value)
        val sent = port.states.size
        // Shown deafened: the headphones' tap undeafens, and is sent although nothing that holds changes.
        core.setDeafened(!core.shownDeafened.value)
        assertEquals(sent + 1, port.states.size)
        assertEquals(false to false, port.states.last())
        assertFalse(core.shownDeafened.value)
        assertFalse(core.shownMuted.value)
    }

    @Test
    fun aPluginThatDoesNotSayTheSelfStateShowsWhatIsWanted() {
        val (core, port) = connected()
        val a = port.lastAttempt
        val m = voiceMessageOf("""{"self":{"channel":1,"muted":false,"suppressed":false}}""")!!
        core.message(a, m)
        core.message(a, VoiceMessage.Talking(setOf(12L)))
        port.advance(60_000)
        assertFalse(core.shownMuted.value)
        assertFalse(core.shownDeafened.value)
        assertEquals(setOf(12L), core.talking.value)
    }

    @Test
    fun theServersWordIsOnlyShownForTheConnectionThatWasAnswered() {
        val (core, port) = connected()
        core.message(port.lastAttempt, VoiceMessage.Self(channel = 1, muted = false, suppressed = false, selfMuted = true))
        port.advance(VoiceCore.SERVER_WORD_MS)
        assertTrue(core.shownMuted.value)
        // The connection goes: until the next join is answered, what is wanted is shown.
        val joins = port.joins.size
        core.link(port.lastAttempt, VoiceLink.Failed)
        assertFalse(core.shownMuted.value)
        port.advance(1_000)
        assertEquals("joined again", joins + 1, port.joins.size)
        assertFalse(core.shownMuted.value)
        val old = port.joins[port.joins.size - 2].first
        core.answered(port.lastAttempt, joined)
        port.advance(VoiceCore.SERVER_WORD_MS * 5)
        assertFalse("the closed connection's word is not shown on the new one", core.shownMuted.value)
        core.message(old, VoiceMessage.Self(channel = 1, muted = false, suppressed = false, selfMuted = true))
        assertFalse("nor a word heard late on it", core.shownMuted.value)
        // The new connection's own word: the two seconds since the answer have passed.
        core.message(port.lastAttempt, VoiceMessage.Self(channel = 1, muted = false, suppressed = false, selfMuted = true))
        assertTrue(core.shownMuted.value)
        core.leave()
        assertFalse(core.shownMuted.value)
    }

    @Test
    fun theNewConnectionsWordWaitsTwoSecondsFromItsAnswer() {
        val (core, port) = connected()
        core.message(port.lastAttempt, VoiceMessage.Self(channel = 1, muted = false, suppressed = false, selfMuted = true))
        port.advance(VoiceCore.SERVER_WORD_MS)
        assertTrue(core.shownMuted.value)
        core.renew()
        assertFalse(core.shownMuted.value)
        core.answered(port.lastAttempt, joined)
        core.message(port.lastAttempt, VoiceMessage.Self(channel = 1, muted = false, suppressed = false, selfMuted = true))
        port.advance(VoiceCore.SERVER_WORD_MS - 1)
        assertFalse(core.shownMuted.value)
        port.advance(1)
        assertTrue(core.shownMuted.value)
    }

    @Test
    fun aTapThatChangesNothingShownIsNotSentAgain() {
        // A plugin that does not say the self state (4.3.0): what is shown is what holds, and a
        // tap that changes nothing that holds asks the server for nothing.
        val (core, port) = connected(mic = false)
        assertTrue("muted for want of the microphone", core.shownMuted.value)
        val sent = port.states.size
        core.setMuted(!core.shownMuted.value)
        assertTrue("still muted until it is allowed", core.shownMuted.value)
        assertEquals("nothing sent", sent, port.states.size)
        core.streamStarted(privateOutput = false)
        assertEquals("a stream starting while muted sends nothing", sent, port.states.size)

        val (held, heldPort) = connected()
        held.held(true)
        assertEquals("a phone call takes the voice: sent once", true to true, heldPort.states.last())
        val once = heldPort.states.size
        held.setMuted(!held.shownMuted.value)
        held.setDeafened(!held.shownDeafened.value)
        assertTrue(held.shownMuted.value && held.shownDeafened.value)
        assertEquals("taps during the call send nothing", once, heldPort.states.size)
    }

    @Test
    fun aStateRequestOfAnEarlierConnectionIsAnsweredBeforeTheNextJoinGoes() {
        val posts = mutableListOf<Pair<Pair<Boolean, Boolean>, CompletableDeferred<VoiceRefusal?>>>()
        val heard = mutableListOf<Pair<Int, VoiceRefusal?>>()
        val order = mutableListOf<String>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sender = VoiceStateSender(
            scope,
            post = { m, d -> CompletableDeferred<VoiceRefusal?>().also { posts += (m to d) to it }.await().also { order += "state $m" } },
            done = { on, r -> heard += on to r },
        )
        // Muted on the first connection, and a newer unmute waiting behind it; the connection goes.
        sender.send(1, muted = true, deafened = false)
        sender.send(1, muted = false, deafened = false)
        // The renewed connection's join waits for the request out.
        scope.launch { sender.settle(); order += "join" }
        assertEquals("not before the request out is answered", emptyList<String>(), order)
        posts[0].second.complete(null)
        assertEquals(listOf("state true", "join"), order)
        assertEquals("what waited behind it is not sent: the join carries the newest", 1, posts.size)
        assertTrue("nothing of the earlier connection is heard", heard.isEmpty())

        // Nothing out: the join goes at once.
        scope.launch { sender.settle(); order += "join again" }
        assertEquals("join again", order.last())

        // A request that never comes back holds the join no longer than its limit.
        sender.send(2, muted = true, deafened = false)
        runBlocking { sender.settle(limitMs = 1) }
        assertEquals(2, posts.size)
        sender.stop()
    }

    @Test
    fun aStateRequestStillOutAtTheLimitIsGivenUpAndTheNewConnectionsGoesAtOnce() {
        assertEquals("a state request answers in moments: two seconds is plenty", 2_000L, VoiceStateSender.SETTLE_MS)
        val posts = mutableListOf<Pair<Pair<Boolean, Boolean>, CompletableDeferred<VoiceRefusal?>>>()
        val heard = mutableListOf<Pair<Int, VoiceRefusal?>>()
        val sender = VoiceStateSender(
            CoroutineScope(Dispatchers.Unconfined),
            post = { m, d -> CompletableDeferred<VoiceRefusal?>().also { posts += (m to d) to it }.await() },
            done = { on, r -> heard += on to r },
        )
        sender.send(1, muted = true, deafened = false)
        runBlocking { sender.settle(limitMs = 1) }
        // The new connection's state does not queue behind the request given up.
        sender.send(2, muted = false, deafened = false)
        assertEquals(2, posts.size)
        assertEquals(false to false, posts[1].first)
        posts[0].second.complete(null)
        posts[1].second.complete(null)
        assertEquals("only the new connection's is heard", listOf(2 to null), heard)
    }

    @Test
    fun theSameStateSentAgainWhileARequestIsOutGoesAgainAfterIt() {
        val posts = mutableListOf<Pair<Pair<Boolean, Boolean>, CompletableDeferred<VoiceRefusal?>>>()
        val heard = mutableListOf<Pair<Int, VoiceRefusal?>>()
        val sender = VoiceStateSender(
            CoroutineScope(Dispatchers.Unconfined),
            post = { m, d -> CompletableDeferred<VoiceRefusal?>().also { posts += (m to d) to it }.await() },
            done = { on, r -> heard += on to r },
        )
        sender.send(1, muted = false, deafened = false)
        sender.send(1, muted = false, deafened = false)
        posts[0].second.complete(null)
        assertEquals("a tap asks again", 2, posts.size)
        assertTrue(heard.isEmpty())
        posts[1].second.complete(null)
        assertEquals(listOf(1 to null), heard)
    }

    @Test
    fun aRefusalClassifiedForRetrying() {
        assertEquals(VoiceRetry.Wait, voiceRefusalOf(null, null, 0, "Matty").retry)
        assertEquals("Matty is not answering. Try again in a moment.", voiceRefusalOf(null, null, 0, "Matty").sentence)
        assertEquals(VoiceRefusal(VoiceRetry.Wait, "Talk is off.", 5_000, serverSaid = true), voiceRefusalOf(503, "Talk is off.", 5_000, "Matty"))
        assertEquals(VoiceRefusal(VoiceRetry.Wait, "Too many tries.", 0, serverSaid = true), voiceRefusalOf(429, "Too many tries.", 0, "Matty"))
        assertFalse("the app's own sentence is not the server's", voiceRefusalOf(503, null, 0, "Matty").serverSaid)
        assertFalse(voiceRefusalOf(502, "Bad gateway", 0, "Matty").serverSaid)
        assertEquals(VoiceRetry.Wait, voiceRefusalOf(502, null, 0, "Matty").retry)
        for (status in listOf(400, 401, 403, 409)) assertEquals(VoiceRetry.Stop, voiceRefusalOf(status, "No.", 0, "Matty").retry)
        assertEquals(VoiceRetry.Stop, voiceRefusalOf(404, "No.", 0, "Matty", code = "not_found").retry)
        assertEquals("Couldn't join voice (404).", voiceRefusalOf(404, null, 0, "Matty", code = "not_found").sentence)
    }
}
