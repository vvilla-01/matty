package ceo.villa.matty

import ceo.villa.matty.data.api.MattermostJson
import ceo.villa.matty.plugins.PersonFacts
import ceo.villa.matty.plugins.VoiceState
import ceo.villa.matty.plugins.villanet.MicPermission
import ceo.villa.matty.plugins.villanet.VnAccess
import ceo.villa.matty.plugins.villanet.VnSnapshot
import ceo.villa.matty.plugins.villanet.VnVoice
import ceo.villa.matty.plugins.villanet.VnVoiceChannel
import ceo.villa.matty.plugins.villanet.VnVoiceWord
import ceo.villa.matty.plugins.villanet.VoiceBar
import ceo.villa.matty.plugins.villanet.VoiceJoinAction
import ceo.villa.matty.plugins.villanet.VoiceListRow
import ceo.villa.matty.plugins.villanet.VoiceMember
import ceo.villa.matty.plugins.villanet.VoiceNotice
import ceo.villa.matty.plugins.villanet.VoiceRoom
import ceo.villa.matty.plugins.villanet.VoiceStatus
import ceo.villa.matty.plugins.villanet.inCall
import ceo.villa.matty.plugins.villanet.personsOf
import ceo.villa.matty.plugins.villanet.voiceBarOf
import ceo.villa.matty.plugins.villanet.voiceChannelName
import ceo.villa.matty.plugins.villanet.voiceCountOf
import ceo.villa.matty.plugins.villanet.voiceJoinActionOf
import ceo.villa.matty.plugins.villanet.voiceJoinLabel
import ceo.villa.matty.plugins.villanet.voiceJoinMicWords
import ceo.villa.matty.plugins.villanet.voiceMayJoin
import ceo.villa.matty.plugins.villanet.voiceListRows
import ceo.villa.matty.plugins.villanet.voiceMemberWords
import ceo.villa.matty.plugins.villanet.voiceNoTalkWords
import ceo.villa.matty.plugins.villanet.voiceNoticeOf
import ceo.villa.matty.plugins.villanet.voicePeopleWords
import ceo.villa.matty.plugins.villanet.voiceRoomsOf
import ceo.villa.matty.plugins.villanet.voiceStatusWords
import ceo.villa.matty.plugins.villanet.voiceTilesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the voice screens show: the channel list's rows, the join sheet's button, the bar, the tiles and the notice. */
class VoiceScreensTest {
    private fun member(session: Long, name: String, userId: String = "", muted: Boolean = false, deaf: Boolean = false, phone: Boolean = false, me: Boolean = false) =
        VoiceMember(session, userId, name, muted || deaf, deaf, phone, speaking = false, me = me)

    private val bob = member(15, "Bob")
    private val dave = member(12, "Dave", "u-dave", deaf = true, phone = true)
    private val erin = member(20, "Erin")
    private val alice = member(21, "alice", "u-alice")
    private val rooms = listOf(
        VoiceRoom(0, "Test", 0, 0, emptyList()),
        VoiceRoom(1, "Lobby", 1, 0, listOf(dave, bob)),
        VoiceRoom(2, "Gaming", 1, 0, emptyList()),
        VoiceRoom(4, "Duo", 2, 2, listOf(erin, alice)),
    )

    private val connected = VoiceStatus.Connected

    // ---- the channel list ------------------------------------------------------------------

    @Test
    fun theListIsEveryChannelWithItsPeopleUnderIt() {
        val rows = voiceListRows(rooms, folded = false, mine = null)
        assertEquals(
            listOf("voice:ch:0", "voice:ch:1", "voice:p:12", "voice:p:15", "voice:ch:2", "voice:ch:4", "voice:p:20", "voice:p:21"),
            rows.map { it.key },
        )
        val dave = rows[2] as VoiceListRow.Person
        assertEquals("a person is drawn at their channel's depth", 1, dave.depth)
        assertEquals(1, dave.channel)
        assertTrue(rows.filterIsInstance<VoiceListRow.Channel>().none { it.here })
        assertTrue(voiceListRows(rooms, folded = false, mine = 4).filterIsInstance<VoiceListRow.Channel>().single { it.here }.room.id == 4)
    }

