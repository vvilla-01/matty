package ceo.villa.matty

import android.content.pm.ServiceInfo
import ceo.villa.matty.data.api.MattermostJson
import ceo.villa.matty.plugins.villanet.VOICE_SLOTS
import ceo.villa.matty.plugins.villanet.VnAccess
import ceo.villa.matty.plugins.villanet.VnError
import ceo.villa.matty.plugins.villanet.VnSnapshot
import ceo.villa.matty.plugins.villanet.VnVoice
import ceo.villa.matty.plugins.villanet.VnVoiceChannel
import ceo.villa.matty.plugins.villanet.VnVoiceJoined
import ceo.villa.matty.plugins.villanet.VnVoiceMoved
import ceo.villa.matty.plugins.villanet.VnVoicePerson
import ceo.villa.matty.plugins.villanet.VnVoiceSelfState
import ceo.villa.matty.plugins.villanet.VoiceMember
import ceo.villa.matty.plugins.villanet.VoiceMessage
import ceo.villa.matty.plugins.villanet.VoiceOutput
import ceo.villa.matty.plugins.villanet.VoiceOutputKind
import ceo.villa.matty.plugins.villanet.VoiceSeat
import ceo.villa.matty.plugins.villanet.VoiceTestSignal
import ceo.villa.matty.plugins.villanet.keptChoice
import ceo.villa.matty.plugins.villanet.offerShapeOf
import ceo.villa.matty.plugins.villanet.pickOutput
import ceo.villa.matty.plugins.villanet.voiceJoinBody
import ceo.villa.matty.plugins.villanet.voiceMessageOf
import ceo.villa.matty.plugins.villanet.voiceOfferTrouble
import ceo.villa.matty.plugins.villanet.voiceOffered
import ceo.villa.matty.plugins.villanet.voiceRetryDelay
import ceo.villa.matty.plugins.villanet.voiceRoomsOf
import ceo.villa.matty.plugins.villanet.VoiceNotificationMic
import ceo.villa.matty.plugins.villanet.VoiceRefusal
import ceo.villa.matty.plugins.villanet.VoiceRetry
import ceo.villa.matty.plugins.villanet.VoiceServiceCall
import ceo.villa.matty.plugins.villanet.voiceMicRefusedForGood
import ceo.villa.matty.plugins.villanet.voiceNotificationMicOf
import ceo.villa.matty.plugins.villanet.VoicePeer
import ceo.villa.matty.plugins.villanet.voiceRefusalOf
import ceo.villa.matty.plugins.villanet.voiceServiceType
import ceo.villa.matty.plugins.villanet.voiceServiceTypeFor
import ceo.villa.matty.plugins.villanet.voiceStateBody
import ceo.villa.matty.plugins.villanet.voiceTalkOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.PeerConnection
import java.nio.ByteBuffer

/**
 * Voice channels' pure parts, and the contract's voice answers as hand-written samples of the
 * plugin's contract (4.3.0): the plugin's own fixtures replace them in VillaNetContractTest once
 * they are synced.
 */
class VoiceProtocolTest {
    private fun <T> decode(s: kotlinx.serialization.KSerializer<T>, text: String): T = MattermostJson.decodeFromString(s, text)

    // ---- the contract, as samples ------------------------------------------------------------

    @Test
    fun accessSaysWhetherTalkIsOn() {
        val on = decode(VnAccess.serializer(), """{"access":"linked","configured":true,"contract":4,"version":"4.3.0","name":"Matty","voice":{"talk":"on"}}""")
        assertTrue(voiceTalkOn(on))
        val off = decode(VnAccess.serializer(), """{"access":"linked","configured":true,"contract":4,"version":"4.3.0","voice":{"talk":"off","reason":"Talking from Matty is switched off on this server."}}""")
        assertFalse(voiceTalkOn(off))
        assertEquals("Talking from Matty is switched off on this server.", off.voice?.reason)
        // Before 4.3.0 there is no word, and no talking, whatever an answer might hold.
        assertNull(decode(VnAccess.serializer(), """{"access":"linked","version":"4.2.0"}""").voice)
        assertFalse(voiceTalkOn(on.copy(version = "4.2.0")))
        assertFalse(voiceTalkOn(null))
        assertTrue(voiceOffered("4.3.0") && voiceOffered("4.10.0"))
        assertFalse(voiceOffered("4.2.9") || voiceOffered("") || voiceOffered(null))
    }

