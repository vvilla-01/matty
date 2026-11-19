package ceo.villa.matty.verify

import java.security.GeneralSecurityException
import java.security.NoSuchAlgorithmException
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec

/**
 * The two certificates a release must be signed with, both of them, each checked against a
 * digest known in advance: a certificate that travels with what it signs is never trusted.
 */
class ReleasePins private constructor(val rsa: PublicKey, val mldsa: PublicKey) {
    companion object {
        /** Matty's own two ([MattyCertificates]), refused unless they are the published ones. */
        fun official(): ReleasePins = of(MattyCertificates.rsaDer, MattyCertificates.RSA_SHA256, MattyCertificates.mldsaDer, MattyCertificates.MLDSA_SHA256)

        /**
         * Two certificates (DER), each with the SHA-256 it must have ([sha256Colons]'s form). The
         * RSA key must be 4096 bits; the ML-DSA key's parameter set is held to ML-DSA-87 by the
         * verifier itself, which refuses any other.
         */
        fun of(rsaDer: ByteArray, rsaSha256: String, mldsaDer: ByteArray, mldsaSha256: String): ReleasePins {
            require(sha256Colons(rsaDer).equals(rsaSha256, ignoreCase = true)) { "the RSA certificate is not the one pinned" }
            require(sha256Colons(mldsaDer).equals(mldsaSha256, ignoreCase = true)) { "the ML-DSA certificate is not the one pinned" }
            val rsa = MattyCertificates.certificate(rsaDer).publicKey
            require(rsa is RSAPublicKey && rsa.modulus.bitLength() == 4096) { "the RSA certificate is not RSA 4096" }
            val mldsa = MattyCertificates.certificate(mldsaDer).publicKey
            require(mldsa.algorithm.startsWith("ML-DSA")) { "the ML-DSA certificate is not ML-DSA" }
            return ReleasePins(rsa, mldsa)
        }
    }
}

/**
 * Checks a release's two signatures over [message] with the platform's own providers, and says
 * every way it failed (empty: both verify). Both are always checked, and neither is enough
 * alone:
 *
 *  - [rsaSignature]: RSASSA-PSS with SHA-512, MGF1 with SHA-512 and a 64-byte salt, 512 bytes.
 *    Android names it SHA512withRSA/PSS; a desktop JDK only RSASSA-PSS. The parameters are set
 *    explicitly either way, so no provider's default decides them.
 *  - [mldsaSignature]: pure ML-DSA-87 (FIPS 204) with the empty context, 4627 bytes, which is what
 *    Android's own provider verifies. A phone whose provider has no ML-DSA-87 cannot check it,
 *    and that is a failure like any other.
 */
fun releaseSignatureProblems(pins: ReleasePins, message: ByteArray, rsaSignature: ByteArray?, mldsaSignature: ByteArray?): List<String> {
    val problems = mutableListOf<String>()
    if (rsaSignature == null || rsaSignature.size != RSA_SIGNATURE_BYTES) problems += "the RSA signature is missing or not $RSA_SIGNATURE_BYTES bytes"
    else if (!verifies { rsaPss(pins.rsa, message, rsaSignature) }) problems += "the RSA signature does not verify"

    if (mldsaSignature == null || mldsaSignature.size != MLDSA_SIGNATURE_BYTES) problems += "the ML-DSA signature is missing or not $MLDSA_SIGNATURE_BYTES bytes"
    else {
        val ml = try {
            Signature.getInstance("ML-DSA-87")
        } catch (e: NoSuchAlgorithmException) {
            null
        }
        if (ml == null) problems += "this phone cannot check ML-DSA-87 signatures"
        else if (!verifies { ml.initVerify(pins.mldsa); ml.update(message); ml.verify(mldsaSignature) }) problems += "the ML-DSA signature does not verify"
    }
    return problems
}

const val RSA_SIGNATURE_BYTES = 512
const val MLDSA_SIGNATURE_BYTES = 4627

private fun rsaPss(key: PublicKey, message: ByteArray, signature: ByteArray): Boolean {
    val s = try {
        Signature.getInstance("SHA512withRSA/PSS")
    } catch (e: NoSuchAlgorithmException) {
        Signature.getInstance("RSASSA-PSS")
    }
    s.initVerify(key)
    s.setParameter(PSSParameterSpec("SHA-512", "MGF1", MGF1ParameterSpec.SHA512, 64, 1))
    s.update(message)
    return s.verify(signature)
}

/** A signature that throws (a malformed one, a key of the wrong kind) does not verify. */
private inline fun verifies(check: () -> Boolean): Boolean = try {
    check()
} catch (e: GeneralSecurityException) {
    false
} catch (e: IllegalArgumentException) {
    false
}