    @Test
    fun foldedTheListKeepsOnlyTheChannelThisPhoneIsIn() {
        assertTrue("folded and not in voice: only the header", voiceListRows(rooms, folded = true, mine = null).isEmpty())
        val rows = voiceListRows(rooms, folded = true, mine = 1)
        assertEquals(listOf("voice:ch:1", "voice:p:12", "voice:p:15"), rows.map { it.key })
        assertTrue((rows[0] as VoiceListRow.Channel).here)
    }

    @Test
    fun theListsKeysNeverRepeat() {
        // A session named twice (a roster caught mid-move) must not crash the list.
        val twice = listOf(VoiceRoom(1, "Lobby", 0, 0, listOf(bob)), VoiceRoom(2, "Gaming", 0, 0, listOf(bob, bob)))
        val keys = voiceListRows(twice, folded = false, mine = null).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(listOf("voice:ch:1", "voice:p:15", "voice:ch:2", "voice:p:15#1", "voice:p:15#2"), keys)
    }

    @Test
    fun aChannelWithALimitSaysHowFullItIs() {
        assertNull(voiceCountOf(rooms[1]))
        assertEquals("2/2", voiceCountOf(rooms[3]))
        assertEquals("Nobody is here yet", voicePeopleWords(rooms[0]))
        assertEquals("2 people here", voicePeopleWords(rooms[1]))
        assertEquals("1 person here", voicePeopleWords(VoiceRoom(3, "AFK", 1, 0, listOf(bob))))
        assertEquals("2 people here · Full", voicePeopleWords(rooms[3]))
    }

    @Test
    fun channelNamesComeFromTheRoster() {
        val v = VnVoice(connected = true, channels = listOf(VnVoiceChannel(0, "Test"), VnVoiceChannel(1, "  ", parent = 0, depth = 1)))
        assertEquals("Test", voiceChannelName(v, 0))
        assertNull("a blank name is no name", voiceChannelName(v, 1))
        assertNull(voiceChannelName(v, 7))
        assertNull(voiceChannelName(v, null))
        assertNull(voiceChannelName(null, 0))
    }

    // ---- the join sheet --------------------------------------------------------------------

    @Test
    fun theSheetsButton() {
        val idle = VoiceStatus.Idle
        assertEquals(VoiceJoinAction.Join, voiceJoinActionOf(true, idle, null, null, 1, full = false))
        assertEquals("without talk, who is there and no button", VoiceJoinAction.None, voiceJoinActionOf(false, idle, null, null, 1, full = false))
        assertEquals(VoiceJoinAction.Full, voiceJoinActionOf(true, idle, null, null, 4, full = true))
        assertEquals("in another channel: a move", VoiceJoinAction.Move, voiceJoinActionOf(true, connected, 2, null, 1, full = false))
        assertEquals("connecting counts as in", VoiceJoinAction.Move, voiceJoinActionOf(true, VoiceStatus.Connecting, 2, null, 1, full = false))
        assertEquals("in it already: the voice screen", VoiceJoinAction.Open, voiceJoinActionOf(true, connected, 1, null, 1, full = false))
        assertEquals("in a full channel is still in it", VoiceJoinAction.Open, voiceJoinActionOf(true, connected, 4, null, 4, full = true))
        assertEquals("in it even if talk has just gone off", VoiceJoinAction.Open, voiceJoinActionOf(false, VoiceStatus.Reconnecting, 1, null, 1, full = false))
        assertEquals(VoiceJoinAction.Moving, voiceJoinActionOf(true, connected, 2, 1, 1, full = false))
        assertEquals("moving away from it: back is a move", VoiceJoinAction.Move, voiceJoinActionOf(true, connected, 1, 2, 1, full = false))
        assertEquals("a session that ended joins afresh", VoiceJoinAction.Join, voiceJoinActionOf(true, VoiceStatus.Failed("You left voice."), null, null, 1, full = false))
    }