    private val snapshotVoice = """
        {"voice":{"connected":true,
          "channels":[{"id":0,"name":"Test","depth":0},{"id":1,"name":"Lobby","parent":0,"depth":1},
                      {"id":2,"name":"Gaming","parent":0,"depth":1},{"id":4,"name":"Duo","parent":2,"depth":2,"max":2}],
          "people":[{"session":12,"name":"dave (Matty)","userId":"u-dave","channel":1,"muted":true,"deaf":true,"phone":true},
                    {"session":15,"name":"Bob","channel":1},
                    {"session":16,"name":"carol","userId":"u-carol","muted":true},
                    {"session":20,"name":"Erin","channel":4},{"session":21,"name":"alice","userId":"u-alice","channel":4}]},
         "people":{}}
    """.trimIndent()

    @Test
    fun theSnapshotsVoiceObjectDecodes() {
        val v = decode(VnSnapshot.serializer(), snapshotVoice).voice!!
        assertTrue(v.connected)
        assertEquals(VnVoiceChannel(id = 0, name = "Test", parent = null, depth = 0), v.channels[0])
        assertEquals("a parent of 0 is a parent", VnVoiceChannel(id = 1, name = "Lobby", parent = 0, depth = 1), v.channels[1])
        assertEquals(2, v.channels[3].max)
        assertEquals(VnVoicePerson(name = "dave (Matty)", userId = "u-dave", muted = true, session = 12, channel = 1, deaf = true, phone = true), v.people[0])
        assertEquals("no channel is the root", 0, v.people[2].channel)
        // What an older plugin sent still decodes: no channels, no sessions.
        val old = decode(VnSnapshot.serializer(), """{"voice":{"connected":true,"people":[{"name":"Bob","userId":"","muted":false}]}}""").voice!!
        assertTrue(old.channels.isEmpty())
        assertEquals(0L, old.people.single().session)
    }

    @Test
    fun theRoutesAnswersAndBodies() {
        val joined = decode(VnVoiceJoined.serializer(), """{"sdp":"v=0\r\n","session":12,"channel":1,"name":"dave (Matty)"}""")
        assertEquals(VnVoiceJoined("v=0\r\n", 12, 1, "dave (Matty)"), joined)
        assertEquals("the SDP is never logged whole", "VnVoiceJoined(session=12, channel=1)", joined.toString())
        assertEquals(VnVoiceJoined(channel = 0), decode(VnVoiceJoined.serializer(), """{"sdp":"","session":0,"channel":0}""").copy(name = ""))
        assertEquals(VnVoiceMoved(2), decode(VnVoiceMoved.serializer(), """{"channel":2}"""))
        assertEquals(VnVoiceSelfState(true, false), decode(VnVoiceSelfState.serializer(), """{"muted":true,"deafened":false}"""))

        assertEquals(Json.parseToJsonElement("""{"channel":1,"muted":false,"deafened":false,"sdp":"v=0"}""").jsonObject, voiceJoinBody(1, false, false, "v=0"))
        assertEquals("deafened is muted too", Json.parseToJsonElement("""{"muted":true,"deafened":true}""").jsonObject, voiceStateBody(false, true))
        assertEquals(Json.parseToJsonElement("""{"channel":0,"muted":true,"deafened":true,"sdp":"x"}""").jsonObject, voiceJoinBody(0, false, true, "x"))
    }

    @Test
    fun theRefusalsDecodeAsEveryRefusalDoes() {
        val full = decode(VnError.serializer(), """{"error":"That channel is full.","code":"voice_denied"}""")
        assertEquals("voice_denied", full.code)
        val budget = decode(VnError.serializer(), """{"error":"Too many people joined voice just now. Try again in a moment.","code":"unavailable","retryAfterMs":42000}""")
        assertEquals(42_000, budget.retryAfterMs)
    }

