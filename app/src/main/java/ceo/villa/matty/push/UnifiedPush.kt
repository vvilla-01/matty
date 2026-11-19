package ceo.villa.matty.push

import android.app.Activity
import android.app.BroadcastOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/**
 * The UnifiedPush connector's half of the Android protocol (AND_3.1; older distributors are
 * spoken to the way the official connector library speaks to them). Distributors are other apps:
 * everything here is a broadcast to one named package, or an activity it shows.
 */
object UnifiedPush {
    // ---- app -> distributor ----------------------------------------------------------------
    const val ACTION_REGISTER = "org.unifiedpush.android.distributor.REGISTER"
    const val ACTION_UNREGISTER = "org.unifiedpush.android.distributor.UNREGISTER"
    const val ACTION_MESSAGE_ACK = "org.unifiedpush.android.distributor.MESSAGE_ACK"
    /** Declared by a distributor that hands over `bytesMessage` (AND_2), asked for in `features`. */
    const val FEATURE_BYTES_MESSAGE = "org.unifiedpush.android.distributor.feature.BYTES_MESSAGE"

    // ---- distributor -> app ----------------------------------------------------------------
    const val ACTION_NEW_ENDPOINT = "org.unifiedpush.android.connector.NEW_ENDPOINT"
    const val ACTION_REGISTRATION_FAILED = "org.unifiedpush.android.connector.REGISTRATION_FAILED"
    const val ACTION_MESSAGE = "org.unifiedpush.android.connector.MESSAGE"
    const val ACTION_UNREGISTERED = "org.unifiedpush.android.connector.UNREGISTERED"
    const val ACTION_TEMP_UNAVAILABLE = "org.unifiedpush.android.connector.TEMP_UNAVAILABLE"
    /** The service a distributor binds to lift Matty to foreground importance while a push lands. */
    const val ACTION_RAISE_TO_FOREGROUND = "org.unifiedpush.android.connector.RAISE_TO_FOREGROUND"

    /** What [PushReceiver] hears, every one of them. */
    val RECEIVED = listOf(ACTION_MESSAGE, ACTION_UNREGISTERED, ACTION_NEW_ENDPOINT, ACTION_REGISTRATION_FAILED, ACTION_TEMP_UNAVAILABLE)

    // ---- extras ----------------------------------------------------------------------------
    const val EXTRA_TOKEN = "token"
    const val EXTRA_APPLICATION = "application"
    const val EXTRA_PI = "pi"
    const val EXTRA_FEATURES = "features"
    const val EXTRA_VAPID = "vapid"
    /** REGISTER's description of the registration, shown by some distributors. */
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_ID = "id"
    const val EXTRA_ENDPOINT = "endpoint"
    const val EXTRA_REASON = "reason"
    const val EXTRA_BYTES_MESSAGE = "bytesMessage"
    /** The migration target, as the specification names it ... */
    const val EXTRA_USE_DISTRIBUTOR = "useDistributor"
    /** ... and as the connector library and android-distributor actually send it. Both are read. */
    const val EXTRA_NEW_DISTRIBUTOR = "newDistributor"

    /** Where REGISTER's identity proof points: a package that does not exist. */
    const val DUMMY_APP = "org.unifiedpush.dummy_app"

    /** The link activity every AND_3 distributor offers (scheme `unifiedpush`, host `link`). */
    const val LINK_SCHEME = "unifiedpush"
    const val LINK_HOST = "link"
    val LINK: Uri get() = Uri.parse("$LINK_SCHEME://$LINK_HOST")

    // ---- limits (AND_3.1 "Definitions") -----------------------------------------------------
    const val MAX_TOKEN_BYTES = 100
    const val MAX_ID_BYTES = 100
    const val MAX_ENDPOINT_BYTES = 1000
    const val MAX_DESCRIPTION_BYTES = 100
    const val MAX_MESSAGE_BYTES = WebPush.MAX_BODY
    /** What one package name may be at most (Android's own limit is 255). */
    const val MAX_PACKAGE_CHARS = 255

    /** A token is not REGISTERed again this soon after its last REGISTER: the answer to that one is on its way. */
    const val REGISTER_SPACING_MS = 10_000L

    private val VAPID = Regex("^[A-Za-z0-9_-]{87}$")

    /** Whether [key] is a VAPID public key as REGISTER carries it: 87 base64url characters, an uncompressed point. */
    fun isVapid(key: String?): Boolean = key != null && VAPID.matches(key) && B64.decode(key)?.let { it.size == 65 && it[0] == 4.toByte() } == true

