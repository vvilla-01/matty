package ceo.villa.matty

import ceo.villa.matty.data.api.MattermostJson
import ceo.villa.matty.plugins.villanet.ReleaseVerdict
import ceo.villa.matty.plugins.villanet.VnRelease
import ceo.villa.matty.plugins.villanet.forgetReleases
import ceo.villa.matty.plugins.villanet.parseReleaseManifest
import ceo.villa.matty.plugins.villanet.releaseKey
import ceo.villa.matty.plugins.villanet.releaseVerdictOf
import ceo.villa.matty.plugins.villanet.releaseWords
import ceo.villa.matty.verify.MattyCertificates
import ceo.villa.matty.verify.ReleasePins
import ceo.villa.matty.verify.releaseSignatureProblems
import ceo.villa.matty.verify.sha256Colons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.security.NoSuchAlgorithmException
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * The plugin's signed release, as the app checks it: both signatures over the manifest against
 * certificates the app holds, then the manifest, strictly. The vector under
 * src/test/resources/verify was signed with throwaway keys (see SOURCE there); only their public
 * certificates are kept.
 */
class ReleaseCheckTest {
    private fun bytes(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/verify/$name")) { "no test file $name" }.use { it.readBytes() }

    private fun der(pem: String): ByteArray = CertificateFactory.getInstance("X.509").generateCertificate(bytes(pem).inputStream()).encoded

    private val rsaDer by lazy { der("throwaway-rsa.pem") }
    private val mldsaDer by lazy { der("throwaway-mldsa87.pem") }
    private val throwaway by lazy { ReleasePins.of(rsaDer, sha256Colons(rsaDer), mldsaDer, sha256Colons(mldsaDer)) }
    private val manifest by lazy { bytes("manifest.txt") }
    private val rsaSig by lazy { bytes("manifest.rsa.sig") }
    private val mldsaSig by lazy { bytes("manifest.mldsa87.sig") }

    private fun answer(state: String = "signed", manifest: ByteArray = this.manifest, rsa: ByteArray? = rsaSig, mldsa: ByteArray? = mldsaSig, version: String = "4.0.0", reason: String = "") =
        VnRelease(state, version, reason, String(manifest, Charsets.UTF_8), rsa?.let(Base64.getEncoder()::encodeToString).orEmpty(), mldsa?.let(Base64.getEncoder()::encodeToString).orEmpty())

    @Before
    fun thisJvmVerifiesMlDsa() {
        // Android 17's own provider has ML-DSA-87 (Conscrypt); a desktop JDK only from 24, or a
        // build that carries it (Arch's 21.0.13 does). Without it nothing here can be shown.
        val has = try { Signature.getInstance("ML-DSA-87"); true } catch (e: NoSuchAlgorithmException) { false }
        assumeTrue("this JVM (${System.getProperty("java.version")}) has no ML-DSA-87: the release tests are skipped, not passed", has)
    }

    @Test
    fun mattysOwnCertificatesReadAsTheirKinds() {
        val rsa = MattyCertificates.certificate(MattyCertificates.rsaDer).publicKey
        assertTrue(rsa is RSAPublicKey && rsa.modulus.bitLength() == 4096)
        assertTrue(MattyCertificates.certificate(MattyCertificates.mldsaDer).publicKey.algorithm.startsWith("ML-DSA"))
        ReleasePins.official()
    }

    @Test
    fun aPinIsADigestKnownBeforehand() {
        val wrong = runCatching { ReleasePins.of(rsaDer, MattyCertificates.RSA_SHA256, mldsaDer, sha256Colons(mldsaDer)) }
        assertTrue(wrong.isFailure)
        // The two the wrong way round: neither is what its place needs.
        assertTrue(runCatching { ReleasePins.of(mldsaDer, sha256Colons(mldsaDer), rsaDer, sha256Colons(rsaDer)) }.isFailure)
    }

    @Test
    fun bothSignaturesVerify() {
        assertEquals(emptyList<String>(), releaseSignatureProblems(throwaway, manifest, rsaSig, mldsaSig))
        val v = releaseVerdictOf(answer(), "4.0.0") { throwaway }
        assertTrue(v.toString(), v is ReleaseVerdict.Signed)
        assertEquals(sha256Colons(manifest), (v as ReleaseVerdict.Signed).manifestSha256)
        assertEquals("Matty Plugin 4.0.0 · signed release (RSA + ML-DSA)", releaseWords(v))
    }

    @Test
    fun neverSignedOnOneSignature() {
        val zero = ByteArray(rsaSig.size)
        assertEquals(listOf("the RSA signature does not verify"), releaseSignatureProblems(throwaway, manifest, zero, mldsaSig))
        assertEquals(listOf("the ML-DSA signature does not verify"), releaseSignatureProblems(throwaway, manifest, rsaSig, ByteArray(mldsaSig.size)))
        assertEquals(listOf("the RSA signature is missing or not 512 bytes"), releaseSignatureProblems(throwaway, manifest, null, mldsaSig))
        assertEquals(listOf("the ML-DSA signature is missing or not 4627 bytes"), releaseSignatureProblems(throwaway, manifest, rsaSig, mldsaSig.copyOf(4626)))
        for (half in listOf(answer(rsa = null), answer(mldsa = null), answer(rsa = zero))) {
            assertTrue(releaseVerdictOf(half, "4.0.0") { throwaway } is ReleaseVerdict.NotVerified)
        }
    }

    @Test
    fun theMlDsaSignatureIsOfTheEmptyContext() {
        assertEquals(listOf("the ML-DSA signature does not verify"), releaseSignatureProblems(throwaway, manifest, rsaSig, bytes("manifest.mldsa87-context.sig")))
    }

    @Test
    fun aChangedManifestVerifiesNeither() {
        val changed = manifest.copyOf().also { it[it.size - 2] = 'X'.code.toByte() }
        assertEquals(2, releaseSignatureProblems(throwaway, changed, rsaSig, mldsaSig).size)
    }

    @Test
    fun onlyMattysCertificatesVouchForARelease() {
        // Genuinely signed, by keys that are not Matty's.
        val v = releaseVerdictOf(answer(), "4.0.0") { ReleasePins.official() }
        assertEquals(ReleaseVerdict.NotVerified("4.0.0", "the RSA signature does not verify; the ML-DSA signature does not verify"), v)
        assertEquals("Matty Plugin 4.0.0 · release not verified: the RSA signature does not verify; the ML-DSA signature does not verify", releaseWords(v))
    }

    @Test
    fun theReleaseMustBeTheVersionTheServerReports() {
        assertEquals(ReleaseVerdict.NotVerified("4.0.1", "the signed release is 4.0.0, but the server reports 4.0.1"), releaseVerdictOf(answer(version = "4.0.1"), "4.0.1") { throwaway })
    }

    @Test
    fun theServersOwnWordThatItWasAlteredStands() {
        val v = releaseVerdictOf(answer(state = "altered", reason = "assets/icon.svg has changed"), "4.0.0") { throwaway }
        assertEquals(ReleaseVerdict.NotVerified("4.0.0", "the server says its files are not as signed: assets/icon.svg has changed"), v)
    }

    @Test
    fun anUnsignedReleaseIsSaidSo() {
        val fixture = checkNotNull(javaClass.getResourceAsStream("/villanet/release-unsigned.json")).bufferedReader().use { it.readText() }
        val unsigned = MattermostJson.decodeFromString(VnRelease.serializer(), fixture)
        assertEquals(ReleaseVerdict.Unsigned("4.0.0"), releaseVerdictOf(unsigned, "4.0.0") { throwaway })
        assertEquals("Matty Plugin 4.0.0 · not a signed release", releaseWords(ReleaseVerdict.Unsigned("4.0.0")))
        // Signed, says the server, and nothing to show for it.
        assertEquals(ReleaseVerdict.NotVerified("4.0.0", "the server sent no signatures"), releaseVerdictOf(VnRelease("signed", "4.0.0"), "4.0.0") { throwaway })
        assertTrue(releaseVerdictOf(answer(state = "unsigned"), "4.0.0") { throwaway } is ReleaseVerdict.NotVerified)
    }

    @Test
    fun theManifestIsReadStrictly() {
        val good = String(manifest, Charsets.US_ASCII)
        val read = parseReleaseManifest(manifest)
        assertEquals("4.0.0", read.version)
        assertEquals(listOf("linux-amd64"), read.platforms)
        assertEquals(3, read.files)
        val lines = good.dropLast(1).split('\n')
        fun with(i: Int, line: String) = (lines.toMutableList().also { it[i] = line }.joinToString("\n") + "\n")
        val bad = mapOf(
            "no final line feed" to good.dropLast(1),
            "a carriage return" to good.replace("\n", "\r\n"),
            "another header" to with(0, "matty-release-manifest 2"),
            "another product" to with(1, "product ceo.villa.villanet"),
            "another kind" to with(2, "kind app"),
            "not a plain version" to with(3, "version 4.0.0+standin"),
            "platforms not sorted" to with(4, "platform linux-arm64,linux-amd64"),
            "a count that is not the files" to with(5, "files 4"),
            "out of order" to (lines.take(6) + lines[7] + lines[6] + lines[8]).joinToString("\n") + "\n",
            "a path that climbs" to with(6, lines[6].replace("assets/icon.svg", "assets/../icon.svg")),
            "a path in the release folder" to with(8, lines[8].replace("server/dist/plugin-linux-amd64", "release/manifest.txt")),
            "upper-case hex" to with(6, lines[6].uppercase()),
            "a size with a leading zero" to with(6, lines[6].replace(" 7 ", " 07 ")),
            "a blank line" to good + "\n",
        )
        for ((why, text) in bad) {
            assertTrue(why, runCatching { parseReleaseManifest(text.toByteArray(Charsets.ISO_8859_1)) }.isFailure)
        }
        assertEquals(listOf("linux-amd64", "linux-arm64"), parseReleaseManifest(with(4, "platform linux-amd64,linux-arm64").toByteArray()).platforms)
    }

    @Test
    fun aPluginSwitchedOnAgainHasItsReleaseLookedAtAgain() {
        // Put back over itself as the same version with the same word from `/access`, it is
        // held under the same key: only letting go of the server's verdicts has it verified again.
        val here = "https://chat.example.com/plugins/ceo.villa.matty"
        val there = "https://chat.example.org/plugins/ceo.villa.matty"
        val held = hashMapOf<String, ReleaseVerdict>(
            releaseKey(here, "4.0.0", "signed") to ReleaseVerdict.Signed("4.0.0", "aa"),
            releaseKey(here, "4.0.0", "altered") to ReleaseVerdict.NotVerified("4.0.0", "assets/icon.svg has changed"),
            releaseKey(there, "4.0.0", "signed") to ReleaseVerdict.Signed("4.0.0", "bb"),
            releaseKey(here + "x", "4.0.0", "signed") to ReleaseVerdict.Signed("4.0.0", "cc"),
        )
        val missed = hashMapOf(releaseKey(here, "4.0.0", "unsigned") to 1L, releaseKey(there, "4.0.0", "unsigned") to 2L)
        forgetReleases(held, here)
        forgetReleases(missed, here)
        assertEquals(setOf(releaseKey(there, "4.0.0", "signed"), releaseKey(here + "x", "4.0.0", "signed")), held.keys)
        assertEquals(setOf(releaseKey(there, "4.0.0", "unsigned")), missed.keys)
    }
}
