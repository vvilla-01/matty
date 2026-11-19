package ceo.villa.matty

import android.content.SharedPreferences
import ceo.villa.matty.push.Sealer
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** What the push tests share: a Web Push sender written apart from the app's, and stand-ins for storage. */
internal object PushTestKit {
    fun b64(text: String): ByteArray = Base64.getUrlDecoder().decode(text.filterNot(Char::isWhitespace))
    fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun hmac(key: ByteArray, data: ByteArray) = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    fun point(key: ECPublicKey): ByteArray {
        fun f(n: BigInteger) = n.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }
        return byteArrayOf(4) + f(key.w.affineX) + f(key.w.affineY)
    }

    private fun publicKey(point: ByteArray, like: ECPublicKey): ECPublicKey = KeyFactory.getInstance("EC").generatePublic(
        ECPublicKeySpec(ECPoint(BigInteger(1, point.copyOfRange(1, 33)), BigInteger(1, point.copyOfRange(33, 65))), like.params),
    ) as ECPublicKey

    fun senderPair(): java.security.KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /**
     * An aes128gcm body for the receiver [uaPublic]/[auth], RFC 8291 section 3.4 as the plugin does
     * it. [padded] is the record's plaintext with its delimiter and padding already on, so a test
     * can break either; [rs], [idlen] and [senderPoint] may be bent too.
     */
    fun encrypt(
        uaPublic: ByteArray,
        auth: ByteArray,
        padded: ByteArray,
        sender: java.security.KeyPair = senderPair(),
        salt: ByteArray = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) },
        rs: Int = 4096,
        idlen: Int = 65,
        senderPoint: ByteArray = point(sender.public as ECPublicKey),
    ): ByteArray {
        val ua = publicKey(uaPublic, sender.public as ECPublicKey)
        val ecdh = KeyAgreement.getInstance("ECDH").apply { init(sender.private as ECPrivateKey); doPhase(ua, true) }.generateSecret()
        val prkKey = hmac(auth, ecdh)
        val ikm = hmac(prkKey, "WebPush: info".toByteArray() + byteArrayOf(0) + uaPublic + point(sender.public as ECPublicKey) + byteArrayOf(1))
        val prk = hmac(salt, ikm)
        val cek = hmac(prk, "Content-Encoding: aes128gcm".toByteArray() + byteArrayOf(0, 1)).copyOf(16)
        val nonce = hmac(prk, "Content-Encoding: nonce".toByteArray() + byteArrayOf(0, 1)).copyOf(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce)) }
        val header = salt + ByteBuffer.allocate(4).putInt(rs).array() + byteArrayOf(idlen.toByte()) + senderPoint
        return header + c.doFinal(padded)
    }

    /** [plain] with the last record's delimiter and zero padding to the next 1024 bytes, as the plugin pads. */
    fun pad(plain: ByteArray): ByteArray {
        val size = ((plain.size + 1 + 1023) / 1024) * 1024
        return plain + byteArrayOf(2) + ByteArray(size - plain.size - 1)
    }
}

/** A sealer that is not the Keystore but, like it, never leaves the plain text readable in what it stores. */
internal class FakeSealer : Sealer {
    var opens = true
    override fun seal(plain: String): String = "sealed:" + Base64.getEncoder().encodeToString(plain.toByteArray().map { (it.toInt() xor 0x5a).toByte() }.toByteArray())
    override fun open(sealed: String): String? = if (!opens || !sealed.startsWith("sealed:")) null
    else String(Base64.getDecoder().decode(sealed.removePrefix("sealed:")).map { (it.toInt() xor 0x5a).toByte() }.toByteArray())
}

/** SharedPreferences in memory, for code that keeps its state there. */
internal class MemoryPrefs : SharedPreferences {
    val values = HashMap<String, Any?>()
    var commits = 0

    override fun getAll(): Map<String, *> = HashMap(values)
    override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?) = values[key] as Set<String>? ?: defValues
    override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as Boolean? ?: defValue
    override fun contains(key: String) = key in values
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = HashMap<String, Any?>()
        private val removed = HashSet<String>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { changes[key] = values }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { removed += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) values.clear()
            removed.forEach { values.remove(it) }
            changes.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
            commits++
            return true
        }
        override fun apply() { commit() }
    }
}
