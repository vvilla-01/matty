package ceo.villa.matty.plugins.villanet

import ceo.villa.matty.data.api.MattermostClient
import ceo.villa.matty.data.api.MattermostJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder

/** The id of the Matty plugin, the server plugin this module speaks for. */
const val VILLANET_ID = "ceo.villa.matty"

/**
 * The id the plugin had before 4.0.0, when it was VillaNET. Kept only for what was posted
 * under it: links that name it open the same clips and plans as links that name [VILLANET_ID].
 */
const val PREDECESSOR_ID = "ceo.villa.villanet"

/** The plugin's own name: what to call it until the server says, and when it says nothing. */
const val PRODUCT_NAME = "Matty"

/** What to call the plugin wherever a person reads it: the server's name for it, or [PRODUCT_NAME]. */
fun nameOf(access: VnAccess?): String = access?.name?.trim().orEmpty().ifEmpty { PRODUCT_NAME }

// ---- models (field names follow webapp/src/types.ts of the plugin) ---------------------------

/**
 * `GET /access`: the reader's access, and which plugin is answering. It is the one read about
 * the reader that every screen needs, and the one a player repeats while it plays. A link and an
 * unlink answer with the same two facts, so whoever made the change has no need to ask.
 *
 * (Who the reader is, their name and whether they're an admin come from the Mattermost session;
 * the plugin's `/me`, with the reader's stream key and the web panel's settings in it, is never
 * read: a secret that isn't in memory can't leak from it.)
 */
@Serializable
data class VnAccess(
    /** linked | letIn | locked | unknown */
    val access: String = "unknown",
    val configured: Boolean = false,
    /** Which client contract the server speaks (the plugin's contract/README.md). */
    val contract: Int = 0,
    /** The plugin's own version ("4.0.0"), to name in a notice. */
    val version: String = "",
    /**
     * What this server calls the plugin, to say wherever a person reads it; "" when it didn't say
     * (a link's answer doesn't). Read it through [nameOf], which supplies [PRODUCT_NAME].
     */
    val name: String = "",
    /** Whether this plugin is a signed release; null when it didn't say (a link's answer doesn't). See [VnRelease]. */
    val release: VnReleaseWord? = null,
    /** What the app check is set to here, and what it found for this session; null when it didn't say. */
    val app: VnAppWord? = null,
    /** Whether this server takes push registrations (from 4.1.0), with the key to register with; null when it didn't say. */
    val push: VnPushWord? = null,
) {
    val canWatch get() = access == "linked" || access == "letIn"
    val linked get() = access == "linked"

    companion object {
        /**
         * The contract revision this app is written against, and the first version of the plugin
         * that spoke it: the plugin's contract/contract.json, which VillaNetContractTest holds
         * these to.
         */
        const val CONTRACT = 4
        const val SINCE = "4.0.0"
    }
}

/** `release` in `GET /access`: what the plugin found of its own folder, which the app verifies for itself ([VnRelease]). */
@Serializable
data class VnReleaseWord(
    /** signed | unsigned | altered */
    val state: String = "",
    val version: String = "",
)

/**
 * `GET /release`: the release this plugin presents, the manifest and both signatures over it,
 * whenever it has a release folder. The app never takes [state] for it: see [releaseVerdictOf].
 */
@Serializable
data class VnRelease(
    val state: String = "",
    val version: String = "",
    /** With "altered": what the plugin found not as signed. */
    val reason: String = "",
    /** The manifest's text: its UTF-8 bytes (ASCII) are what is signed. */
    val manifest: String = "",
    /** RSASSA-PSS (SHA-512) over the manifest, base64. */
    val rsa: String = "",
    /** ML-DSA-87 over the manifest, base64. */
    val mldsa87: String = "",
)

/**
 * `app` in `GET /access`, for the session asking: [check] is the server's setting (off | report
 * | require), [state] what it found (verified | unverified | none), [reason] why, in its words.
 */
@Serializable
data class VnAppWord(
    val check: String = "",
    val state: String = "",
    val reason: String = "",
    /** With verified: only the development build of the plugin would pass it (an emulator, a debug build). */
    val development: Boolean = false,
    /** The app's version code the verdict was for, as Android attested it. */
    val versionCode: Long = 0,
)

/**
 * `push` in `GET /access` (from 4.1.0): [state] is "on" or "off" ("" when it didn't say); [key],
 * while on, the server's VAPID public key (87 characters), which the distributor is given as
 * `vapid` and the server as `key`; [reason], while off, the server's sentence to show.
 */
@Serializable
data class VnPushWord(val state: String = "", val key: String = "", val reason: String = "") {
    val on: Boolean get() = state == "on"
}

