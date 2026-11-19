package ceo.villa.matty

import ceo.villa.matty.data.api.MattermostJson
import ceo.villa.matty.plugins.PersonFacts
import ceo.villa.matty.plugins.UserActivity
import ceo.villa.matty.plugins.VoiceState
import ceo.villa.matty.plugins.villanet.BADGE_BIRTHDAY
import ceo.villa.matty.plugins.villanet.BADGE_LIVE
import ceo.villa.matty.plugins.villanet.VOICE_MUTED
import ceo.villa.matty.plugins.villanet.VOICE_OPEN
import ceo.villa.matty.plugins.villanet.VillaNetApi
import ceo.villa.matty.plugins.villanet.VillaNetAwardLogic
import ceo.villa.matty.plugins.villanet.VnAccess
import ceo.villa.matty.plugins.villanet.VnAccounts
import ceo.villa.matty.plugins.villanet.VnAppChallenge
import ceo.villa.matty.plugins.villanet.VnAppWord
import ceo.villa.matty.plugins.villanet.VnAttestAnswer
import ceo.villa.matty.plugins.villanet.VnRelease
import ceo.villa.matty.plugins.villanet.VnBanners
import ceo.villa.matty.plugins.villanet.VnBoards
import ceo.villa.matty.plugins.villanet.VnCards
import ceo.villa.matty.plugins.villanet.VnClip
import ceo.villa.matty.plugins.villanet.VnError
import ceo.villa.matty.plugins.villanet.VnPersonFacts
import ceo.villa.matty.plugins.villanet.VnSnapshot
import ceo.villa.matty.plugins.villanet.VnStream
import ceo.villa.matty.plugins.villanet.PRODUCT_NAME
import ceo.villa.matty.plugins.villanet.PUSH_SINCE
import ceo.villa.matty.plugins.villanet.VnPushState
import ceo.villa.matty.plugins.villanet.pushOffered
import ceo.villa.matty.push.Arrival
import ceo.villa.matty.push.PushPayload
import ceo.villa.matty.push.UnifiedPush
import ceo.villa.matty.plugins.villanet.nameOf
import ceo.villa.matty.plugins.villanet.olderThan
import ceo.villa.matty.plugins.villanet.personsOf
import ceo.villa.matty.plugins.villanet.steamGameOf
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The Matty plugin's client contract, from the server's own mouth. The files under
 * src/test/resources/villanet are what the plugin really answers: its tests write them (its
 * contract/fixtures) from the handlers and structs that serve a real client, and
 * tools/sync-villanet-contract.sh copies them here. These tests decode them with the models the
 * app uses, so a field renamed, retyped or dropped on either side fails a test instead of a
 * screen; and, with the plugin's checkout beside Matty's, fail while the two copies differ.
 *
 * (me-linked.json and me-locked.json are the plugin's `/me`, which its web panel reads. The app
 * never does: its stream key and panel settings are none of the app's business.)
 */
class VillaNetContractTest {
    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/villanet/$name")) { "no fixture $name: run tools/sync-villanet-contract.sh" }.bufferedReader().use { it.readText() }

    @Test
    fun theCopiesAreInStepWithThePluginsOwn() {
        val theirs = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "../Broadcast Box Plugin/contract").normalize() }.firstOrNull { File(it, "fixtures").isDirectory }
        // Only where the two repositories are checked out side by side (a developer's machine).
        assumeTrue("the plugin's checkout isn't beside Matty's", theirs != null)
        val files = File(theirs!!, "fixtures").listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name } + File(theirs, "contract.json")
        assertTrue("the plugin has no fixtures", files.size > 1)
        for (f in files) {
            assertEquals("${f.name} differs from the plugin's: run tools/sync-villanet-contract.sh, then make the tests pass", f.readText(), fixture(f.name))
        }
        val mine = File(javaClass.getResource("/villanet/contract.json")!!.toURI()).parentFile.list { _, n -> n.endsWith(".json") }!!.toSet()
        assertEquals("a file here that the plugin no longer writes", files.map { it.name }.toSet(), mine)
    }

    @Test
    fun thisAppIsWrittenForTheContractThePluginSpeaks() {
        val record = Json.parseToJsonElement(fixture("contract.json")).jsonObject
        assertEquals(VnAccess.CONTRACT, record.getValue("revision").jsonPrimitive.int)
        assertEquals("the plugin a reader out of step is asked for", VnAccess.SINCE, record.getValue("since").jsonPrimitive.content)
    }

    @Test
    fun accessSaysWhoYouAreToThePluginAndWhichPluginItIs() {
        val access = decoded("access.json", VnAccess.serializer())
        assertEquals("letIn", access.access)
        assertTrue(access.configured && access.canWatch)
        assertFalse(access.linked)
        assertEquals(VnAccess.CONTRACT, access.contract)
        assertTrue(access.version.isNotEmpty())
        // And what this server calls it: a server nobody named calls it Matty, and says so.
        assertEquals("Matty", access.name)
        assertEquals("Matty", nameOf(access))
    }

    @Test
    fun theNameIsTheServersAndMattyOnlyWhenItSaysNone() {
        assertEquals("Acme TV", nameOf(VnAccess(name = "Acme TV")))
        assertEquals("trimmed, as the server trims it", "Acme TV", nameOf(VnAccess(name = "  Acme TV ")))
        // Before the server has said, and when it says nothing (a link's answer doesn't): Matty.
        assertEquals(PRODUCT_NAME, nameOf(null))
        assertEquals(PRODUCT_NAME, nameOf(VnAccess()))
        assertEquals(PRODUCT_NAME, nameOf(VnAccess(name = "   ")))
        assertEquals("Matty", PRODUCT_NAME)
        // The model holds only what was sent: an answer without a name holds none.
        assertEquals("", decoded("unlink.json", VnAccess.serializer()).name)
    }

    @Test
    fun anUnlinkAnswersWithTheAccessLeft() {
        val left = decoded("unlink.json", VnAccess.serializer())
        assertEquals("letIn", left.access)
        assertTrue(left.configured)
        // Which plugin it is, and its name, aren't said again: the app keeps what /access told it.
        assertEquals(0, left.contract)
    }

    @Test
    fun accessSaysWhetherTheReleaseIsSignedAndWhatTheAppCheckFound() {
        val access = decoded("access.json", VnAccess.serializer())
        // What the plugin found of its own folder: the app looks for itself (GET /release).
        assertEquals("unsigned", access.release?.state)
        assertEquals(access.version, access.release?.version)
        // The check is on by default and has found nothing for this session yet.
        assertEquals(VnAppWord(check = "report", state = "none"), access.app)
        val verified = decoded("access-app-verified.json", VnAccess.serializer())
        assertEquals(VnAppWord(check = "require", state = "verified", versionCode = 28), verified.app)
    }

    @Test
    fun theReleaseAndTheAppCheckAnswerAsTheAppReadsThem() {
        val release = decoded("release-unsigned.json", VnRelease.serializer())
        assertEquals("unsigned", release.state)
        assertTrue("nothing to verify in an unsigned release", release.manifest.isEmpty() && release.rsa.isEmpty() && release.mldsa87.isEmpty())

        val challenge = decoded("app-challenge.json", VnAppChallenge.serializer())
        assertEquals("32 bytes, in base64", 32, java.util.Base64.getDecoder().decode(challenge.challenge).size)
        assertTrue(challenge.expires > 0)

        val yes = decoded("app-attest-verified.json", VnAttestAnswer.serializer())
        assertEquals("verified", yes.state)
        assertTrue(yes.versionCode > 0 && yes.expires > 0)
        val no = decoded("app-attest-unverified.json", VnAttestAnswer.serializer())
        assertEquals("unverified", no.state)
        assertTrue("the server says why, in its words", no.reason.isNotBlank())

        val refused = decoded("refusal-app-unverified.json", VnError.serializer())
        assertEquals(VillaNetApi.APP_UNVERIFIED, refused.code)
        assertTrue(refused.error.isNotBlank())
    }

    @Test
    fun aRefusalIsASentenceAndACode() {
        val refusal = decoded("refusal-locked.json", VnError.serializer())
        assertEquals("locked", refusal.code)
        assertTrue(refusal.error.isNotBlank())
        assertEquals("not about time: no wait is said", 0, refusal.retryAfterMs)
    }

    @Test
    fun everyRefusalIsThatOneBody() {
        // From three different routes (the gate, playback, a clip), and one that is about time.
        // The sentence is the server's, which is the only wording there is: the app shows it.
        val notLive = decoded("refusal-not-live.json", VnError.serializer())
        assertEquals("not_live", notLive.code)
        assertTrue(notLive.error.isNotBlank())

        val gone = decoded("refusal-clip-gone.json", VnError.serializer())
        assertEquals(VillaNetApi.CLIP_GONE, gone.code)
        assertTrue(gone.error.isNotBlank())

        val wait = decoded("refusal-rate-limited.json", VnError.serializer())
        assertEquals("rate_limited", wait.code)
        assertTrue("how long until asking again can work", wait.retryAfterMs > 0)
        // The sentence a refused link shows is this one, as sent: the app keeps no copy of it.
        assertEquals(wait.error, VillaNetApi.lockCopy(wait.error, "Acme TV"))
        assertEquals("only for no sentence at all", VillaNetApi.notAnswering("Acme TV"), VillaNetApi.lockCopy(null, "Acme TV"))
        assertEquals(VillaNetApi.notAnswering("Acme TV"), VillaNetApi.lockCopy("  ", "Acme TV"))
        assertEquals("Acme TV is not answering. Try again in a moment.", VillaNetApi.notAnswering("Acme TV"))
    }

    @Test
    fun aPlanSaysWhetherThereIsRoom() {
        val plan = decoded("snapshot.json", VnSnapshot.serializer()).schedule.single()
        assertEquals("Arma co-op", plan.title)
        assertEquals(listOf("u-bob"), plan.joined.orEmpty().map { it.userId })
        // Said by the server, which counts the places taken; the app never counts the names.
        assertFalse(plan.full)
    }

    @Test
    fun aClipAndItsCard() {
        val clip = decoded("clip.json", VnClip.serializer())
        assertEquals("00000000000000a1", clip.id)
        assertTrue(clip.expiresAt > clip.createdAt)
        assertTrue(clip.canVote && clip.canShare && clip.voted)

        val cards = decoded("clip-cards.json", VnCards.serializer()).cards
        val card = cards.getValue(clip.id)!!
        assertEquals(clip.title, card.title)
        assertEquals("when it goes, as the clip itself says", clip.expiresAt, card.expiresAt)
        assertEquals(clip.upvotes, card.upvotes)
        // A clip that has gone is there, as nothing: asked about, and answered.
        assertTrue(cards.containsKey("00000000000000b2"))
        assertEquals(null, cards["00000000000000b2"])
    }

    @Test
    fun theSnapshotCarriesItsPeople() {
        val s = decoded("snapshot.json", VnSnapshot.serializer())
        assertTrue(s.configured)
        assertTrue(s.streams.isNotEmpty())
        assertTrue("when to read the boards again, and nothing of the boards themselves", s.boards?.version.orEmpty().isNotEmpty())
        assertEquals(true, s.voice?.connected)

        // The index, as sent: Dave on the air, in voice on two devices (one open: open), wearing the live badge;
        // Bob muted in voice, wearing the birthday cake, and not live (the card is paused).
        val dave = s.people.getValue("u-dave")
        assertEquals(VnPersonFacts(live = dave.live, voice = VOICE_OPEN, badge = BADGE_LIVE), dave)
        assertTrue("the handle is a card in the snapshot", s.streams.any { it.handle == dave.live && it.userId == "u-dave" })
        assertEquals(VnPersonFacts(voice = VOICE_MUTED, badge = BADGE_BIRTHDAY), s.people.getValue("u-bob"))

        // And what the rest of the app is told about them. The cake is Bob's to show; only the
        // live badge is "not their own words".
        val persons = personsOf(s, null, canWatch = false)
        assertEquals(PersonFacts(live = true, voice = VoiceState.Open, holdsStatus = true), persons["u-dave"])
        // Bob is deafened in the voice roster: the index still says "muted", the roster says more.
        assertEquals(PersonFacts(voice = VoiceState.Deafened), persons["u-bob"])
        assertEquals("everybody in the index, and nobody else", s.people.keys, persons.keys)
    }

    @Test
    fun whoIsLiveIsOnlyEverWhatTheServerSays() {
        // Cards and a voice roster with no index beside them say nothing about anybody: the rule
        // for who is live is the server's alone, and the app holds no second copy of it to drift.
        val cardsOnly = VnSnapshot(streams = listOf(VnStream(handle = "h", userId = "u-dave")))
        assertTrue(personsOf(cardsOnly, null, canWatch = true).isEmpty())
        assertTrue(personsOf(null, null, canWatch = true).isEmpty())
    }

    @Test
    fun theStreamsEventIsTheSnapshotAndItsTag() {
        val event = Json.parseToJsonElement(fixture("event-streams.json")).jsonObject
        assertEquals(setOf("payload", "tag"), event.keys)
        val payload = event.getValue("payload").jsonPrimitive.content
        val pushed = MattermostJson.decodeFromString(VnSnapshot.serializer(), payload)
        assertEquals("h1", pushed.streams.single().handle)
        assertEquals(VnPersonFacts(live = "h1"), pushed.people["u-dave"])
        // Bare here; GET /streams sends the same tag quoted, as its ETag.
        assertTrue(Regex("^[0-9a-f]{16}$").matches(event.getValue("tag").jsonPrimitive.content))
        holdsNoSurprises("event-streams.json", Json.parseToJsonElement(payload), pushed, VnSnapshot.serializer())
    }

    @Test
    fun aQuietServerSaysLittleAndTheAppAssumesNothing() {
        // Every optional feature off: whatever the server leaves out, the app must take to be
        // off, empty or nobody (checked field by field in holdsNoSurprises).
        val s = decoded("snapshot-quiet.json", VnSnapshot.serializer())
        assertFalse(s.livePreviews || s.twitchOn || s.twitchEmbed)
        assertTrue(s.voice == null)
        assertEquals("one person on the air, and it is said", 1, s.people.count { it.value.live.isNotEmpty() })

        // And the boards of the same server: the airtime boards are all there is.
        val b = decoded("boards-quiet.json", VnBoards.serializer())
        assertFalse(b.fansOn)
        assertTrue(b.fans.isEmpty())
        assertTrue(b.allTime.isNotEmpty())
        assertNull(VillaNetAwardLogic.biggestFan(b, "u-dave"))
    }

    @Test
    fun theBoardsCarryEverybodysRecordDecided() {
        val s = decoded("snapshot.json", VnSnapshot.serializer())
        val b = decoded("boards.json", VnBoards.serializer())
        // Published together: a snapshot's word for the boards is the version of those boards.
        assertEquals(s.boards?.version, b.version)
        assertTrue(b.allTime.isNotEmpty() && b.month.isNotEmpty() && b.monthLabel.isNotEmpty())

        // Bob holds one of everything a profile can show. The rows, their order and their titles
        // are the server's; the app gives each its figures and nothing more.
        val rows = VillaNetAwardLogic.rowsFor(b, "u-bob")
        assertEquals(
            listOf("Runner-up", "Champion", "Top Snooper", "Best Clipper", "Biggest fan of Dave", "Achievements", "Monthly champion"),
            rows.map { it.title },
        )
        assertEquals("All time · 1h", rows[0].meta)
        assertEquals("${b.monthLabel} · 10m", rows[1].meta)
        assertEquals("All time · 25h watching others", rows[2].meta)
        assertEquals("All time · 12 upvotes on 3 clips", rows[3].meta)
        assertEquals("25 hours watching", rows[4].meta)
        assertEquals(listOf("On the podium 1 month"), rows[5].chips.map { it.text })
        assertEquals(listOf(2 to 1), rows[6].tallies.map { it.rank to it.count })
        // And who is whose biggest fan, the other way round, from the list.
        assertEquals("Bob Turner", VillaNetAwardLogic.biggestFan(b, "u-dave")?.fan)
        assertTrue(VillaNetAwardLogic.rowsFor(b, "u-nobody").isEmpty())
    }

    @Test
    fun theBannersSayWhoHasOneAndWhichPicture() {
        val s = decoded("snapshot.json", VnSnapshot.serializer())
        val b = decoded("banners.json", VnBanners.serializer())
        // Published together: the snapshot's word for the banners is the version of that list.
        assertEquals(s.banners?.version, b.version)
        assertEquals(3, b.people.size)
        for ((id, banner) in b.people) {
            assertTrue("$id: a picture's version is a fingerprint", Regex("^[0-9a-f]{12,16}$").matches(banner.v))
            assertTrue("$id: ${banner.type}", banner.type in setOf("image/jpeg", "image/png", "image/gif"))
        }
        // VillaNET 3.8.0: Alice's banner shows the middle third of its square picture, across
        // its whole width; Bob's and Dave's have no crop, and show their middle.
        assertEquals(ceo.villa.matty.plugins.villanet.CropRect(0.0, 0.3333, 1.0, 0.3333), ceo.villa.matty.plugins.villanet.bannerCropOf(b.people.getValue("alicebanner000000000000000").crop))
        assertNull(ceo.villa.matty.plugins.villanet.bannerCropOf(b.people.getValue("bobbanner00000000000000000").crop))
        assertNull(ceo.villa.matty.plugins.villanet.bannerCropOf(b.people.getValue("davebanner0000000000000000").crop))
        // A quiet server still says the word (of nobody's banners), and so does every push.
        assertTrue(decoded("snapshot-quiet.json", VnSnapshot.serializer()).banners?.version.orEmpty().isNotEmpty())
        val payload = Json.parseToJsonElement(fixture("event-streams.json")).jsonObject.getValue("payload").jsonPrimitive.content
        assertTrue(MattermostJson.decodeFromString(VnSnapshot.serializer(), payload).banners?.version.orEmpty().isNotEmpty())
    }

    @Test
    fun accountsSayWhoIsPlayingWhat() {
        val a = decoded("accounts.json", VnAccounts.serializer())
        assertTrue(a.steam.on && a.steam.fresh)
        assertEquals("ELDEN RING", steamGameOf(a, "acdave00000000000000000000"))
        // A private profile reads as offline, with no game.
        assertEquals(null, steamGameOf(a, "acerin00000000000000000000"))
        assertEquals("offline", a.people.getValue("acerin00000000000000000000").steam?.state)

        // What each is doing is the server's line, printed: Dave's stream is of the very game
        // Steam has him in, Boss is in a game with nothing on the air, and Bob, connected to
        // nothing, is there for what their own stream says. Erin, in no game, is live with a movie
        // tagged in What's on?: that is what she is streaming (the server's line since 3.6.1), and
        // it comes from the panel, by whatever name this server gives it.
        fun doing(kind: UserActivity.Kind, title: String, source: String) = PersonFacts(activity = UserActivity(kind, title, source))
        assertEquals(
            mapOf(
                "acdave00000000000000000000" to doing(UserActivity.Kind.Streaming, "ELDEN RING", "Steam"),
                "acboss00000000000000000000" to doing(UserActivity.Kind.Playing, "Factorio", "Steam"),
                "acbobk00000000000000000000" to doing(UserActivity.Kind.Streaming, "Hades", "Twitch"),
                "acerin00000000000000000000" to doing(UserActivity.Kind.Streaming, "Quantum of Solace", "Acme TV"),
            ),
            personsOf(null, a, canWatch = true, name = "Acme TV"),
        )
        // Only for a reader who may watch: a locked one is shown nothing of what anybody is doing.
        assertTrue(personsOf(null, a, canWatch = false).isEmpty())
    }

    @Test
    fun accessSaysWhetherThisServerTakesPushRegistrations() {
        // Off: a sentence to show, and no key.
        val off = decoded("access.json", VnAccess.serializer()).push!!
        assertFalse(off.on)
        assertEquals("off", off.state)
        assertTrue(off.reason.isNotBlank())
        assertEquals("", off.key)
        assertFalse(decoded("access-app-verified.json", VnAccess.serializer()).push!!.on)
        // On: the key the distributor is given as `vapid`, and the server as `key`.
        val on = decoded("access-push-on.json", VnAccess.serializer()).push!!
        assertTrue(on.on)
        assertTrue("an uncompressed P-256 point, as REGISTER carries it", UnifiedPush.isVapid(on.key))
        assertEquals("", on.reason)
        // A plugin before 4.1.0 says nothing, and nothing is assumed.
        assertNull(decoded("unlink.json", VnAccess.serializer()).push)
        assertTrue(pushOffered("4.1.0") && pushOffered("4.2.0") && pushOffered("5.0.0"))
        assertFalse(pushOffered(VnAccess.SINCE))
        assertFalse(pushOffered(""))
        assertFalse(pushOffered(null))
        assertEquals("4.1.0", PUSH_SINCE)
    }

    @Test
    fun theMePushRoutesAnswerWithHowTheRegistrationStands() {
        val pending = decoded("push-pending.json", VnPushState.serializer())
        assertEquals("pending", pending.state)
        assertEquals("push.example.net", pending.host)
        assertTrue(pending.since > 0)
        assertEquals("confirmed", decoded("push-confirmed.json", VnPushState.serializer()).state)
        assertEquals("a test is asked of a confirmed registration", "confirmed", decoded("push-test.json", VnPushState.serializer()).state)
        assertEquals("confirmed", decoded("push-state-confirmed.json", VnPushState.serializer()).state)
        assertEquals(VnPushState(state = "none"), decoded("push-state-none.json", VnPushState.serializer()))
        assertEquals(VnPushState(state = "refresh"), decoded("push-state-refresh.json", VnPushState.serializer()))
        val refused = decoded("push-state-refused.json", VnPushState.serializer())
        assertEquals("refused" to 403, refused.state to refused.status)
        assertEquals("push.example.net", refused.host)
        val failing = decoded("push-state-failing.json", VnPushState.serializer())
        assertEquals("failing" to "unreachable", failing.state to failing.kind)

        // Each refusal is the one body, with the code the app acts on.
        val off = decoded("refusal-push-off.json", VnError.serializer())
        assertEquals(VillaNetApi.PUSH_UNAVAILABLE, off.code)
        assertEquals("the same sentence /access gives", decoded("access.json", VnAccess.serializer()).push!!.reason, off.error)
        assertEquals(VillaNetApi.PUSH_KEY, decoded("refusal-push-key.json", VnError.serializer()).code)
        assertEquals(VillaNetApi.PUSH_CONFLICT, decoded("refusal-push-conflict.json", VnError.serializer()).code)
    }

    @Test
    fun everyPushPlaintextIsReadAsTheAppReadsIt() {
        fun parsed(name: String) = checkNotNull(PushPayload.parse(fixture(name).toByteArray())) { "$name does not parse" }
        val message = parsed("push-payload-message.json") as PushPayload.Message
        assertEquals(
            PushPayload.Message(
                user = "uid-dave", post = "post-1", channel = "chan-1", root = "root-1", team = "team-1", sender = "uid-bob",
                senderName = "bob", channelName = "Town Square", channelType = "O", text = "bob: lunch?", crt = true,
                idLoaded = false, fromWebhook = false, at = 1791230401000,
            ),
            message,
        )
        // Mattermost's own lead comes off the text when the sender is named beside it.
        val note = ceo.villa.matty.service.PushNotes.note("s1", "https://chat.example.test", "uid-dave", message, silent = false)
        assertEquals("lunch?", note.text)
        assertEquals("a reply in a collapsed thread is the thread's", "root-1", note.conversation)

        val idLoaded = parsed("push-payload-message-id-loaded.json") as PushPayload.Message
        assertTrue(idLoaded.idLoaded)
        assertEquals("in id-loaded mode the sender is not named", "", idLoaded.sender)

        assertEquals(PushPayload.Clear("uid-dave", "chan-1", "root-1", true, 1791230401000), parsed("push-payload-clear.json"))
        val hello = parsed("push-payload-hello.json") as PushPayload.Hello
        assertEquals("AAAAAAAAAAAAAAAAAAAAAA", hello.nonce)
        assertEquals(PushPayload.Test("uid-dave", 1791230401000), parsed("push-payload-test.json"))
        assertEquals(PushPayload.Ended("uid-dave", 1791230401000), parsed("push-payload-ended.json"))

        // `at` is Unix milliseconds: a second later, every one of them is fresh.
        val now = 1791230402000
        assertEquals(Arrival.Show(message, silent = false), Arrival.of(message, "uid-dave", now))
        assertEquals(Arrival.Confirm(hello.nonce), Arrival.of(hello, "uid-dave", now))
        assertEquals(Arrival.Tested, Arrival.of(parsed("push-payload-test.json"), "uid-dave", now))
        assertEquals(Arrival.Ended, Arrival.of(parsed("push-payload-ended.json"), "uid-dave", now))
        assertEquals("another account's push", Arrival.Drop, Arrival.of(message, "uid-bob", now))
    }

    @Test
    fun anOlderPluginIsToldFromThisOne() {
        assertTrue(olderThan("3.4.0", VnAccess.SINCE))
        // The pair before this one: contract 3, when the plugin was VillaNET under another id.
        assertTrue(olderThan("3.8.0", VnAccess.SINCE))
        assertTrue(olderThan("3.2.1", VnAccess.SINCE))
        assertFalse(olderThan(VnAccess.SINCE, VnAccess.SINCE))
        assertFalse(olderThan("3.10.0", "3.5.0"))
        assertFalse(olderThan("4.0", "3.5.0"))
        assertFalse(olderThan("3.5.0-rc1", "3.5.0"))
        // A version that can't be read isn't vouched for.
        assertTrue(olderThan("", VnAccess.SINCE))
        assertTrue(olderThan("latest", VnAccess.SINCE))
    }

    // ---- decoding with no surprises -----------------------------------------------------------

    /** What the server sends that the app deliberately has no place for, and why. */
    private val NOT_READ: Map<String, Map<String, String>> = run {
        val names = "the app names people from their Mattermost account (userId), and by the row's title otherwise"
        val badges = "the awards beside a name: not drawn in the app yet (a profile shows the whole record, from the boards)"
        val snapshot = mapOf(
            "people.*.awards" to badges,
            "streams[].awards" to "the badges on a stream's card: not drawn in the app yet",
            "streams[].linkSource" to "how a stream was matched to its person: only the web panel's footer says",
        )
        val trophies = "the web panel's Trophies card: the app shows the two airtime boards, and each person's record from their rows"
        val boards = mapOf(
            "allTime[].displayName" to names, "allTime[].username" to names,
            "month[].displayName" to names, "month[].username" to names,
            "allTimeTotal" to "how many are on the whole board: the app shows the rows it is sent",
            "monthTotal" to "how many are on the whole board: the app shows the rows it is sent",
            "monthMedals" to "$trophies (the months somebody won are a row of their record)",
        )
        mapOf(
            "snapshot-quiet.json" to snapshot,
            "snapshot.json" to snapshot,
            "boards-quiet.json" to boards,
            "boards.json" to boards + mapOf(
                "watchBoards" to trophies, "watchAllTime" to trophies, "watchMonth" to trophies,
                "watchAllTimeTotal" to trophies, "watchMonthTotal" to trophies,
                "bestClipper" to "$trophies (the Best Clipper's score is a row of their record)",
                "achievementKinds" to "$trophies (a step's name comes with the row it is on)",
                "achievements" to "$trophies (a person's steps are a row of their record)",
                "fans[].fanUsername" to names,
            ),
            "access.json" to mapOf(
                "artwork" to "whether the server has an emblem and a No Signal ghost: the app draws neither (it shows the icon, which always exists)",
            ),
            "access-app-verified.json" to mapOf(
                "artwork" to "whether the server has an emblem and a No Signal ghost: the app draws neither (it shows the icon, which always exists)",
            ),
            "access-push-on.json" to mapOf(
                "artwork" to "whether the server has an emblem and a No Signal ghost: the app draws neither (it shows the icon, which always exists)",
            ),
            "unlink.json" to mapOf("streamKey" to "the reader's stream name: the app has no use for it, and a secret that isn't in memory can't leak"),
            "accounts.json" to mapOf(
                "hytale" to "Hytale names: not shown in the app yet",
                "people.*.hytale" to "Hytale names: not shown in the app yet",
            ),
            "clip.json" to mapOf(
                "startAt" to "when in the stream the clip begins: only the web panel's editor uses it",
                "codecs" to "the player finds out what the file holds from the file",
                "bytes" to "the file's size: shown only where clips are downloaded and managed, on the web",
                "ofMyStream" to "why Delete is offered: the app goes by canDelete",
                "canReport" to "reporting a clip: not built in the app yet",
                "reported" to "reporting a clip: not built in the app yet",
            ),
        )
    }

    /** [name] decoded as the app decodes it, and checked by [holdsNoSurprises]. */
    private fun <T> decoded(name: String, ser: KSerializer<T>): T {
        val text = fixture(name)
        val value = MattermostJson.decodeFromString(ser, text)
        holdsNoSurprises(name, Json.parseToJsonElement(text), value, ser)
        return value
    }

    private val withDefaults = Json { encodeDefaults = true }

    /**
     * What the app made of an answer, written back out and laid beside the answer itself:
     *
     *  - Everything the app holds that the server didn't send must be empty (false, 0, "",
     *    nothing). A default that isn't is the app assuming something the server never said.
     *    (That was a real bug: previews defaulted to on, and the server leaves the field out when
     *    they're off.)
     *  - Everything both carry must be equal. Lenient decoding would otherwise quietly turn a
     *    field that changed type into its default.
     *
     * What the server sends and the app doesn't read is the app's choice, and isn't checked.
     */
    private fun <T> holdsNoSurprises(name: String, sent: JsonElement, value: T, ser: KSerializer<T>) {
        val unread = sortedSetOf<String>()
        compare("", sent, withDefaults.encodeToJsonElement(ser, value), unread)
        // And what the server sends that the app has no place for is a decision somebody made,
        // written down in NOT_READ with its reason: a field the server adds fails here until
        // the app either reads it or says why not.
        val expected = NOT_READ[name].orEmpty().keys.toSortedSet()
        assertEquals("$name: sent by the server and not read by the app (read them, or list them in NOT_READ with a reason)", expected, unread)
    }

    /** [path] names a field by shape, not by instance: list items are `[]`, and ids used as keys are `*`. */
    private fun compare(path: String, sent: JsonElement?, held: JsonElement, unread: MutableSet<String>) {
        val there = sent?.takeIf { it !is JsonNull }
        fun at(key: String) = (if (path.isEmpty()) "" else "$path.") + if (ID.matches(key)) "*" else key
        when (held) {
            is JsonObject -> {
                if (there != null) assertTrue("$path: the server sends ${there::class.simpleName}, the app holds an object", there is JsonObject)
                for ((key, mine) in held) {
                    val theirs = (there as? JsonObject)?.get(key)
                    if (theirs == null) assertTrue("${at(key)}: the server didn't send it, and the app assumes $mine", isEmpty(mine))
                    else compare(at(key), theirs, mine, unread)
                }
                (there as? JsonObject)?.keys?.filter { it !in held }?.forEach { unread += at(it) }
            }
            is JsonArray -> {
                if (there != null) assertTrue("$path: the server sends ${there::class.simpleName}, the app holds a list", there is JsonArray)
                val theirs = (there as? JsonArray).orEmpty()
                assertEquals("$path: how many", theirs.size, held.size)
                held.forEachIndexed { i, mine -> compare("$path[]", theirs[i], mine, unread) }
            }
            is JsonNull -> assertTrue("$path: the server sends $there, the app holds nothing", there == null)
            is JsonPrimitive -> if (there != null) {
                assertTrue("$path: the server sends ${there::class.simpleName}, the app holds a plain value", there is JsonPrimitive)
                assertEquals(path, (there as JsonPrimitive).content, held.content)
            }
        }
    }

    /** Keys that are somebody's id (a user, a stream, a clip) rather than a field's name. */
    private val ID = Regex("^(u-[a-z]+|ac[a-z]+0{10,}|[a-z]+banner0{10,}|[0-9a-f]{26,32}|[0-9a-f]{16})$")

    private fun isEmpty(e: JsonElement): Boolean = when (e) {
        is JsonNull -> true
        is JsonObject -> e.values.all(::isEmpty)
        is JsonArray -> e.isEmpty()
        is JsonPrimitive -> e.content in setOf("", "0", "false", "0.0")
    }
}