    @Test
    fun theDataChannelsThreeMessages() {
        assertEquals(VoiceMessage.Talking(setOf(12L, 15L)), voiceMessageOf("""{"talking":[12,15]}"""))
        assertEquals(VoiceMessage.Talking(emptySet()), voiceMessageOf("""{"talking":[]}"""))
        assertEquals(VoiceMessage.Self(2, muted = false, suppressed = true), voiceMessageOf("""{"self":{"channel":2,"muted":false,"suppressed":true}}"""))
        assertEquals("channel 0 left out is the root", VoiceMessage.Self(0, muted = true, suppressed = false), voiceMessageOf("""{"self":{"muted":true}}"""))
        assertEquals(
            "the voice server's own word on the self mute and deafen",
            VoiceMessage.Self(1, muted = false, suppressed = false, selfMuted = true, selfDeafened = false),
            voiceMessageOf("""{"self":{"channel":1,"muted":false,"suppressed":false,"selfMuted":true,"selfDeafened":false}}"""),
        )
        assertEquals(VoiceMessage.End("kicked", "Kicked by an admin."), voiceMessageOf("""{"end":"kicked","reason":"Kicked by an admin."}"""))
        assertEquals(VoiceMessage.End("mumble", ""), voiceMessageOf("""{"end":"mumble"}"""))
        assertNull(voiceMessageOf("not json"))
        assertNull(voiceMessageOf("""{"something":"else"}"""))
    }

    // ---- the offer -----------------------------------------------------------------------------

    /** An offer as libwebrtc writes it for one sending and four receiving audio sections and a data channel (trimmed). */
    private val offer = buildString {
        append("v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0 1 2 3 4 5\r\n")
        for (mid in 0..4) {
            append("m=audio 9 UDP/TLS/RTP/SAVPF 111 63\r\nc=IN IP4 0.0.0.0\r\na=mid:$mid\r\n")
            append("a=extmap:1 urn:ietf:params:rtp-hdrext:ssrc-audio-level\r\n")
            append(if (mid == 0) "a=sendonly\r\n" else "a=recvonly\r\n")
            append("a=rtpmap:111 opus/48000/2\r\na=fmtp:111 minptime=10;useinbandfec=1\r\n")
        }
        append("m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 0.0.0.0\r\na=mid:5\r\na=sctp-port:5000\r\n")
    }

    @Test
    fun theOfferHasTheShapeTheContractAsks() {
        val s = offerShapeOf(offer)
        assertEquals(1, s.sending)
        assertEquals(VOICE_SLOTS, s.receiving)
        assertEquals(1, s.data)
        assertEquals(0, s.other)
        assertTrue(s.opus && s.audioLevel)
        assertNull(voiceOfferTrouble(offer))
    }

    @Test
    fun anOfferOfAnyOtherShapeIsSaidToBeWrong() {
        assertNull(voiceOfferTrouble(offerWith(1)))
        assertEquals("2 sending audio sections", voiceOfferTrouble(offerWith(2)))
        assertEquals("0 data sections", voiceOfferTrouble(offer.substringBefore("m=application")))
        assertEquals("the microphone's section offers no audio level", voiceOfferTrouble(offer.replace("urn:ietf:params:rtp-hdrext:ssrc-audio-level", "urn:ietf:params:rtp-hdrext:sdes:mid")))
        // A section with no direction of its own is sendrecv, which the contract refuses.
        assertEquals("1 other sections", voiceOfferTrouble(offer.replaceFirst("a=recvonly\r\n", "")))
        assertEquals("1 other sections", voiceOfferTrouble("$offer" + "m=video 9 UDP/TLS/RTP/SAVPF 96\r\na=recvonly\r\n"))
    }

    private fun offerWith(sending: Int): String = buildString {
        append("v=0\r\n")
        for (mid in 0 until sending + 1) {
            append("m=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=extmap:1 urn:ietf:params:rtp-hdrext:ssrc-audio-level\r\n")
            append(if (mid < sending) "a=sendonly\r\n" else "a=recvonly\r\n")
            append("a=rtpmap:111 opus/48000/2\r\n")
        }
        append("m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n")
    }