/** The first plugin version that sends pushes: before it, `push` and the `/me/push` routes don't exist. */
const val PUSH_SINCE = "4.1.0"

/** Whether the plugin at [version] can send pushes at all (whether it does is `push.state`). */
fun pushOffered(version: String?): Boolean = !version.isNullOrBlank() && !olderThan(version, PUSH_SINCE)

/** `PUT /me/push`: this session's UnifiedPush address, its two keys, and the key it was registered with. */
@Serializable
data class VnPushRegistration(val endpoint: String, val p256dh: String, val auth: String, val key: String) {
    override fun toString() = "VnPushRegistration"
}

/**
 * How this session's push registration stands, the answer of every `/me/push` route:
 * [state] none | pending | confirmed | refused | failing | refresh; [host] the push server's host;
 * [status] what it answered (with refused); [kind] how sending fails (with failing:
 * unreachable | address | certificate | answer); [since] when the state began (Unix ms).
 */
@Serializable
data class VnPushState(
    val state: String = "",
    val host: String = "",
    val status: Int = 0,
    val kind: String = "",
    val since: Long = 0,
)

/** `POST /app/challenge`: a challenge for this session, good for one answer until [expires] (ms). */
@Serializable
data class VnAppChallenge(val challenge: String = "", val expires: Long = 0)

/** `POST /app/attest`'s body. [claims] is the app's own word about itself, kept beside the verdict and never decided by. */
@Serializable
data class VnAttestRequest(val challenge: String, val origin: String, val chain: List<String>, val claims: VnAppClaims)

@Serializable
data class VnAppClaims(
    @kotlinx.serialization.SerialName("package") val packageName: String = "",
    val versionCode: Long = 0,
    val currentSigner: String = "",
    val history: List<String> = emptyList(),
    val hybrid: Boolean = false,
)

/** `POST /app/attest`'s answer: the verdict for this session, as `GET /access` will say it. */
@Serializable
data class VnAttestAnswer(
    val state: String = "",
    val reason: String = "",
    val development: Boolean = false,
    val versionCode: Long = 0,
    val expires: Long = 0,
)

/**
 * One person in the snapshot's index, with the server's rules already applied: what a screen
 * showing that person anywhere (a post, the member list, a profile) needs, in one lookup.
 */
@Serializable
data class VnPersonFacts(
    /** The handle of the card that stands for them while they're on the air; empty when they aren't. */
    val live: String = "",
    /** "open", or "muted" when every voice session of theirs is; empty when they aren't in voice. */
    val voice: String = "",
    /** The custom status VillaNET is holding for them: "live" or "birthday"; empty when it's their own. */
    val badge: String = "",
)

/**
 * `GET /accounts` (VillaNET 3.4.0): everyone's connected Steam (and Hytale) accounts in one answer,
 * for the member list, DMs and profiles. Linked and let-in readers only; locked ones get a 403.
 */
@Serializable
data class VnAccounts(
    /** Changes only when [people] does. */
    val v: String = "",
    val steam: VnSteamService = VnSteamService(),
    /** By Mattermost user id; empty, never absent, when nobody is connected. */
    val people: Map<String, VnPersonAccounts> = emptyMap(),
)

@Serializable
data class VnSteamService(
    val on: Boolean = false,
    /** A good read from Steam in the last five minutes: only then are statuses (and games) given. */
    val fresh: Boolean = false,
)

@Serializable
data class VnPersonAccounts(
    val steam: VnSteamStatus? = null,
    /** What they're doing, in a line, as VillaNET decides it once for every client. See [personsOf]. */
    val activity: VnActivity? = null,
)

/**
 * "Playing ELDEN RING", or "Streaming ELDEN RING" when their own stream is of that very game.
 * The server puts the game Steam reports and what the stream says together (by Steam's own id for
 * the game where it has one); the app prints the answer and compares nothing itself.
 */
@Serializable
data class VnActivity(
    /** playing | streaming */
    val kind: String = "",
    /** The game. Empty only with "playing", for a game Steam doesn't name. */
    val title: String = "",
    /** Who names it: steam | panel (the stream's own "What's on?") | twitch */
    val source: String = "",
)

@Serializable
data class VnSteamStatus(
    val name: String = "",
    /** Built by the server from the SteamID: https://steamcommunity.com/profiles/<id> */
    val url: String = "",
    /** online | busy | away | offline, or "" while Steam's read isn't fresh. */
    val state: String = "",
    /** Steam reports a game for a public profile. [game] may be empty even then. */
    val inGame: Boolean = false,
    val game: String? = null,
)

