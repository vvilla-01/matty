package ceo.villa.matty.plugins.villanet

import ceo.villa.matty.verify.ReleasePins
import ceo.villa.matty.verify.releaseSignatureProblems
import ceo.villa.matty.verify.sha256Colons
import java.util.Base64

// The plugin's signed release, as the app checks it for itself (the plugin's contract/README.md,
// "A signed release"): the server presents its release's manifest and the two signatures over
// it, and the app verifies both against Matty's own certificates, which it holds itself. What
// this shows is which release a server presents, not what code it runs: a server program that
// was altered can present a genuine release's manifest. The plugin goes on working whatever
// the answer is.

/** What the app found of the release a server presents. */
sealed interface ReleaseVerdict {
    /** The plugin's version, as the server reports it. */
    val version: String

    /** Both signatures verify, and the manifest is of this plugin at [version]. [manifestSha256] is the release's identity. */
    data class Signed(override val version: String, val manifestSha256: String) : ReleaseVerdict

    /** The server says it is not a signed release (a development build, or somebody's own). */
    data class Unsigned(override val version: String) : ReleaseVerdict

    /** Anything else, and [reason] says what. */
    data class NotVerified(override val version: String, val reason: String) : ReleaseVerdict
}

/** The header lines of a manifest, in order (the plugin's server/release). */
internal const val MANIFEST_HEADER = "matty-release-manifest 1"
private const val MANIFEST_MAX_BYTES = 1 shl 20
private const val MANIFEST_MAX_FILES = 10_000
private val VERSION = Regex("^(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})$")
private val PLATFORM = Regex("^[a-z0-9]{1,16}-[a-z0-9]{1,16}$")
private val COUNT = Regex("^(0|[1-9][0-9]{0,8})$")
private val ENTRY = Regex("^([0-9a-f]{64}) (0|[1-9][0-9]{0,15}) (.+)$")
private val PATH = Regex("^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$")

/** A manifest that read: its version and platforms. The files it lists are the plugin's to check on disk. */
internal data class ReleaseManifest(val version: String, val platforms: List<String>, val files: Int)

/**
 * Reads a manifest strictly, as the plugin itself does: printable ASCII and line feeds only,
 * ending in one; the header lines exactly in order, of `ceo.villa.matty`, kind `plugin`; a plain
 * version; the platforms sorted; the count equal to the files listed; and each file a lower-case
 * SHA-256, a size and a plain relative path, sorted, each path once. Throws with what is wrong.
 */
internal fun parseReleaseManifest(raw: ByteArray): ReleaseManifest {
    require(raw.isNotEmpty() && raw.size <= MANIFEST_MAX_BYTES && raw.last() == '\n'.code.toByte()) { "the manifest is empty, too large, or does not end in a line feed" }
    require(raw.all { it == '\n'.code.toByte() || it in 0x20..0x7e }) { "the manifest holds something other than printable ASCII and line feeds" }
    val lines = String(raw, Charsets.US_ASCII).dropLast(1).split('\n')
    require(lines.size >= 7) { "the manifest is too short" }
    require(lines[0] == MANIFEST_HEADER) { "the manifest's first line is not \"$MANIFEST_HEADER\"" }
    fun field(i: Int, name: String): String {
        require(lines[i].startsWith("$name ")) { "line ${i + 1} of the manifest is not \"$name\"" }
        return lines[i].removePrefix("$name ")
    }
    require(field(1, "product") == VILLANET_ID) { "the manifest is not of $VILLANET_ID" }
    require(field(2, "kind") == "plugin") { "the manifest is not of a plugin" }
    val version = field(3, "version")
    require(VERSION.matches(version)) { "the manifest's version is not a plain version" }
    val platforms = field(4, "platform").split(',')
    platforms.forEachIndexed { i, p -> require(PLATFORM.matches(p) && (i == 0 || p > platforms[i - 1])) { "the manifest's platforms are not sorted names" } }
    val count = field(5, "files")
    require(COUNT.matches(count)) { "the manifest's file count is not a number" }
    val n = count.toInt()
    require(n in 1..MANIFEST_MAX_FILES && n == lines.size - 6) { "the manifest says $n files and lists ${lines.size - 6}" }
    var last: String? = null
    lines.drop(6).forEachIndexed { i, line ->
        val m = requireNotNull(ENTRY.matchEntire(line)) { "line ${7 + i} of the manifest is not a file" }
        val path = m.groupValues[3]
        require(PATH.matches(path) && path.split('/').none { it == "." || it == ".." } && path != "release" && !path.startsWith("release/")) {
            "line ${7 + i} of the manifest is not a plain path outside the release folder"
        }
        require(last == null || path > last!!) { "line ${7 + i} of the manifest is out of order or listed twice" }
        requireNotNull(m.groupValues[2].toLongOrNull()) { "line ${7 + i} of the manifest has a size too large" }
        last = path
    }
    return ReleaseManifest(version, platforms, n)
}