    // ---- trying again, the service, the outputs ------------------------------------------------

    @Test
    fun theReconnectSchedule() {
        assertEquals(listOf(1_000L, 2_000L, 5_000L, 10_000L, 15_000L, 15_000L, 15_000L), (0..6).map { voiceRetryDelay(it) })
    }

    @Test
    fun aBare404IsThePluginNotRunningAndIsWaitedOut() {
        // Mattermost's own answer while the plugin restarts or is upgraded: no code of the plugin's.
        assertEquals(VoiceRefusal(VoiceRetry.Wait, "Matty is not answering. Try again in a moment."), voiceRefusalOf(404, null, 0, "Matty", code = ""))
        assertEquals(VoiceRetry.Wait, voiceRefusalOf(404, null, 0, "Matty").retry)
        // The plugin's own: no such channel.
        assertEquals(VoiceRefusal(VoiceRetry.Stop, "No such channel."), voiceRefusalOf(404, "No such channel.", 0, "Matty", code = "not_found"))
    }

    @Test
    fun aMicrophoneIsRefusedForGoodOnlyWhenAndroidStopsExplaining() {
        assertFalse("granted", voiceMicRefusedForGood(granted = true, explainedBefore = true, explainedAfter = false, remembered = true))
        assertFalse("the first refusal: Android explains it next time", voiceMicRefusedForGood(false, explainedBefore = false, explainedAfter = true, remembered = false))
        assertTrue("the second: it won't ask again", voiceMicRefusedForGood(false, explainedBefore = true, explainedAfter = false, remembered = false))
        assertFalse("dismissed without an answer", voiceMicRefusedForGood(false, explainedBefore = false, explainedAfter = false, remembered = false))
        assertFalse("dismissed after a refusal", voiceMicRefusedForGood(false, explainedBefore = true, explainedAfter = true, remembered = false))
        assertTrue("kept from before", voiceMicRefusedForGood(false, explainedBefore = false, explainedAfter = false, remembered = true))
    }

    @Test
    fun theNotificationsUnmuteOpensMattyWhileTheServiceHasNoMicrophone() {
        assertEquals(VoiceNotificationMic.Mute, voiceNotificationMicOf(muted = false, hasMic = false))
        assertEquals(VoiceNotificationMic.Mute, voiceNotificationMicOf(muted = false, hasMic = true))
        assertEquals(VoiceNotificationMic.Unmute, voiceNotificationMicOf(muted = true, hasMic = true))
        assertEquals(VoiceNotificationMic.OpenToUnmute, voiceNotificationMicOf(muted = true, hasMic = false))
    }

    @Test
    fun aVoiceConnectionBundlesItsSectionsAndGathersUdpOnly() {
        val config = VoicePeer.config()
        assertEquals(PeerConnection.BundlePolicy.MAXBUNDLE, config.bundlePolicy)
        assertEquals(PeerConnection.TcpCandidatePolicy.DISABLED, config.tcpCandidatePolicy)
        assertEquals(PeerConnection.SdpSemantics.UNIFIED_PLAN, config.sdpSemantics)
        assertEquals(PeerConnection.ContinualGatheringPolicy.GATHER_ONCE, config.continualGatheringPolicy)
        assertTrue("no ICE servers", config.iceServers.isEmpty())
    }