@Serializable
data class VnSnapshot(
    val streams: List<VnStream> = emptyList(),
    /** When to read the boards again ([VnBoards]): absent only before the server's first poll. */
    val boards: VnBoardsRef? = null,
    val schedule: List<VnPlan> = emptyList(),
    val voice: VnVoice? = null,
    /** By user id; always sent, empty when nobody is live, in voice or wearing a badge. See [personsOf]. */
    val people: Map<String, VnPersonFacts> = emptyMap(),
    val fetchedAt: Long = 0,
    val configured: Boolean = false,
    val error: String = "",
    /** Left out by the server when previews are off. */
    val livePreviews: Boolean = false,
    val twitchOn: Boolean = false,
    val twitchEmbed: Boolean = false,
    val clips: VnClipsInfo? = null,
    /** When to read the banners again ([VnBanners]): the one word the snapshot says of them. */
    val banners: VnBannersRef? = null,
)

/** All the snapshot says of the banners: a word that moves whenever anybody's is set, replaced or taken away. */
@Serializable
data class VnBannersRef(val version: String = "")

/**
 * `GET /banners` (VillaNET 3.7.0): who has a profile banner, and which picture it is. Read when a
 * profile opens and the snapshot's word ([VnBannersRef]) is not this copy's [version].
 */
@Serializable
data class VnBanners(
    val version: String = "",
    /** By user id; empty, never absent, when nobody has one. */
    val people: Map<String, VnBanner> = emptyMap(),
)

/** Somebody's banner: [v] is a fingerprint of its bytes, so a new picture is a new address. */
@Serializable
data class VnBanner(
    val v: String = "",
    /** image/jpeg | image/png | image/gif */
    val type: String = "",
    /**
     * From VillaNET 3.8.0, the part that shows, `[x, y, w, h]` in fractions of the picture: read
     * with [bannerCropOf], which takes anything it can't use as none (the middle shows). Kept as
     * it came, so a crop the app can't read never stops the list being read.
     */
    val crop: kotlinx.serialization.json.JsonElement? = null,
)

/** All the snapshot says of the boards: a word that moves when anything in them but a board's seconds does. */
@Serializable
data class VnBoardsRef(val version: String = "")

/**
 * `GET /boards` (VillaNET 3.6.0): the airtime boards and everybody's record. They rode the
 * snapshot until then; a board's seconds tick for as long as anybody is on the air, so the whole
 * snapshot went to every connection once a minute to say so. Now they are read when somebody
 * looks at a board or a profile, with the tag of the copy in hand, and the snapshot says in one
 * word ([VnBoardsRef]) when anything else in them has changed. Open to every signed-in reader.
 *
 * The app decides nothing about who holds what: the rows of a person's record come decided, in
 * order, each with its title and its facts ([VnAwardRow]), and the app gives them their artwork
 * and writes the figures the reader's way.
 */
@Serializable
data class VnBoards(
    val version: String = "",
    val allTime: List<VnLeader> = emptyList(),
    val month: List<VnLeader> = emptyList(),
    /** The month in progress, by name ("November 2026"); empty while the boards are off. */
    val monthLabel: String = "",
    /** Each streamer's biggest fan; empty, with [fansOn] false, while biggest fans are off. */
    val fans: List<VnFan> = emptyList(),
    val fansOn: Boolean = false,
    /** By user id, for everybody who holds anything; empty, never absent, when nobody does. */
    val people: Map<String, VnRecord> = emptyMap(),
)

/** Somebody's whole record, as their profile shows it, whatever they show beside their name. */
@Serializable
data class VnRecord(val rows: List<VnAwardRow> = emptyList())

/**
 * One row of a record. Only the facts of its own [kind] are sent, and none is formatted:
 * seconds are seconds, and months are "YYYY-MM", newest first.
 */
@Serializable
data class VnAwardRow(
    /** board | clipper | fan | achievements | months */
    val kind: String = "",
    /** alltime | month | snooper | clipper | fan:<streamer id> | achievements | past */
    val id: String = "",
    /** The place that tints the row and picks its artwork: 1 to 3, and 0 for the achievements. */
    val rank: Int = 0,
    val title: String = "",
    // A place on a board: which, how long, and what the time was spent on when it wasn't streaming.
    val board: String = "",
    val seconds: Long = 0,
    val doing: String = "",
    // The Best Clipper's score.
    val upvotes: Int = 0,
    val clips: Int = 0,
    // A biggest fan: of whom, and for how long.
    val streamerId: String = "",
    val streamer: String = "",
    val hours: Long = 0,
    /** The achievements, one step a family, in the catalogue's order. */
    val steps: List<VnAwardStep> = emptyList(),
    /** The months won, and second and third places, whichever there are. */
    val gold: List<String> = emptyList(),
    val tallies: List<VnAwardTally> = emptyList(),
)

@Serializable
data class VnAwardStep(val id: String = "", val tier: Int = 0, val name: String = "")