    /** Whether [description] may go in REGISTER's `message`. */
    fun isDescription(description: String): Boolean = description.toByteArray(Charsets.UTF_8).size <= MAX_DESCRIPTION_BYTES

    // ---- finding distributors ---------------------------------------------------------------

    /** The packages that take REGISTER (installed and set up distributors), never Matty itself. */
    fun distributors(context: Context): List<String> = takingRegister(
        context.packageManager.queryBroadcastReceivers(Intent(ACTION_REGISTER), PackageManager.ResolveInfoFlags.of(0))
            .mapNotNull { it.activityInfo }.map { it.packageName to it.exported },
        context.packageName,
    )

    /** From the REGISTER receivers [found] (package, exported), the packages that are distributors: exported ones only, never [self]. */
    fun takingRegister(found: List<Pair<String?, Boolean>>, self: String): List<String> =
        found.filter { it.second }.mapNotNull { it.first }.filter { it != self }.distinct()

    // ---- who a distributor is ----------------------------------------------------------------

    /** Where the chosen distributor stands. */
    enum class Standing {
        /** It takes REGISTER and is the app that was chosen. */
        USABLE,
        /** Installed and signed as when it was chosen, but taking no REGISTER now: it needs setting up (its receiver is off). */
        NOT_SET_UP,
        /** Not installed, or signed by somebody else. */
        GONE,
    }

    /** [Standing] from whether the chosen package [takesRegister], is [installed], and is [signed] as when it was chosen. */
    fun standing(takesRegister: Boolean, installed: Boolean, signed: Boolean): Standing = when {
        !installed || !signed -> Standing.GONE
        takesRegister -> Standing.USABLE
        else -> Standing.NOT_SET_UP
    }