    @Test
    fun theSheetsWords() {
        assertEquals("Join Voice", voiceJoinLabel(VoiceJoinAction.Join))
        assertEquals("Move Here", voiceJoinLabel(VoiceJoinAction.Move))
        assertEquals("Open Voice", voiceJoinLabel(VoiceJoinAction.Open))
        assertEquals("Channel Full", voiceJoinLabel(VoiceJoinAction.Full))
        assertNull(voiceJoinLabel(VoiceJoinAction.None))
        val off = VnAccess(version = "4.3.0", voice = VnVoiceWord("off", "Talking from Matty is switched off on this server."))
        assertEquals("Talking from Matty is switched off on this server.", voiceNoTalkWords(off))
        assertEquals("Talking from Matty isn't available on this server.", voiceNoTalkWords(VnAccess(version = "4.3.0", voice = VnVoiceWord("off"))))
        assertEquals("Talking from Matty isn't available on this server.", voiceNoTalkWords(null))
    }

    // ---- the bar -------------------------------------------------------------------------------

    @Test
    fun theBarSaysHowTheCallIsGoing() {
        assertNull(voiceBarOf(VoiceStatus.Idle, "Lobby", "Villa"))
        assertEquals(VoiceBar("Voice Connected", "Lobby · Villa", connected = true, controls = true), voiceBarOf(connected, "Lobby", "Villa"))
        assertEquals(VoiceBar("Connecting…", "Voice · Villa", connected = false, controls = true), voiceBarOf(VoiceStatus.Connecting, null, "Villa"))
        assertEquals(VoiceBar("Reconnecting…", "Lobby", connected = false, controls = true), voiceBarOf(VoiceStatus.Reconnecting, "Lobby", " "))
        assertEquals(
            "ended by the server: why, and only a way to put it away",
            VoiceBar("Voice Disconnected", "You were removed from the voice channel.", connected = false, controls = false),
            voiceBarOf(VoiceStatus.Failed("You were removed from the voice channel."), "Lobby", "Villa"),
        )
    }

    @Test
    fun theStatusWords() {
        assertEquals("Voice Connected", voiceStatusWords(connected))
        assertEquals("Connecting…", voiceStatusWords(VoiceStatus.Connecting))
        assertEquals("Reconnecting…", voiceStatusWords(VoiceStatus.Reconnecting))
        assertEquals("", voiceStatusWords(VoiceStatus.Idle))
        assertTrue(VoiceStatus.Reconnecting.inCall)
        assertFalse(VoiceStatus.Idle.inCall)
        assertFalse(VoiceStatus.Failed("x").inCall)
    }

    // ---- the voice screen --------------------------------------------------------------------

    @Test
    fun theTilesAreTheChannelsPeopleWithThisPhoneFirst() {
        val me = member(30, "Dave", "u-dave", phone = true, me = true)
        val withSeat = listOf(VoiceRoom(1, "Lobby", 1, 0, listOf(bob, me)))
        assertEquals(listOf(30L, 15L), voiceTilesOf(withSeat, 1, null).map { it.session })
        assertTrue("another channel's tiles are nobody's", voiceTilesOf(withSeat, 2, null).isEmpty())
        // Before the join is answered the roster has no seat: the account stands in, and the
        // older phone session of the same account the roster still shows is left out.
        val standIn = member(-1, "Dave", "u-dave", phone = true, me = true)
        assertEquals(listOf(-1L, 15L), voiceTilesOf(rooms, 1, standIn).map { it.session })
        assertEquals("no seat and not in voice: as the roster is", listOf(12L, 15L), voiceTilesOf(rooms, 1, null).map { it.session })
        val twice = listOf(VoiceRoom(1, "Lobby", 1, 0, listOf(bob, bob)))
        assertEquals(listOf(15L), voiceTilesOf(twice, 1, null).map { it.session })
    }

    @Test
    fun theNoticeOverTheControls() {
        assertNull(voiceNoticeOf(held = false, serverMuted = false, suppressed = false, mic = MicPermission.Granted))
        assertNull("not asked yet is said by asking", voiceNoticeOf(false, false, false, MicPermission.NotAsked))
        assertEquals(VoiceNotice("Matty can't use the microphone, so you're muted. Allow it in Settings.", true), voiceNoticeOf(false, false, false, MicPermission.Refused))
        assertNull("refused once: unmuting asks again", voiceNoticeOf(false, false, false, MicPermission.Declined))
        assertEquals(VoiceNotice("You can't speak in this channel.", false), voiceNoticeOf(false, false, true, MicPermission.Refused))
        assertEquals(VoiceNotice("The voice server has muted you.", false), voiceNoticeOf(false, true, true, MicPermission.Granted))
        assertEquals("a phone call comes first", VoiceNotice("On hold while a phone call has the audio.", false), voiceNoticeOf(true, true, true, MicPermission.Refused))
    }

