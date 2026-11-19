package ceo.villa.matty.push

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** base64url without padding, as UnifiedPush and Web Push write keys; reading also takes padding. */
internal object B64 {
    fun url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** The bytes [text] holds, or null when it is not base64url. */
    fun decode(text: String?): ByteArray? = text?.let { runCatching { Base64.getUrlDecoder().decode(it.trimEnd('=')) }.getOrNull() }
}

/** The curve Web Push uses (P-256), with the platform's own providers. */
internal object P256 {
    val params: ECParameterSpec by lazy {
        runCatching {
            AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
        }.getOrElse { (generate(SecureRandom()).public as ECPublicKey).params }
    }

    private val p: BigInteger get() = (params.curve.field as ECFieldFp).p

    fun generate(random: SecureRandom): java.security.KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    /** The 65-byte uncompressed point (0x04 || X || Y). */
    fun encode(key: ECPublicKey): ByteArray = byteArrayOf(4) + fixed(key.w.affineX) + fixed(key.w.affineY)

    /** The public key an uncompressed point names, or null when it is not one on this curve. */
    fun decodePublic(bytes: ByteArray): ECPublicKey? {
        if (bytes.size != 65 || bytes[0] != 4.toByte()) return null
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        if (!onCurve(x, y)) return null
        return runCatching { KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) as ECPublicKey }.getOrNull()
    }

    /** y² = x³ + ax + b (mod p), both coordinates in the field: a point anywhere else is refused. */
    fun onCurve(x: BigInteger, y: BigInteger): Boolean {
        val p = p
        if (x.signum() < 0 || y.signum() < 0 || x >= p || y >= p) return false
        val left = y.multiply(y).mod(p)
        val right = x.pow(3).add(params.curve.a.multiply(x)).add(params.curve.b).mod(p)
        return left == right
    }

    fun privateKey(scalar: ByteArray): ECPrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, scalar), params)) as ECPrivateKey

    /** The private key's 32-byte scalar. */
    fun scalar(key: ECPrivateKey): ByteArray = fixed(key.s)

    fun ecdh(private: ECPrivateKey, public: ECPublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").apply { init(private); doPhase(public, true) }.generateSecret()

    private fun fixed(n: BigInteger): ByteArray {
        val raw = n.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        return ByteArray(32 - raw.size) + raw
    }
}

/**
 * Opening a Web Push message (RFC 8291, "aes128gcm" of RFC 8188): what a UnifiedPush distributor
 * hands over is the push server's body exactly as the server sent it, and only the keys this
 * install made for that registration open it. Platform ECDH, HMAC-SHA256 and AES-GCM only.
 */
object WebPush {
    /** A distributor never hands over more (AND_3: the push message is 1 to 4096 bytes). */
    const val MAX_BODY = 4096
    /** salt (16) + rs (4) + idlen (1) + the sender's key (65). */
    const val HEADER = 86
    private const val TAG = 16
    /** A header, a tag and at least the padding delimiter. */
    const val MIN_BODY = HEADER + TAG + 1

    /** One registration's receiving keys: the P-256 pair and the 16-byte authentication secret. */
    class Keys(val private: ECPrivateKey, val public: ByteArray, val auth: ByteArray) {
        init {
            require(public.size == 65) { "an uncompressed P-256 point" }
            require(auth.size == 16) { "a 16-byte authentication secret" }
        }

        /** The public half as the server is given it (`p256dh`). */
        val p256dh: String get() = B64.url(public)
        val authText: String get() = B64.url(auth)

        override fun toString() = "WebPush.Keys"
    }

    fun newKeys(random: SecureRandom = SecureRandom()): Keys {
        val pair = P256.generate(random)
        return Keys(pair.private as ECPrivateKey, P256.encode(pair.public as ECPublicKey), ByteArray(16).also(random::nextBytes))
    }

    /** The values RFC 8291 section 3.4 derives, exposed for its appendix's test vector. */
    internal class Derived(val ecdh: ByteArray, val prkKey: ByteArray, val ikm: ByteArray, val prk: ByteArray, val cek: ByteArray, val nonce: ByteArray)

    internal fun derive(keys: Keys, salt: ByteArray, senderPublic: ByteArray): Derived? {
        val sender = P256.decodePublic(senderPublic) ?: return null
        val ecdh = P256.ecdh(keys.private, sender)
        val prkKey = hmac(keys.auth, ecdh)
        val ikm = hmac(prkKey, "WebPush: info".toByteArray() + 0 + keys.public + senderPublic + 1)
        val prk = hmac(salt, ikm)
        val cek = hmac(prk, "Content-Encoding: aes128gcm".toByteArray() + 0 + 1).copyOf(16)
        val nonce = hmac(prk, "Content-Encoding: nonce".toByteArray() + 0 + 1).copyOf(12)
        return Derived(ecdh, prkKey, ikm, prk, cek, nonce)
    }

    /**
     * The plaintext of [body], or null when it is not one aes128gcm record these [keys] open:
     * out of size, another key length, a record size that does not hold the whole body, a key
     * not on the curve, a failed tag, or padding that does not end in the last record's 0x02.
     */
    fun decrypt(body: ByteArray, keys: Keys): ByteArray? = runCatching { open(body, keys) }.getOrNull()

    private fun open(body: ByteArray, keys: Keys): ByteArray? {
        if (body.size < MIN_BODY || body.size > MAX_BODY) return null
        val salt = body.copyOfRange(0, 16)
        val rs = ((body[16].toLong() and 0xff) shl 24) or ((body[17].toLong() and 0xff) shl 16) or
            ((body[18].toLong() and 0xff) shl 8) or (body[19].toLong() and 0xff)
        // One record only: it must hold everything after the header (RFC 8291 section 4).
        if (rs < 18 || rs > MAX_BODY || body.size - HEADER > rs) return null
        if (body[20].toInt() != 65) return null
        val derived = derive(keys, salt, body.copyOfRange(21, HEADER)) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derived.cek, "AES"), GCMParameterSpec(TAG * 8, derived.nonce))
        val padded = cipher.doFinal(body, HEADER, body.size - HEADER)
        var end = padded.size - 1
        while (end >= 0 && padded[end] == 0.toByte()) end--
        if (end < 0 || padded[end] != 2.toByte()) return null
        return padded.copyOf(end)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private operator fun ByteArray.plus(b: Int): ByteArray = this + byteArrayOf(b.toByte())
}