    @Test
    fun theServicesTypeFollowsThePermissionAlone() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, voiceServiceType(micGranted = true))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, voiceServiceType(micGranted = false))
    }

    @Test
    fun theServiceBecomesForegroundOnceAndItsTypeOnlyGainsTheMicrophone() {
        val both = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        val media = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        // Its start: the type by the permission as it is then.
        assertEquals(both, voiceServiceTypeFor(VoiceServiceCall.Start, foreground = false, hasMic = false, micGranted = true))
        assertEquals(media, voiceServiceTypeFor(VoiceServiceCall.Start, foreground = false, hasMic = false, micGranted = false))
        assertNull("started again while it is one: left as it is", voiceServiceTypeFor(VoiceServiceCall.Start, foreground = true, hasMic = false, micGranted = true))
        // The notification's Mute, Unmute and Leave never ask for a type, whatever holds.
        for (fg in listOf(true, false)) for (mic in listOf(true, false)) for (granted in listOf(true, false)) {
            assertNull(voiceServiceTypeFor(VoiceServiceCall.NotificationAction, fg, mic, granted))
        }
        // The microphone found allowed with Matty on screen: gained, once.
        assertEquals(both, voiceServiceTypeFor(VoiceServiceCall.MicAllowed, foreground = true, hasMic = false, micGranted = true))
        assertNull("has it: never taken down", voiceServiceTypeFor(VoiceServiceCall.MicAllowed, foreground = true, hasMic = true, micGranted = true))
        assertNull("has it, the permission gone: never taken down", voiceServiceTypeFor(VoiceServiceCall.MicAllowed, foreground = true, hasMic = true, micGranted = false))
        assertNull(voiceServiceTypeFor(VoiceServiceCall.MicAllowed, foreground = true, hasMic = false, micGranted = false))
        assertNull("not a foreground service: nothing to give it", voiceServiceTypeFor(VoiceServiceCall.MicAllowed, foreground = false, hasMic = false, micGranted = true))
    }

    @Test
    fun theOutputIsAHeadsetOrBluetoothElseTheSpeakerAndTheChoiceWinsWhileItLasts() {
        val speaker = VoiceOutput(1, VoiceOutputKind.Speaker, "Speaker")
        val ear = VoiceOutput(2, VoiceOutputKind.Earpiece, "Phone")
        val bt = VoiceOutput(3, VoiceOutputKind.Bluetooth, "Buds")
        val wired = VoiceOutput(4, VoiceOutputKind.Headset, "Headset")
        assertEquals(speaker, pickOutput(listOf(ear, speaker), null))
        assertEquals(bt, pickOutput(listOf(ear, speaker, bt), null))
        assertEquals(wired, pickOutput(listOf(ear, speaker, bt, wired), null))
        assertEquals("the person's choice", ear, pickOutput(listOf(ear, speaker, bt), chosen = 2))
        // The chosen device goes: the choice with it.
        assertNull(keptChoice(listOf(ear, speaker), chosen = 3))
        assertEquals(2, keptChoice(listOf(ear, speaker), chosen = 2))
        assertEquals(speaker, pickOutput(listOf(ear, speaker), chosen = 3))
        assertNull(pickOutput(emptyList(), null))
        assertTrue(bt.private && wired.private)
        assertFalse(speaker.private || ear.private)
    }

    // ---- the roster ------------------------------------------------------------------------------

    private val accounts = mapOf("u-dave" to "Dave", "u-carol" to "Carol")

    @Test
    fun theRosterIsTheChannelsInOrderWithTheirPeople() {
        val v = decode(VnSnapshot.serializer(), snapshotVoice).voice
        val rooms = voiceRoomsOf(v, accounts::get)
        assertEquals(listOf("Test", "Lobby", "Gaming", "Duo"), rooms.map { it.name })
        assertEquals(listOf(0, 1, 1, 2), rooms.map { it.depth })
        assertEquals(listOf("Carol"), rooms[0].people.map { it.name })
        // A Mattermost account by its own name; anybody else by the voice server's.
        assertEquals(listOf("Dave", "Bob"), rooms[1].people.map { it.name })
        assertEquals(VoiceMember(12, "u-dave", "Dave", muted = true, deaf = true, phone = true, speaking = false, me = false), rooms[1].people[0])
        assertEquals("an account this app doesn't know keeps the voice server's name", "alice", rooms[3].people[1].name)
        assertTrue(rooms[2].people.isEmpty())
        assertEquals(2, rooms[3].max)
        assertTrue(rooms[3].full)
        assertFalse(rooms[1].full)
    }

    @Test
    fun thisPhonesSeatIsPlacedAtOnceAndSpeakingIsShown() {
        val v = decode(VnSnapshot.serializer(), snapshotVoice).voice
        // Just joined Gaming as session 30; the snapshot still has the old phone session in Lobby.
        val seat = VoiceSeat(session = 30, name = "dave (Matty)", channel = 2, userId = "u-dave")
        val rooms = voiceRoomsOf(v, accounts::get, seat, muted = false, deafened = false, talking = setOf(30L, 15L))
        assertEquals(listOf("Bob"), rooms[1].people.map { it.name })
        assertTrue("talking known for this phone's channel", rooms[1].people.single().speaking)
        val me = rooms[2].people.single()
        assertEquals(VoiceMember(30, "u-dave", "Dave", muted = false, deaf = false, phone = true, speaking = true, me = true), me)
        // Deafened as it holds now, not as the snapshot last said.
        val deaf = voiceRoomsOf(v, accounts::get, seat, muted = false, deafened = true)[2].people.single()
        assertTrue(deaf.muted && deaf.deaf)
        // Nobody speaks while this phone is in no channel.
        assertFalse(voiceRoomsOf(v, accounts::get, talking = setOf(15L))[1].people.any { it.speaking })
    }

    @Test
    fun thisPhonesRingIsOffWhileItIsShownMutedOrDeafened() {
        val v = decode(VnSnapshot.serializer(), snapshotVoice).voice
        val seat = VoiceSeat(session = 30, name = "dave (Matty)", channel = 2, userId = "u-dave")
        val muted = voiceRoomsOf(v, accounts::get, seat, muted = true, deafened = false, talking = setOf(30L, 15L))
        assertFalse("heard by the server, but shown muted", muted[2].people.single().speaking)
        assertTrue("the others' rings are as heard", muted[1].people.single().speaking)
        assertFalse(voiceRoomsOf(v, accounts::get, seat, muted = false, deafened = true, talking = setOf(30L))[2].people.single().speaking)
    }

    @Test
    fun noRosterWithoutTheVoiceServerOrItsChannels() {
        assertTrue(voiceRoomsOf(null, accounts::get).isEmpty())
        assertTrue(voiceRoomsOf(VnVoice(connected = false, channels = listOf(VnVoiceChannel(0, "Test"))), accounts::get).isEmpty())
        assertTrue(voiceRoomsOf(VnVoice(connected = true, people = listOf(VnVoicePerson("Bob"))), accounts::get).isEmpty())
    }

    // ---- the debug test voice --------------------------------------------------------------------

    @Test
    fun theTestVoiceIsBurstsOfToneAndSilenceAndLeavesTheBufferAsItWas() {
        val rate = 48_000
        val buf = ByteBuffer.allocateDirect(rate / 100 * 2) // 10 ms, mono
        buf.position(7)
        val frames = rate / 100
        // Off: nothing is touched, and the capture time comes back.
        VoiceTestSignal.on = false
        assertEquals(123L, VoiceTestSignal.onBuffer(buf, 2, 1, rate, frames * 2, 123L))
        assertTrue((0 until buf.capacity()).all { buf.get(it) == 0.toByte() })

        VoiceTestSignal.on = true
        try {
            val loud = mutableListOf<Boolean>()
            // A second of 10 ms buffers: two cycles of 300 ms on, 200 ms off.
            repeat(100) {
                assertEquals(9L, VoiceTestSignal.onBuffer(buf, 2, 1, rate, frames * 2, 9L))
                var peak = 0
                for (i in 0 until frames) {
                    val s = (buf.get(2 * i).toInt() and 0xff) or (buf.get(2 * i + 1).toInt() shl 8)
                    peak = maxOf(peak, kotlin.math.abs(s.toShort().toInt()))
                }
                loud += peak > 3_000
            }
            assertEquals(7, buf.position())
            assertEquals(buf.capacity(), buf.limit())
            assertTrue("bursts", loud.count { it } in 50..62)
            assertTrue("and silences between them", loud.count { !it } >= 38)
            assertTrue("each burst is about 300 ms", loud.windowed(30).any { w -> w.all { it } })
        } finally {
            VoiceTestSignal.on = false
        }
    }
}