    @Test
    fun theSheetSaysWhatJoiningUnmutedDoesWithoutTheMicrophone() {
        assertNull(voiceJoinMicWords(MicPermission.Granted))
        val asked = VoiceNotice("Matty will ask to use the microphone when you join.", false)
        assertEquals(asked, voiceJoinMicWords(MicPermission.NotAsked))
        assertEquals("refused once: Android asks again", asked, voiceJoinMicWords(MicPermission.Declined))
        assertEquals(
            VoiceNotice("Matty can't use the microphone, so you'll join muted. Allow it in Settings.", true),
            voiceJoinMicWords(MicPermission.Refused),
        )
    }

    @Test
    fun joiningIsForReadersWhoMayWatch() {
        val on = VnAccess(access = "linked", configured = true, version = "4.3.0", name = "VillaNET", voice = VnVoiceWord(talk = "on"))
        assertTrue(voiceMayJoin(on))
        assertTrue(voiceMayJoin(on.copy(access = "letIn")))
        val locked = on.copy(access = "locked")
        assertFalse(voiceMayJoin(locked))
        assertEquals("Unlock VillaNET to join voice.", voiceNoTalkWords(locked))
        assertEquals("Unlock Matty to join voice.", voiceNoTalkWords(locked.copy(name = "")))
        assertEquals(VoiceJoinAction.None, voiceJoinActionOf(voiceMayJoin(locked), VoiceStatus.Idle, null, null, 1, full = false))
        // Talk off says why, locked or not.
        val off = locked.copy(voice = VnVoiceWord(talk = "off", reason = "Talking from Matty is switched off on this server."))
        assertFalse(voiceMayJoin(off))
        assertEquals("Talking from Matty is switched off on this server.", voiceNoTalkWords(off))
        assertFalse(voiceMayJoin(null))
    }

    @Test
    fun whatAPersonReadsOutAs() {
        assertEquals("Dave, deafened, on a phone", voiceMemberWords(dave, speaking = false))
        assertEquals("Bob, speaking", voiceMemberWords(bob, speaking = true))
        assertEquals("Carol, muted", voiceMemberWords(member(16, "Carol", muted = true), speaking = false))
        assertEquals("Dave, you, muted", voiceMemberWords(member(30, "Dave", muted = true, phone = true, me = true), speaking = false))
    }

    // ---- deafened everywhere a person is shown -------------------------------------------------

    @Test
    fun deafenedPeopleAreDeafenedOnTheirAvatars() {
        val snap = MattermostJson.decodeFromString(
            VnSnapshot.serializer(),
            """
            {"people":{"u-dave":{"voice":"muted"},"u-bob":{"voice":"open"},"u-carol":{"voice":"muted"},"u-erin":{"live":"erin"}},
             "voice":{"connected":true,"channels":[{"id":0,"name":"Test","depth":0}],
               "people":[{"session":12,"name":"dave (Matty)","userId":"u-dave","muted":true,"deaf":true,"phone":true},
                         {"session":15,"name":"bob","userId":"u-bob"},
                         {"session":16,"name":"carol","userId":"u-carol","muted":true},
                         {"session":20,"name":"erin","userId":"u-erin","deaf":true}]}}
            """.trimIndent(),
        )
        val persons = personsOf(snap, null, canWatch = true)
        assertEquals(VoiceState.Deafened, persons["u-dave"]?.voice)
        assertEquals(VoiceState.Open, persons["u-bob"]?.voice)
        assertEquals(VoiceState.Muted, persons["u-carol"]?.voice)
        assertEquals("the index says who is in voice; the roster only adds deafened", PersonFacts(live = true), persons["u-erin"])
        // The roster's own model agrees.
        assertTrue(voiceRoomsOf(snap.voice, { null })[0].people.single { it.session == 12L }.deaf)
        // Without the voice server, nobody is deafened from a roster left over.
        val gone = snap.copy(voice = snap.voice?.copy(connected = false))
        assertEquals(VoiceState.Muted, personsOf(gone, null, canWatch = true)["u-dave"]?.voice)
    }
}