/**
 * What [answer] (`GET /release`) shows of the release of the plugin that reports [version] in
 * `GET /access`. Signed only when the server says so, both signatures verify against [pins]
 * over the manifest exactly as sent (before anything in it is read), and the manifest is of
 * this plugin at [version]. Never signed on one signature.
 */
internal fun releaseVerdictOf(answer: VnRelease, version: String, pins: () -> ReleasePins): ReleaseVerdict {
    fun failed(reason: String) = ReleaseVerdict.NotVerified(version, reason)
    val sent = answer.manifest.isNotEmpty() || answer.rsa.isNotEmpty() || answer.mldsa87.isNotEmpty()
    if (answer.state == RELEASE_UNSIGNED && !sent) return ReleaseVerdict.Unsigned(version)
    if (answer.state == RELEASE_UNSIGNED) return failed("the server says it is not signed, and sent signatures")
    if (answer.state != RELEASE_SIGNED && answer.state != RELEASE_ALTERED) return failed("the server's answer does not say")
    if (!sent) return failed("the server sent no signatures")
    val manifest = answer.manifest.toByteArray(Charsets.UTF_8)
    val rsa = base64(answer.rsa)
    val mldsa = base64(answer.mldsa87)
    val held = try {
        pins()
    } catch (e: Exception) {
        return failed("this phone could not read Matty's certificates")
    }
    val problems = releaseSignatureProblems(held, manifest, rsa, mldsa)
    if (problems.isNotEmpty()) return failed(problems.joinToString("; "))
    val read = try {
        parseReleaseManifest(manifest)
    } catch (e: IllegalArgumentException) {
        return failed(e.message ?: "the manifest does not read")
    }
    if (read.version != version) return failed("the signed release is ${read.version}, but the server reports $version")
    // Genuine signatures, and the server's own word that its files are not what they sign.
    if (answer.state == RELEASE_ALTERED) return failed("the server says its files are not as signed" + answer.reason.trim().let { if (it.isEmpty()) "" else ": $it" })
    if (answer.version.isNotEmpty() && answer.version != version) return failed("the server reports $version here and ${answer.version} there")
    return ReleaseVerdict.Signed(version, sha256Colons(manifest))
}

private fun base64(s: String): ByteArray? = if (s.isEmpty()) null else try {
    Base64.getDecoder().decode(s)
} catch (e: IllegalArgumentException) {
    null
}

/** What a verdict about a release is held under: the server, the version and what `/access` said of it. */
internal fun releaseKey(base: String, version: String, state: String): String = "$base|$version|$state"

/** Lets go of everything held by [releaseKey] for the server at [base], so its release is looked at again. */
internal fun forgetReleases(held: MutableMap<String, *>, base: String) {
    held.keys.removeAll { it.startsWith("$base|") }
}

internal const val RELEASE_SIGNED = "signed"
internal const val RELEASE_UNSIGNED = "unsigned"
internal const val RELEASE_ALTERED = "altered"

/** The line where the plugin is listed and in the panel's menu. About the software, so it says "Matty Plugin" whatever the server calls it. */
internal fun releaseWords(v: ReleaseVerdict): String = when (v) {
    is ReleaseVerdict.Signed -> "Matty Plugin ${v.version} · signed release (RSA + ML-DSA)"
    is ReleaseVerdict.Unsigned -> "Matty Plugin ${v.version} · not a signed release"
    is ReleaseVerdict.NotVerified -> "Matty Plugin ${v.version} · release not verified: ${v.reason}"
}
