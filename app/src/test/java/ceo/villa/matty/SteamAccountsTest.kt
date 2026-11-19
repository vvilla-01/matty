package ceo.villa.matty

import ceo.villa.matty.data.api.MattermostJson
import ceo.villa.matty.plugins.UserActivity
import ceo.villa.matty.plugins.villanet.VnAccounts
import ceo.villa.matty.plugins.villanet.VnActivity
import ceo.villa.matty.plugins.villanet.VnNowPlaying
import ceo.villa.matty.plugins.villanet.VnPersonAccounts
import ceo.villa.matty.plugins.villanet.VnPersonFacts
import ceo.villa.matty.plugins.villanet.VnSnapshot
import ceo.villa.matty.plugins.villanet.VnSteamService
import ceo.villa.matty.plugins.villanet.VnSteamStatus
import ceo.villa.matty.plugins.villanet.VnStream
import ceo.villa.matty.plugins.villanet.activityOf
import ceo.villa.matty.plugins.villanet.personsOf
import ceo.villa.matty.plugins.villanet.steamGameOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * VillaNET's GET /accounts: everyone's Steam, and what each person is doing. The contract's own
 * fixture of it is held in [VillaNetContractTest]; these are the cases around it.
 */
class SteamAccountsTest {
    private val sample = """
        {"v": "3f2a9c01b7de",
         "steam":  {"on": true, "fresh": true, "at": 1794430000000},
         "hytale": {"on": true},
         "people": {
           "dave": {"steam":  {"name": "Dave", "url": "https://steamcommunity.com/profiles/76561197960287930",
                               "state": "online", "inGame": true, "game": "ELDEN RING"},
                    "hytale": {"name": "Steve_Hytale"}},
           "bob":  {"hytale": {"name": "Bob_the_Builder"}},
           "kit":  {"steam": {"name": "Kit", "url": "https://steamcommunity.com/profiles/76561197960287931", "state": "away"}},
           "rupert":  {"steam": {"name": "Rupert", "url": "https://steamcommunity.com/profiles/76561197960287932", "state": "busy", "inGame": true, "game": ""}}
         }}
    """.trimIndent()

    private fun decode(json: String) = MattermostJson.decodeFromString(VnAccounts.serializer(), json)

    @Test
    fun decodesTheDocumentedAnswer() {
        val a = decode(sample)
        assertEquals("3f2a9c01b7de", a.v)
        assertEquals(true, a.steam.on)
        assertEquals("ELDEN RING", a.people.getValue("dave").steam?.game)
        assertNull(a.people.getValue("bob").steam)
    }

    @Test
    fun onlyAGameCountsNotOnlineOrAway() {
        val a = decode(sample)
        assertEquals("ELDEN RING", steamGameOf(a, "dave"))
        // Online/away/busy but not in a game: nothing to show.
        assertNull(steamGameOf(a, "kit"))
        // In a game Steam doesn't name.
        assertEquals("", steamGameOf(a, "rupert"))
        // No Steam, unknown person, no answer.
        assertNull(steamGameOf(a, "bob"))
        assertNull(steamGameOf(a, "nobody"))
        assertNull(steamGameOf(null, "dave"))
    }

    @Test
    fun steamSwitchedOffShowsNothing() {
        val off = decode(sample.replace("\"steam\":  {\"on\": true, \"fresh\": true, \"at\": 1794430000000}", "\"steam\": {\"on\": false}"))
        assertEquals(false, off.steam.on)
        assertNull(steamGameOf(off, "dave"))
    }

    @Test
    fun anEmptyServerDecodes() {
        val a = decode("""{"v":"e3b0c44298fc","steam":{"on":false},"hytale":{"on":false},"people":{}}""")
        assertEquals(emptyMap<String, Any>(), a.people)
    }

    // ---- what somebody is doing: VillaNET's to say, the app's to print -------------------------

    private fun accountsWith(vararg people: Pair<String, VnPersonAccounts>, steamOn: Boolean = true) =
        VnAccounts(v = "x", steam = VnSteamService(on = steamOn, fresh = steamOn), people = people.toMap())