@Serializable
data class VnAwardTally(val rank: Int = 0, val months: List<String> = emptyList())

@Serializable
data class VnFan(val streamerId: String = "", val streamer: String = "", val fanId: String = "", val fan: String = "", val hours: Long = 0)

@Serializable
data class VnStream(
    val handle: String,
    val title: String = "",
    val motd: String = "",
    val paused: Boolean = false,
    val alwaysOn: Boolean = false,
    val viewers: Int = 0,
    val bitrate: Long = 0,
    val streamStart: String = "",
    val videoTracks: Int = 0,
    val audioTracks: Int = 0,
    val userId: String = "",
    val username: String = "",
    val displayName: String = "",
    val stillVersion: String = "",
    val pictureVersion: String = "",
    val thumbnailVersion: String = "",
    val previewVersion: String = "",
    val watchers: List<VnWatcher>? = null,
    val others: Int = 0,
    val nowPlaying: VnNowPlaying? = null,
    val source: String = "",
    val twitchLogin: String = "",
    val twitchCategory: String = "",
    val coveredBy: String = "",
) {
    val isTwitch get() = source == "twitch"
    val name get() = displayName.ifBlank { title.ifBlank { username } }
}

@Serializable
data class VnWatcher(val userId: String = "", val who: String = "")

@Serializable
data class VnNowPlaying(val category: String = "", val title: String = "", val media: VnMedia? = null)

@Serializable
data class VnMedia(
    val ref: String = "",
    val kind: String = "",
    val title: String = "",
    val year: String = "",
    val overview: String = "",
    val link: String = "",
    val source: String = "",
)

@Serializable
data class VnLeader(val rank: Int = 0, val title: String = "", val seconds: Long = 0, val userId: String = "", val twitchSeconds: Long = 0)

@Serializable
data class VnPlan(
    val id: String,
    val title: String = "",
    val startsAt: Long = 0,
    val category: String = "",
    val userId: String = "",
    val who: String = "",
    val media: VnMedia? = null,
    val joined: List<VnWatcher>? = null,
    /**
     * No room for anybody else, by the server's own count of the places taken: the Join it would
     * refuse. Never counted here from [joined], which leaves out people whose accounts are gone.
     */
    val full: Boolean = false,
)

@Serializable
data class VnVoice(val connected: Boolean = false, val people: List<VnVoicePerson> = emptyList())

@Serializable
data class VnVoicePerson(val name: String = "", val userId: String = "", val muted: Boolean = false)

@Serializable
data class VnClipsInfo(
    val enabled: Boolean = false,
    val lengthSeconds: Int = 0,
    val retentionDays: Int = 0,
    val count: Int = 0,
    /** When the newest clip was made (ms); 0 when there is none. Older than it was: a clip has gone. */
    val newest: Long = 0,
    /** Changes whenever a clip, a title, a picture or a count does: what the app's copies are good for. */
    val version: String = "",
)

@Serializable
data class VnPerson(val userId: String = "", val username: String = "", val displayName: String = "", val name: String = "")

@Serializable
data class VnTag(val category: String = "", val title: String = "")

@Serializable
data class VnClip(
    val id: String,
    val title: String = "",
    val createdAt: Long = 0,
    val expiresAt: Long = 0,
    val durationMs: Long = 0,
    val leadMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val thumb: String = "",
    val clipper: VnPerson = VnPerson(),
    val streamer: VnPerson = VnPerson(),
    val tag: VnTag? = null,
    val upvotes: Int = 0,
    val voted: Boolean = false,
    val mine: Boolean = false,
    val canVote: Boolean = false,
    val canDelete: Boolean = false,
    val canShare: Boolean = true,
    val canDownload: Boolean = false,
)

@Serializable
data class VnClips(val clips: List<VnClip> = emptyList())

@Serializable
data class VnClipCard(
    val id: String = "",
    val title: String = "",
    val createdAt: Long = 0,
    /** When the clip goes, as the server counts it (ms). 0 when it didn't say. */
    val expiresAt: Long = 0,
    val durationMs: Long = 0,
    val thumb: String = "",
    val clipper: VnPerson = VnPerson(),
    val streamer: VnPerson = VnPerson(),
    val tag: VnTag? = null,
    val upvotes: Int = 0,
    val voted: Boolean = false,
    val canVote: Boolean = false,
)

@Serializable
data class VnCards(val cards: Map<String, VnClipCard?> = emptyMap())

@Serializable
data class VnVote(val upvotes: Int = 0, val voted: Boolean = false)

/**
 * A refusal, the same from every route: [error] is the sentence to show (the server's wording is
 * the only wording), [code] a stable word for what was refused, and [retryAfterMs], with a refusal
 * that is about time, how long until asking again can work.
 */
