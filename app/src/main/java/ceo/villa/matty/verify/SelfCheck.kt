package ceo.villa.matty.verify

import android.content.Context
import android.content.pm.PackageManager
import ceo.villa.matty.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * How Android says this copy of Matty is signed: the certificate it is signed with now (for a
 * hybrid-signed Matty on Android 17, the ML-DSA one) and its signing history, oldest first
 * (the RSA one, then the ML-DSA one), each as [sha256Colons] writes it.
 */
data class SigningRecord(
    val current: List<String>,
    val history: List<String>,
    val multipleSigners: Boolean,
    /** The current signer's key is an ML-DSA key. */
    val currentIsMlDsa: Boolean,
)

/** What this copy of Matty says of itself. It is the app's own word: a copy rebuilt with care can say anything. */
sealed interface SelfVerdict {
    /** Android's record is Matty's two certificates, the ML-DSA one signing now and the RSA one before it. */
    data object Official : SelfVerdict
    data object NotOfficial : SelfVerdict
    /** A debug build, which is signed with a development key. */
    data object Development : SelfVerdict
    data object Unreadable : SelfVerdict
}

/** Whether [record] is the official build's: exactly Matty's pair, in that order, and nothing else. */
fun selfVerdictOf(debug: Boolean, record: SigningRecord?): SelfVerdict = when {
    debug -> SelfVerdict.Development
    record == null -> SelfVerdict.Unreadable
    !record.multipleSigners &&
        record.current.map { it.uppercase() } == listOf(MattyCertificates.MLDSA_SHA256) &&
        record.history.map { it.uppercase() } == listOf(MattyCertificates.RSA_SHA256, MattyCertificates.MLDSA_SHA256) -> SelfVerdict.Official
    else -> SelfVerdict.NotOfficial
}

/** The line in Settings. */
fun selfWords(v: SelfVerdict): String = when (v) {
    SelfVerdict.Official -> "This copy of Matty is the official build: Android says it is signed with Matty's RSA and ML-DSA keys."
    SelfVerdict.NotOfficial -> "This copy of Matty is not the official build."
    SelfVerdict.Development -> "This is a development build of Matty, signed with a development key."
    SelfVerdict.Unreadable -> "Android did not say how this copy of Matty is signed."
}

/** The app's check of itself, made once when it starts (off the main thread), and what it read. */
object SelfCheck {
    private val _verdict = MutableStateFlow<SelfVerdict?>(null)
    val verdict: StateFlow<SelfVerdict?> = _verdict

    /** Android's record, once read; also what the app check sends along as the app's own claims. */
    @Volatile var record: SigningRecord? = null
        private set

    fun start(context: Context, scope: CoroutineScope) {
        if (_verdict.value != null) return
        val app = context.applicationContext
        scope.launch(Dispatchers.Default) {
            val read = runCatching { readRecord(app) }.getOrNull()
            record = read
            _verdict.value = selfVerdictOf(BuildConfig.DEBUG, read)
        }
    }

    private fun readRecord(context: Context): SigningRecord? {
        val info = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        ).signingInfo ?: return null
        val current = info.apkContentsSigners.orEmpty().map { it.toByteArray() }
        val history = if (info.hasMultipleSigners()) emptyList() else info.signingCertificateHistory.orEmpty().map { it.toByteArray() }
        val mlDsa = current.singleOrNull()?.let { runCatching { MattyCertificates.certificate(it).publicKey.algorithm.startsWith("ML-DSA") }.getOrDefault(false) } ?: false
        return SigningRecord(current.map(::sha256Colons), history.map(::sha256Colons), info.hasMultipleSigners(), mlDsa)
    }
}