    @Test
    fun theLineUnderANameIsTheServersOwn() {
        val a = accountsWith(
            "dave" to VnPersonAccounts(steam = VnSteamStatus(name = "Dave", state = "online", inGame = true, game = "ELDEN RING"), activity = VnActivity("streaming", "ELDEN RING", "steam")),
            "kit" to VnPersonAccounts(activity = VnActivity("streaming", "Hades", "twitch")),
            "rupert" to VnPersonAccounts(steam = VnSteamStatus(name = "Rupert", state = "busy", inGame = true, game = ""), activity = VnActivity("playing", "", "steam")),
            "bob" to VnPersonAccounts(steam = VnSteamStatus(name = "Bob", state = "online")),
            "erin" to VnPersonAccounts(activity = VnActivity("streaming", "Factorio", "panel")),
        )
        val persons = personsOf(null, a, canWatch = true, name = "Acme TV")
        assertEquals(UserActivity(UserActivity.Kind.Streaming, "ELDEN RING", "Steam"), persons["dave"]?.activity)
        // Connected to nothing: there for what their own stream says.
        assertEquals(UserActivity(UserActivity.Kind.Streaming, "Hades", "Twitch"), persons["kit"]?.activity)
        // Named in the panel's own What's on?: said to come from what this server calls the panel.
        assertEquals(UserActivity(UserActivity.Kind.Streaming, "Factorio", "Acme TV"), persons["erin"]?.activity)
        // In a game Steam doesn't name.
        assertEquals(UserActivity(UserActivity.Kind.Playing, "", "Steam"), persons["rupert"]?.activity)
        // Online and not in a game: nothing to say, so not there at all.
        assertNull(persons["bob"])
    }

    @Test
    fun theAppDecidesNothingItself() {
        // In ELDEN RING on Steam, and a card on the air that says ELDEN RING: with no line from
        // the server, the app says nothing. It doesn't put the two together (that rule lives on
        // the server, which matches by Steam's own id for the game), so it can't disagree with
        // the web panel about it.
        val accounts = accountsWith("dave" to VnPersonAccounts(steam = VnSteamStatus(name = "Dave", state = "online", inGame = true, game = "ELDEN RING")))
        val snapshot = VnSnapshot(
            streams = listOf(VnStream(handle = "m", userId = "dave", nowPlaying = VnNowPlaying(category = "game", title = "ELDEN RING"), twitchCategory = "ELDEN RING")),
            people = mapOf("dave" to VnPersonFacts(live = "m")),
        )
        val dave = personsOf(snapshot, accounts, canWatch = true).getValue("dave")
        assertTrue(dave.live)
        assertNull(dave.activity)
    }

    @Test
    fun theLineIsOnlyForAReaderWhoMayWatch() {
        val a = accountsWith("dave" to VnPersonAccounts(activity = VnActivity("playing", "ELDEN RING", "steam")))
        val snapshot = VnSnapshot(people = mapOf("dave" to VnPersonFacts(live = "m")))
        assertEquals(UserActivity(UserActivity.Kind.Playing, "ELDEN RING", "Steam"), personsOf(snapshot, a, canWatch = true).getValue("dave").activity)
        // Whatever was fetched before the lock: the ring stays (it's in the snapshot, which
        // everybody is sent), the line goes.
        val locked = personsOf(snapshot, a, canWatch = false).getValue("dave")
        assertTrue(locked.live)
        assertNull(locked.activity)
        assertTrue(personsOf(null, a, canWatch = false).isEmpty())
    }

    @Test
    fun theStreamsOwnLineNeedsNoSteam() {
        // Steam switched off on the server: what a stream says it's showing is still said.
        val a = accountsWith("kit" to VnPersonAccounts(activity = VnActivity("streaming", "Hades", "panel")), steamOn = false)
        assertEquals(UserActivity(UserActivity.Kind.Streaming, "Hades", "Matty"), personsOf(null, a, canWatch = true)["kit"]?.activity)
    }

    @Test
    fun aKindThisAppDoesNotKnowIsNothingToShow() {
        assertNull(activityOf(VnActivity("spectating", "ELDEN RING", "steam")))
        assertNull(activityOf(VnActivity("", "ELDEN RING", "steam")))
        // A source it doesn't know is still a line, without "on …".
        assertEquals(UserActivity(UserActivity.Kind.Playing, "ELDEN RING", ""), activityOf(VnActivity("playing", "ELDEN RING", "epic")))
        // The source the plugin said before 4.0.0 is one this app no longer hears from anybody.
        assertEquals(UserActivity(UserActivity.Kind.Playing, "ELDEN RING", ""), activityOf(VnActivity("playing", "ELDEN RING", "villanet"), "Acme TV"))
        assertEquals(UserActivity(UserActivity.Kind.Streaming, "Hades", "Acme TV"), activityOf(VnActivity("streaming", "Hades", "panel"), "Acme TV"))
        assertEquals("Playing ELDEN RING on Steam", ceo.villa.matty.ui.home.activityLabel(activityOf(VnActivity("playing", "ELDEN RING", "steam"))!!))
        assertEquals("Streaming Hades", ceo.villa.matty.ui.home.activityLabel(activityOf(VnActivity("streaming", "Hades", "twitch"))!!))
        assertEquals("In a game", ceo.villa.matty.ui.home.activityLabel(activityOf(VnActivity("playing", "", "steam"))!!))
    }
}