@Serializable
data class VnError(val error: String = "", val code: String = "", val retryAfterMs: Long = 0)

/**
 * [serverMessage] is the server's own sentence, null when what came back wasn't a refusal of
 * VillaNET's (no answer, a proxy's page): only then does the app use words of its own.
 */
class VillaNetException(
    val code: String,
    message: String,
    val status: Int,
    override val serverMessage: String? = null,
    val retryAfterMs: Long = 0,
) : IOException(message), ceo.villa.matty.data.api.ServerError

/** The routes a server that requires the app check still answers a Matty that has not passed it. */
private val APP_CHECK_EXEMPT = setOf("/access", "/me", "/release", "/app/challenge", "/app/attest")

/**
 * Whether the app check gates the route at [path] (under `/api/v1`, query left off): every
 * route but the lock screen's, the release's and the two the app proves itself with.
 */
internal fun appCheckGated(path: String): Boolean = path.startsWith("/") && path !in APP_CHECK_EXEMPT

/**
 * Hears the app check's refusals of what is loaded with the session outside [VillaNetApi]: the
 * stills, thumbnails, pictures and banners the image loader fetches. Each [VillaNetApi] that
 * listens ([VillaNetApi.hearMedia]) hears those of its own plugin's routes.
 */
object PluginMediaRefusals {
    private val listening = java.util.concurrent.CopyOnWriteArrayList<VillaNetApi>()

    internal fun add(api: VillaNetApi) { listening.addIfAbsent(api) }
    internal fun remove(api: VillaNetApi) { listening.remove(api) }

    /** For the media client: a 403 from a listening plugin's routes is read for the app check. */
    val interceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        if (response.code == 403 && listening.isNotEmpty()) {
            val url = response.request.url
            listening.firstOrNull { it.owns(url) }?.mediaRefused(response.peekBody(4096).string())
        }
        response
    }
}

/**
 * REST client for the VillaNET plugin, riding on the Mattermost session. [onLocked] hears of every
 * answer that says this reader may not watch (on whichever thread the call ran): what the app
 * holds about them is then behind, and whoever owns that asks again. [onAppCheck] hears, on the
 * same terms, of every request refused for the app check ([APP_UNVERIFIED], with the server's
 * sentence) and of every answer to a route the check gates (null: see [appCheckGated]).
 */
