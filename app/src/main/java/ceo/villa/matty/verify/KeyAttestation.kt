package ceo.villa.matty.verify

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.ProviderException
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/** The phone could not make an attested key: [message] says so, in words for a person. */
class AttestationUnavailable(message: String) : Exception(message)

/** The phone's secure hardware could not attest a key just now (Android says to try again later). */
class AttestationBusy(cause: Throwable) : Exception("This phone's secure hardware could not attest a key just now.", cause)

/**
 * Android's hardware key attestation of a key made for [challenge]: an EC P-256 signing key is
 * generated in the Android Keystore with [challenge] as its attestation challenge (in StrongBox
 * when the phone has it, else in its trusted environment), its certificate chain is read (leaf
 * first, DER), and the key is deleted, whatever happened; a key left behind by an app that was
 * stopped in between is deleted the next time. One at a time. The chain says, on the secure
 * hardware's and Android's word, which app asked for the key, which certificate signed that
 * app, and how the phone booted; the server reads it. Blocking: never call it on the main thread.
 */
fun attestedChain(context: Context, challenge: ByteArray): List<ByteArray> = synchronized(ATTESTING) {
    runCatching {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        staleProofKeys(store.aliases().toList()).forEach { runCatching { store.deleteEntry(it) } }
    }
    val strongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    if (strongBox) {
        try {
            return@synchronized generateAndRead(challenge, strongBox = true)
        } catch (e: StrongBoxUnavailableException) {
            // Advertised and not there after all: the trusted environment it is.
        } catch (e: ProviderException) {
            // StrongBox refused this key (some have no room or no EC): the trusted environment it is.
        }
    }
    try {
        generateAndRead(challenge, strongBox = false)
    } catch (e: AttestationUnavailable) {
        throw e
    } catch (e: Exception) {
        // Android wraps the Keystore's own word in what it throws: a failure it calls passing
        // (no attestation keys provisioned yet, the hardware busy) is tried again later.
        if (causesOf(e).any(::isPassingKeystoreFailure)) throw AttestationBusy(e)
        throw AttestationUnavailable("This phone could not make a key in its secure hardware to prove Matty with.")
    }
}

/** The keys an app check made and never deleted (the app was stopped in between): none is in use, one proof being made at a time. */
internal fun staleProofKeys(aliases: Iterable<String>): List<String> = aliases.filter { it.startsWith(PROOF_ALIAS) }

/** [e] and each cause under it, once each. */
internal fun causesOf(e: Throwable): Sequence<Throwable> = sequence {
    val seen = HashSet<Throwable>()
    var at: Throwable? = e
    while (at != null && seen.add(at)) {
        yield(at)
        at = at.cause
    }
}

private fun isPassingKeystoreFailure(e: Throwable): Boolean =
    e is android.security.KeyStoreException &&
        (e.isTransientFailure || e.numericErrorCode == android.security.KeyStoreException.ERROR_ATTESTATION_KEYS_UNAVAILABLE)

private val ATTESTING = Any()

private fun generateAndRead(challenge: ByteArray, strongBox: Boolean): List<ByteArray> {
    val alias = PROOF_ALIAS + UUID.randomUUID()
    val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    try {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge)
            .setIsStrongBoxBacked(strongBox)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply { initialize(spec) }.generateKeyPair()
        val chain = store.getCertificateChain(alias)
        if (chain.isNullOrEmpty()) throw AttestationUnavailable("This phone made the key but gave no attestation for it.")
        return chain.map { it.encoded }
    } finally {
        runCatching { store.deleteEntry(alias) }
    }
}

private const val KEYSTORE = "AndroidKeyStore"
private const val PROOF_ALIAS = "matty-app-check-"
