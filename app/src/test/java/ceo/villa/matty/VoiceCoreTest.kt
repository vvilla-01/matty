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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    fun aRefusalClassifiedForRetrying() {
        assertEquals(VoiceRetry.Wait, voiceRefusalOf(null, null, 0, "Matty").retry)
        assertEquals("Matty is not answering. Try again in a moment.", voiceRefusalOf(null, null, 0, "Matty").sentence)
        assertEquals(VoiceRefusal(VoiceRetry.Wait, "Talk is off.", 5_000), voiceRefusalOf(503, "Talk is off.", 5_000, "Matty"))
        assertEquals(VoiceRetry.Wait, voiceRefusalOf(502, null, 0, "Matty").retry)
        for (status in listOf(400, 401, 403, 409)) assertEquals(VoiceRetry.Stop, voiceRefusalOf(status, "No.", 0, "Matty").retry)
        assertEquals(VoiceRetry.Stop, voiceRefusalOf(404, "No.", 0, "Matty", code = "not_found").retry)
        assertEquals("Couldn't join voice (404).", voiceRefusalOf(404, null, 0, "Matty", code = "not_found").sentence)
    }
}