    /**
     * [pkg]'s signing certificates as they are now ([signers]), or null when they cannot be read
     * (not installed).
     */
    fun signersOf(context: Context, pkg: String): String? = runCatching {
        val info = context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }?.takeIf { it.isNotEmpty() }?.let(::signers)
    }.getOrNull()

    /** Whether [pkg] is installed and signed with every one of [remembered] ([signers]); a key it rotated to counts. */
    fun signedAs(context: Context, pkg: String, remembered: String): Boolean = sameSigners(remembered) { sha256 ->
        runCatching { context.packageManager.hasSigningCertificate(pkg, sha256, PackageManager.CERT_INPUT_SHA256) }.getOrDefault(false)
    }

    /** Certificates as remembered: each one's SHA-256 in hex, in order, joined with ",". */
    fun signers(certificates: List<ByteArray>): String =
        certificates.joinToString(",") { cert -> java.security.MessageDigest.getInstance("SHA-256").digest(cert).joinToString("") { "%02x".format(it) } }

    /** Whether [has] says yes to each SHA-256 in [remembered]; nothing remembered, or something unreadable, is no. */
    fun sameSigners(remembered: String, has: (ByteArray) -> Boolean): Boolean {
        val digests = remembered.split(",").map { hex -> hex.takeIf { it.length == 64 }?.chunked(2)?.mapNotNull { it.toIntOrNull(16)?.toByte() } }
        if (digests.isEmpty() || digests.any { it == null || it.size != 32 }) return false
        return digests.all { has(it!!.toByteArray()) }
    }

    /** The package that opens `unifiedpush://link` without asking, if the person set one (the system's chooser is not one). */
    fun linkDefault(context: Context): String? =
        context.packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, LINK), PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            ?.activityInfo?.packageName

    /** How a distributor is found when push is switched on and none is remembered. */
    sealed interface Choice {
        /** None is installed. */
        data object None : Choice
        /** None takes REGISTER, but the chosen one is installed and needs setting up: open it. */
        data class Open(val distributor: String) : Choice
        /** Ask [distributor]'s link activity, the default for `unifiedpush://link`, for a result. */
        data class Link(val distributor: String) : Choice
        /** Only one is installed: it is the one. */
        data class Only(val distributor: String) : Choice
        /** Several and no default: the person picks. */
        data class Pick(val distributors: List<String>) : Choice
    }

    /**
     * [Choice] from what [linkDefault] and [distributors] found; [notSetUp] is the chosen
     * distributor when it is installed, signed as chosen, and takes no REGISTER now.
     */
    fun choose(linkDefault: String?, installed: List<String>, notSetUp: String? = null): Choice = when {
        installed.isEmpty() -> notSetUp?.let { Choice.Open(it) } ?: Choice.None
        linkDefault != null && linkDefault in installed -> Choice.Link(linkDefault)
        installed.size == 1 -> Choice.Only(installed.single())
        else -> Choice.Pick(installed)
    }

    /**
     * The migration target an UNREGISTERED names, when it is an installed distributor other than
     * the one that sent it; otherwise null (a plain UNREGISTERED).
     */
    fun migrationTarget(named: String?, current: String, installed: List<String>): String? =
        named?.takeIf { it.isNotBlank() && it != current && it in installed }

    // ---- the link activity ------------------------------------------------------------------

    /** Which link activity to start. */
    sealed interface Link {
        /** That distributor's own. */
        data class To(val distributor: String) : Link
        /** The system's chooser over every one installed: never the silent default. */
        data object Choose : Link
    }

    /**
     * Starts a distributor's link activity for a result. The answer is the distributor's package,
     * read from the PendingIntent it returns (its creator), or null when the person cancelled or
     * the answer carried no proof; check it against [distributors] before using it.
     */
    class LinkContract : ActivityResultContract<Link, String?>() {
        override fun createIntent(context: Context, input: Link): Intent {
            val view = Intent(Intent.ACTION_VIEW, LINK)
            return when (input) {
                is Link.To -> view.setPackage(input.distributor)
                Link.Choose -> Intent.createChooser(view, null)
            }
        }

        override fun parseResult(resultCode: Int, intent: Intent?): String? =
            linkResult(resultCode, intent?.getParcelableExtra(EXTRA_PI, PendingIntent::class.java)?.creatorPackage)
    }

    /** The distributor a link activity's answer names: only an OK with a creator package names one. */
    fun linkResult(resultCode: Int, creator: String?): String? =
        creator?.takeIf { resultCode == Activity.RESULT_OK && it.isNotBlank() && it.length <= MAX_PACKAGE_CHARS }

    // ---- sending ----------------------------------------------------------------------------

    /**
     * REGISTER [token] at [distributor] with the server's [vapid] key and [description], both
     * re-sent byte for byte on every REGISTER of the same token. False when either is not one
     * the protocol allows (nothing is sent then).
     */
    fun register(context: Context, distributor: String, token: String, vapid: String, description: String): Boolean {
        if (!isVapid(vapid) || !isDescription(description) || !isToken(token)) return false
        send(context, identified(context, Intent(ACTION_REGISTER).setPackage(distributor), token).apply {
            putExtra(EXTRA_FEATURES, arrayOf(FEATURE_BYTES_MESSAGE))
            putExtra(EXTRA_VAPID, vapid)
            putExtra(EXTRA_MESSAGE, description)
        })
        return true
    }

    /** UNREGISTER [token] at [distributor]. The token is forgotten whether or not it answers. */
    fun unregister(context: Context, distributor: String, token: String) {
        if (!isToken(token)) return
        send(context, identified(context, Intent(ACTION_UNREGISTER).setPackage(distributor), token))
    }

    /** MESSAGE_ACK: sent whenever a NEW_ENDPOINT or MESSAGE carried an `id`. */
    fun ack(context: Context, distributor: String, token: String, id: String) {
        if (!isToken(token) || id.isEmpty() || id.toByteArray(Charsets.UTF_8).size > MAX_ID_BYTES) return
        send(context, Intent(ACTION_MESSAGE_ACK).setPackage(distributor).putExtra(EXTRA_TOKEN, token).putExtra(EXTRA_ID, id))
    }

    fun isToken(token: String?): Boolean = !token.isNullOrEmpty() && token.toByteArray(Charsets.UTF_8).size <= MAX_TOKEN_BYTES

    /**
     * The token and who is asking: the package (AND_2), and an immutable PendingIntent whose
     * creator is Matty (AND_3 before Android 14). The broadcast itself shares Matty's identity.
     */
    private fun identified(context: Context, intent: Intent, token: String): Intent = intent
        .putExtra(EXTRA_TOKEN, token)
        .putExtra(EXTRA_APPLICATION, context.packageName)
        .putExtra(EXTRA_PI, PendingIntent.getBroadcast(context, 0, Intent(DUMMY_APP).setPackage(DUMMY_APP), PendingIntent.FLAG_IMMUTABLE))

    private fun send(context: Context, intent: Intent) {
        context.sendBroadcast(intent, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
    }
}
