package ceo.villa.matty.data.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import ceo.villa.matty.data.api.MattermostJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One saved Mattermost server and the account signed in to it. */
@Serializable
data class SavedServer(
    val id: String,
    val url: String,
    val name: String,
    val userId: String,
    val username: String,
    /** Session token, AES-GCM encrypted with a non-exportable Android Keystore key. */
    val sealedToken: String,
    val lastTeamId: String = "",
    val lastChannelId: String = "",
    /**
     * What this server last required of the app (see [ServerPolicy]): kept with its session, so a
     * cold start is held to it before the server has been asked again, and gone with it at sign-out.
     */
    val policy: ServerPolicy = ServerPolicy(),
)

/**
 * Persists signed-in servers. Tokens never touch disk in the clear: they are sealed with a
 * Keystore key that cannot leave the device, and the prefs file is excluded from backups.
 */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("matty_sessions", Context.MODE_PRIVATE)

    private val _servers = MutableStateFlow(load())
    val servers: StateFlow<List<SavedServer>> = _servers

    private val _activeId = MutableStateFlow(prefs.getString(KEY_ACTIVE, null))
    val activeId: StateFlow<String?> = _activeId

    val active: SavedServer? get() = _servers.value.firstOrNull { it.id == _activeId.value } ?: _servers.value.firstOrNull()

    private fun load(): List<SavedServer> = prefs.getString(KEY_SERVERS, null)?.let {
        runCatching { MattermostJson.decodeFromString(ListSerializer(SavedServer.serializer()), it) }.getOrNull()
    } ?: emptyList()

    private fun persist(list: List<SavedServer>) {
        _servers.value = list
        prefs.edit().putString(KEY_SERVERS, MattermostJson.encodeToString(ListSerializer(SavedServer.serializer()), list)).apply()
    }

    fun upsert(server: SavedServer) {
        val list = _servers.value.filterNot { it.id == server.id || (it.url == server.url && it.userId == server.userId) } + server
        persist(list)
    }

    fun setActive(id: String) {
        _activeId.value = id
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    fun remove(id: String) {
        persist(_servers.value.filterNot { it.id == id })
        if (_activeId.value == id) {
            val next = _servers.value.firstOrNull()?.id
            _activeId.value = next
            prefs.edit().putString(KEY_ACTIVE, next).apply()
        }
    }

    fun rememberLocation(id: String, teamId: String, channelId: String) {
        val list = _servers.value.map { if (it.id == id) it.copy(lastTeamId = teamId, lastChannelId = channelId) else it }
        if (list != _servers.value) persist(list)
    }

    /** What [id]'s server now requires (see [PolicyKeeper]), kept for the next start. */
    fun rememberPolicy(id: String, policy: ServerPolicy) {
        val list = _servers.value.map { if (it.id == id) it.copy(policy = policy) else it }
        if (list != _servers.value) persist(list)
    }

    /** Result of unlocking a saved token. */
    sealed interface Unseal {
        data class Ok(val token: String) : Unseal
        /** The key or data is permanently unusable (key invalidated, wiped, damaged). */
        data object Gone : Unseal
        /** A passing Keystore problem: try again later, never delete anything over it. */
        data object Retry : Unseal
    }

    fun unlock(sealed: String): Unseal = try {
        val k = existingKey() ?: return Unseal.Gone
        Unseal.Ok(open(sealed, k))
    } catch (e: Exception) {
        val chain = generateSequence<Throwable>(e) { it.cause }.toList()
        val transient = chain.filterIsInstance<android.security.KeyStoreException>().any { it.isTransientFailure }
        val permanent = chain.any {
            it is javax.crypto.AEADBadTagException || it is android.security.keystore.KeyPermanentlyInvalidatedException ||
                it is IllegalArgumentException || it is java.security.UnrecoverableKeyException
        }
        if (permanent && !transient) Unseal.Gone else Unseal.Retry
    }

    fun token(server: SavedServer): String? = (unlock(server.sealedToken) as? Unseal.Ok)?.token

    fun unseal(sealed: String): String? = (unlock(sealed) as? Unseal.Ok)?.token

    // ---- sessions still to end on the server ----------------------------------------------

    /** A sign-out that couldn't reach the server; retried until the server confirms. */
    @Serializable
    data class PendingRevoke(val url: String, val sealedToken: String)

    fun pendingRevocations(): List<PendingRevoke> = prefs.getString(KEY_REVOKE, null)?.let {
        runCatching { MattermostJson.decodeFromString(ListSerializer(PendingRevoke.serializer()), it) }.getOrNull()
    } ?: emptyList()

    private fun saveRevocations(list: List<PendingRevoke>) {
        prefs.edit().putString(KEY_REVOKE, MattermostJson.encodeToString(ListSerializer(PendingRevoke.serializer()), list.distinct().takeLast(20))).apply()
    }

    fun enqueueRevoke(url: String, sealedToken: String) = saveRevocations(pendingRevocations() + PendingRevoke(url, sealedToken))

    fun dequeueRevoke(item: PendingRevoke) = saveRevocations(pendingRevocations().filterNot { it == item })

    // ---- the key for what's cached on the device -------------------------------------------

    private var cacheKey: SecretKey? = null

    /**
     * The key recent messages are encrypted with on the device (see ChatCache): random, and
     * only ever on disk wrapped by the Keystore key, like the session tokens. Null when the
     * Keystore can't be used right now; nothing is cached then.
     */
    @Synchronized
    fun cacheKey(): SecretKey? {
        cacheKey?.let { return it }
        val sealed = prefs.getString(KEY_CACHE, null)
        return try {
            val raw = if (sealed != null) {
                Base64.decode(open(sealed, existingKey() ?: return null), Base64.NO_WRAP)
            } else {
                ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }.also {
                    prefs.edit().putString(KEY_CACHE, seal(Base64.encodeToString(it, Base64.NO_WRAP))).apply()
                }
            }
            javax.crypto.spec.SecretKeySpec(raw, "AES").also { cacheKey = it }
        } catch (e: Exception) {
            // A wrapped key that no longer opens is no key: what it encrypted is gone with it.
            if (sealed != null && generateSequence<Throwable>(e) { it.cause }.none { it is android.security.KeyStoreException && it.isTransientFailure }) dropCacheKey()
            null
        }
    }

    /** Forgets the cache key: whatever was encrypted with it can't be read again. */
    @Synchronized
    fun dropCacheKey() {
        cacheKey = null
        prefs.edit().remove(KEY_CACHE).apply()
    }

    // ---- Keystore sealing ------------------------------------------------------------------

    fun seal(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    private fun open(sealed: String, key: SecretKey): String {
        val raw = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw, 0, 12))
        return String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }

    /** The sealing key if it exists; opening never creates one (a new key can't open old data). */
    private fun existingKey(): SecretKey? =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(KEY_ALIAS, null) as? SecretKey

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            // A dedicated security chip (Titan M on Pixels) where the phone has one.
            .setIsStrongBoxBacked(strongBox)
            .build()
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        return try {
            gen.init(spec(strongBox = true)); gen.generateKey()
        } catch (e: Exception) {
            // StrongBoxUnavailableException (or a StrongBox that can't do AES-256): use the TEE.
            gen.init(spec(strongBox = false)); gen.generateKey()
        }
    }

    companion object {
        private const val KEY_SERVERS = "servers"
        private const val KEY_ACTIVE = "active"
        private const val KEY_CACHE = "cache_key"
        private const val KEY_REVOKE = "pending_revocations"
        private const val KEY_ALIAS = "matty_session_key"
    }
}