class VillaNetApi(
    private val mm: MattermostClient,
    /** What this server calls the plugin just now, for the app's own words about an answer. */
    private val name: () -> String = { PRODUCT_NAME },
    private val onAppCheck: VillaNetApi.(refused: String?) -> Unit = {},
    private val onLocked: () -> Unit = {},
) {
    val base = "${mm.baseUrl}/plugins/$VILLANET_ID"
    private val api = "$base/api/v1"
    private val json = "application/json; charset=utf-8".toMediaType()

    private fun req(path: String) = Request.Builder().url(api + path)
        .header("X-Requested-With", "XMLHttpRequest")
        .apply { mm.token?.let { header("Authorization", "Bearer $it") } }

    private suspend fun <T> run(r: Request, ser: KSerializer<T>?): T? = exchange(r, ser).second

    /** The status code too, for the calls where 200 and 201 mean different things. */
    private suspend fun <T> exchange(r: Request, ser: KSerializer<T>?): Pair<Int, T?> = withContext(Dispatchers.IO) {
        mm.newCall(r).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw refusal(resp.code, body)
            answered(r)
            resp.code to (if (ser == null || body.isBlank()) null else MattermostJson.decodeFromString(ser, body))
        }
    }

    private val apiUrl = api.toHttpUrlOrNull()

    /** Whether [url] is one of this plugin's routes. */
    internal fun owns(url: HttpUrl): Boolean {
        val own = apiUrl ?: return false
        return url.scheme == own.scheme && url.host == own.host && url.port == own.port &&
            url.encodedPath.startsWith(own.encodedPath + "/")
    }

    /**
     * From now on, the refusals of the pictures loaded from this plugin are heard as its own
     * requests' are ([onAppCheck]); the function given back stops that.
     */
    fun hearMedia(): () -> Unit {
        PluginMediaRefusals.add(this)
        return { PluginMediaRefusals.remove(this) }
    }

    /** A picture of this plugin's refused 403 with [body]: heard if it was for the app check. */
    internal fun mediaRefused(body: String) {
        val err = runCatching { MattermostJson.decodeFromString(VnError.serializer(), body) }.getOrNull() ?: return
        if (err.code == APP_UNVERIFIED) onAppCheck(err.error.trim().ifBlank { null } ?: "${name()} returned 403")
    }

    /** [r] was answered: if the app check gates its route, the server holds this Matty good enough for it. */
    private fun answered(r: Request) {
        if (appCheckGated(r.url.encodedPath.substringAfter("/plugins/$VILLANET_ID/api/v1", ""))) onAppCheck(null)
    }

    /**
     * Every refusal is one body ([VnError]). [fallback] is what to say when the answer isn't one
     * of VillaNET's at all.
     */
    private fun refusal(status: Int, body: String, fallback: String = "${name()} returned $status"): VillaNetException {
        val err = runCatching { MattermostJson.decodeFromString(VnError.serializer(), body) }.getOrNull()
        val reason = err?.error?.trim()?.ifBlank { null }
        if (err?.code == "locked" || err?.code == "watch_only") onLocked()
        if (err?.code == APP_UNVERIFIED) onAppCheck(reason ?: fallback)
        return VillaNetException(err?.code.orEmpty(), reason ?: fallback, status, reason, err?.retryAfterMs ?: 0)
    }

    /** An answer, with the tag it came under. */
    class Tagged<T>(val value: T, val tag: String?)

    /**
     * A read the server can answer with "you already have it". [tag] is the tag the copy in hand
     * came under (from this route's ETag, or pushed with the streams event); null comes back when
     * that copy is still what the server would send (304), which costs a few bytes instead of
     * the whole document sent, decoded and compared to learn the same.
     */
    private suspend fun <T> conditional(path: String, tag: String?, ser: KSerializer<T>): Tagged<T>? = withContext(Dispatchers.IO) {
        val request = req(path).get().apply { if (!tag.isNullOrEmpty()) header("If-None-Match", tag) }.build()
        mm.newCall(request).execute().use { resp ->
            if (resp.code == 304) { answered(request); return@use null }
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw refusal(resp.code, body)
            answered(request)
            Tagged(MattermostJson.decodeFromString(ser, body), resp.header("ETag"))
        }
    }

    /**
     * A clip or plan share. VillaNET posts a new card (201), or, when the same person shared the
     * same thing into the same channel in the last minute, answers with that earlier post (200)
     * and posts nothing, even if that post has since been deleted.
     */
    private suspend fun share(path: String, body: JsonObject): VnShare {
        val (code, res) = exchange(req(path).post(body.toString().toRequestBody(json)).build(), VnShareResponse.serializer())
        return VnShare(res?.postId.orEmpty(), repeat = code == 200)
    }

    private suspend fun <T> get(path: String, ser: KSerializer<T>): T = run(req(path).get().build(), ser)!!
    private suspend fun <T> post(path: String, body: JsonObject, ser: KSerializer<T>?): T? =
        run(req(path).post(body.toString().toRequestBody(json)).build(), ser)

    /** The snapshot, or null when the one held under [tag] is still current. */
    suspend fun streams(tag: String? = null): Tagged<VnSnapshot>? = conditional("/streams", tag, VnSnapshot.serializer())
    /** Everyone's connected accounts, or null when the answer held under [tag] is still current. */
    suspend fun accounts(tag: String? = null): Tagged<VnAccounts>? = conditional("/accounts", tag, VnAccounts.serializer())

    /** The boards and everybody's record: read when somebody looks, with the tag of the copy in hand. */
    suspend fun boards(tag: String? = null): Tagged<VnBoards>? = conditional("/boards", tag, VnBoards.serializer())
    /** Who has a banner: read when a profile opens, with the tag of the copy in hand. */
    suspend fun banners(tag: String? = null): Tagged<VnBanners>? = conditional("/banners", tag, VnBanners.serializer())

    /**
     * Makes [bytes] the reader's banner, sent as they are under [type] (image/jpeg, image/png or
     * image/gif): the server checks them and keeps them unchanged, so a GIF stays animated.
     */
    internal suspend fun setBanner(bytes: ByteArray, type: String, crop: WireCrop? = null): VnBanner =
        // The part that shows (VillaNET 3.8.0), kept with the picture it arrives with.
        run(req("/me/banner" + (crop?.let { "?crop=${it.text}" } ?: "")).post(bytes.toRequestBody(type.toMediaType())).build(), VnBanner.serializer()) ?: VnBanner()

    /**
     * Changes the part of the reader's banner that shows, or clears it with null, without sending
     * the picture again (VillaNET 3.8.0; for a GIF, which the phone can't cut), as long as it is
     * still the picture [v] the part was chosen for. See [bannerCropBody].
     */
    internal suspend fun setBannerCrop(crop: WireCrop?, v: String?): VnBanner {
        val body = bannerCropBody(crop, v)
        return run(req("/me/banner/crop").put(body.toRequestBody(json)).build(), VnBanner.serializer()) ?: VnBanner()
    }

    /**
     * Somebody's banner as it is stored (for adjusting the part of a GIF that shows): loaded as a
     * picture, so with the session token only and no X-Requested-With, and no more than the
     * largest banner VillaNET keeps.
     */
    suspend fun bannerBytes(userId: String, v: String): ByteArray = withContext(Dispatchers.IO) {
        val url = bannerUrl(userId, v)
        val r = Request.Builder().url(url).apply { mediaHeaders(url).forEach { (k, value) -> header(k, value) } }.get().build()
        mm.newCall(r).execute().use { resp ->
            if (!resp.isSuccessful) throw refusal(resp.code, resp.body?.string().orEmpty())
            answered(r)
            val body = resp.body ?: throw IOException("No picture")
            val out = java.io.ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size() + n > BANNER_GIF_MAX_BYTES) throw IOException("The banner is larger than a banner can be")
                    out.write(buf, 0, n)
                }
            }
            out.toByteArray()
        }
    }

    /** Takes the reader's own banner away; never metered, and fine when there was none. */
    suspend fun removeMyBanner() { run<Unit>(req("/me/banner").delete().build(), null) }

    /** A system admin taking [userId]'s banner away. */
    suspend fun removeBanner(userId: String) { run<Unit>(req("/admin/banner?user=${enc(userId)}").delete().build(), null) }

    /** The reader's access, and which VillaNET this is. */
    suspend fun access(): VnAccess = get("/access", VnAccess.serializer())

    /** The release this plugin presents, with its manifest and both signatures when it has them. */
    suspend fun release(): VnRelease = get("/release", VnRelease.serializer())

    /** A challenge for this session's app check (no body). */
    suspend fun appChallenge(): VnAppChallenge =
        run(req("/app/challenge").post(ByteArray(0).toRequestBody(null)).build(), VnAppChallenge.serializer()) ?: VnAppChallenge()

    /** The attestation for that challenge, and the server's verdict on it. */
    suspend fun appAttest(body: VnAttestRequest): VnAttestAnswer =
        run(req("/app/attest").post(MattermostJson.encodeToString(VnAttestRequest.serializer(), body).toRequestBody(json)).build(), VnAttestAnswer.serializer()) ?: VnAttestAnswer()

    /**
     * Link (unlock) with a Broadcast Box bearer token. The token is sent once and never kept. The
     * answer is the reader's access now (and their stream name, which is not decoded).
     */
    suspend fun link(token: String): VnAccess = post("/me/link", buildJsonObject { put("token", token) }, VnAccess.serializer()) ?: VnAccess()
    /** Take the link back. Never metered, like every takeback: it works however many tokens were mistyped first. */
    suspend fun unlink(): VnAccess = run(req("/me/link").delete().build(), VnAccess.serializer()) ?: VnAccess()

    // ---- push notifications (from 4.1.0) ------------------------------------------------------

    /**
     * Gives the server this session's UnifiedPush address and keys: "confirmed" when it already
     * holds exactly these, "pending" when a `hello` is on its way to the address.
     */
    suspend fun pushRegister(body: VnPushRegistration): VnPushState =
        run(req("/me/push").put(MattermostJson.encodeToString(VnPushRegistration.serializer(), body).toRequestBody(json)).build(), VnPushState.serializer()) ?: VnPushState()

    /** Answers the `hello` with its nonce. A [VillaNetException] coded [PUSH_CONFLICT] is a hello since superseded. */
    suspend fun pushConfirm(nonce: String): VnPushState =
        post("/me/push/confirm", buildJsonObject { put("nonce", nonce) }, VnPushState.serializer()) ?: VnPushState()

    /** How this session's registration stands. */
    suspend fun pushState(): VnPushState = get("/me/push", VnPushState.serializer())

    /** Takes this session's registration away; never metered, and fine when there was none. */
    suspend fun pushForget(): VnPushState = run(req("/me/push").delete().build(), VnPushState.serializer()) ?: VnPushState()

    /** Asks for a `test` push to this session's confirmed registration. */
    suspend fun pushTest(): VnPushState =
        run(req("/me/push/test").post(ByteArray(0).toRequestBody(null)).build(), VnPushState.serializer()) ?: VnPushState()

    suspend fun clips(): List<VnClip> = get("/clips", VnClips.serializer()).clips
    /** One clip. A [VillaNetException] coded [CLIP_GONE] (410) is the only answer that means it's gone. */
    suspend fun clip(id: String): VnClip = get("/clips/clip?id=${enc(id)}", VnClip.serializer())
    suspend fun clipCards(ids: List<String>): Map<String, VnClipCard?> =
        get("/clips/cards?ids=${ids.joinToString(",")}", VnCards.serializer()).cards
    suspend fun vote(id: String, up: Boolean): VnVote =
        post("/clips/vote?id=$id", buildJsonObject { put("up", up) }, VnVote.serializer())!!
    suspend fun shareClip(id: String, channelId: String): VnShare =
        share("/clips/share?id=$id", buildJsonObject { put("channelId", channelId) })
    suspend fun joinPlan(id: String, join: Boolean) {
        post<Unit>("/schedule/join", buildJsonObject { put("id", id); put("join", join) }, null)
    }
    suspend fun sharePlan(id: String, channelId: String): VnShare =
        share("/schedule/share", buildJsonObject { put("id", id); put("channelId", channelId) })

    /**
     * WHEP offer/answer exchange, proxied by the plugin to Broadcast Box. Refused like any other
     * call: with the server's sentence and its code ("not_live", "rate_limited", "locked"…).
     */
    suspend fun whep(handle: String, offerSdp: String): String = withContext(Dispatchers.IO) {
        val r = req("/whep").header("X-Stream-Handle", handle)
            .post(offerSdp.toRequestBody("application/sdp".toMediaType())).build()
        mm.newCall(r).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw refusal(resp.code, body, "Could not start the stream (${resp.code}).")
            answered(r)
            body
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    fun stillUrl(s: VnStream) = "$api/stills?handle=${enc(s.handle)}&v=${enc(s.stillVersion)}"
    fun thumbnailUrl(s: VnStream) = "$api/channel/thumbnail?handle=${enc(s.handle)}&v=${enc(s.thumbnailVersion)}"
    fun pictureUrl(s: VnStream) = "$api/channel/picture?handle=${enc(s.handle)}&v=${enc(s.pictureVersion)}"
    fun twitchPreviewUrl(s: VnStream) = "$api/twitch/preview?handle=${enc(s.handle)}&v=${enc(s.previewVersion)}"
    /** Loaded as a picture (no X-Requested-With): see [mediaHeaders]. */
    fun bannerUrl(userId: String, v: String) = "$api/banner?user=${enc(userId)}&v=${enc(v)}"
    fun clipThumbUrl(id: String, v: String) = "$api/clips/thumb?id=$id&v=${enc(v)}"
    fun clipVideoUrl(id: String) = "$api/clips/video?id=$id"
    fun mediaImageUrl(ref: String, size: String = "thumb") = "$api/media/image?ref=${enc(ref)}&size=$size"
    val iconUrl get() = "$base/static/brand/icon"
    val logoUrl get() = "$base/static/brand/logo"

    /** For playing a clip: the session token and nothing else (TransportPolicy.mediaHeaders). */
    fun mediaHeaders(url: String): Map<String, String> = ceo.villa.matty.data.net.TransportPolicy.mediaHeaders(url, mm.origin, mm.token)

    companion object {
        private val BEARER = Regex("^bearer(?:\\s+|$)", RegexOption.IGNORE_CASE)
        private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)

        /** Trim, and take one leading "Bearer " off, as the server does. */
        fun cleanToken(raw: String): String {
            val t = raw.trim()
            val m = BEARER.find(t)
            return if (m != null && m.range.first == 0) t.substring(m.value.length).trim() else t
        }

        fun looksLikeToken(text: String) = UUID.matches(cleanToken(text))

        /** The code of a clip that has expired or been deleted (410): the only answer that means so. */
        const val CLIP_GONE = "clip_gone"

        /**
         * The code of a refusal of a Matty whose session has not passed the app check, while the
         * server requires it (403): the sentence says why.
         */
        const val APP_UNVERIFIED = "app_unverified"

        /** The push routes' refusal while push is off on the server (503): the sentence says why. */
        const val PUSH_UNAVAILABLE = "unavailable"

        /** A push registration made with a key that is not the server's (400): read `/access` again. */
        const val PUSH_KEY = "push_key"

        /** A confirm with a nonce that is not the latest, or too old (409): a superseded hello. */
        const val PUSH_CONFLICT = "conflict"

        /** The app's own words, for when the plugin (called [name] on this server) said nothing at all. */
        fun notAnswering(name: String) = "$name is not answering. Try again in a moment."

        /**
         * What to say for a link or an unlink that failed: the server's own sentence. It words
         * every refusal, and the app keeps no copy of its sentences; [notAnswering] is for no
         * sentence at all (offline, the plugin restarting, somebody else's error page).
         */
        fun lockCopy(serverText: String?, name: String): String = serverText?.trim().orEmpty().ifBlank { notAnswering(name) }
    }
}

@Serializable
data class VnShareResponse(val postId: String = "", val channelId: String = "")

/** [repeat]: nothing new was posted; VillaNET answered with the same share from the last minute. */
data class VnShare(val postId: String, val repeat: Boolean)
