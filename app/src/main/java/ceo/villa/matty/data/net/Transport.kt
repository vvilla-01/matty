package ceo.villa.matty.data.net

import ceo.villa.matty.BuildConfig
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Matty's one rule for the network: anything that can carry a password, a session token, a
 * message or a file travels over TLS, and the session token goes only to the signed-in
 * server's exact origin (scheme + host + port).
 *
 * Release builds are HTTPS/WSS-only. Debug builds additionally allow plain http to the local
 * dev server (localhost, 127.0.0.1, and the emulator's 10.0.2.2) — nothing else. The platform
 * network security config enforces the same thing underneath (res/xml + src/debug overlay).
 */
object TransportPolicy {
    private val devHosts = setOf("localhost", "127.0.0.1", "10.0.2.2")

    fun isDevHost(host: String) = host.lowercase() in devHosts

    /** Whether a URL may be used for anything session-related. ws/wss are judged as http/https. */
    fun isAllowed(url: HttpUrl): Boolean = url.isHttps || (BuildConfig.DEBUG && isDevHost(url.host))

    fun isAllowed(url: String): Boolean {
        val u = url.trim().replaceFirst(Regex("^wss://", RegexOption.IGNORE_CASE), "https://")
            .replaceFirst(Regex("^ws://", RegexOption.IGNORE_CASE), "http://")
            .toHttpUrlOrNull() ?: return false
        return isAllowed(u)
    }

    /**
     * Whether [url] is an http or https address at all, and not a file:, content:,
     * android.resource: or data: one (or a bare path) that the image loader would open on the
     * phone itself.
     */
    fun isWebAddress(url: String): Boolean = url.trim().toHttpUrlOrNull() != null

    /** Exact-origin comparison. `startsWith(baseUrl)` would accept `https://chat.example.com.evil.net`. */
    fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean =
        a.scheme == b.scheme && a.host.equals(b.host, ignoreCase = true) && a.port == b.port

    fun sameOrigin(url: String, origin: HttpUrl): Boolean =
        url.toHttpUrlOrNull()?.let { sameOrigin(it, origin) } ?: false

    /**
     * The headers for loading [url] as a picture, a video or a file: the session token, and only
     * for the signed-in server's exact [origin] over a connection the policy allows. Nothing for
     * anywhere else.
     *
     * Never `X-Requested-With`. That header is how a server tells a request its own client meant
     * to make from one a post made it make: a web page can't put it on a picture, so servers (and
     * their plugins) answer some costly reads only with it. Anything loaded as media may be an
     * address somebody posted. So the calls the app itself decides to make carry the header
     * (MattermostClient.request, VillaNetApi.req), and nothing loaded as media ever does.
     */
    fun mediaHeaders(url: HttpUrl, origin: HttpUrl, token: String?): Map<String, String> =
        if (!token.isNullOrEmpty() && sameOrigin(url, origin) && isAllowed(url)) mapOf("Authorization" to "Bearer $token") else emptyMap()

    fun mediaHeaders(url: String, origin: HttpUrl, token: String?): Map<String, String> =
        url.toHttpUrlOrNull()?.let { mediaHeaders(it, origin, token) } ?: emptyMap()

    /**
     * TLS settings shared by every client: TLS 1.2+ with modern ciphers, no cleartext in
     * release, and no redirects that switch between http and https.
     */
    fun harden(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder
        .connectionSpecs(
            if (BuildConfig.DEBUG) listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT)
            else listOf(ConnectionSpec.MODERN_TLS)
        )
        .followSslRedirects(false)
        // Last line of defence, run on every hop including redirects: a request carrying a
        // bearer token must be going somewhere the policy allows.
        .addNetworkInterceptor(Interceptor { chain ->
            val req = chain.request()
            if (req.header("Authorization") != null && !isAllowed(req.url)) {
                throw IOException("Refusing to send credentials over an unencrypted connection")
            }
            chain.proceed(req)
        })
}

/** What went wrong, in words a person can act on. */
object NetErrors {
    /** The server's certificate was rejected (as opposed to the server not speaking TLS at all). */
    fun isUntrustedCert(t: Throwable): Boolean {
        var e: Throwable? = t
        while (e != null) {
            if (e is SSLPeerUnverifiedException || e is CertPathValidatorException || e is CertificateException) return true
            if (e.message.orEmpty().contains("Trust anchor", ignoreCase = true) ||
                e.message.orEmpty().contains("certificate", ignoreCase = true)) return true
            e = e.cause
        }
        return false
    }

    /** A TLS-level failure — but not a dropped connection that merely surfaced inside the TLS layer. */
    fun isTls(t: Throwable): Boolean {
        val chain = generateSequence(t) { it.cause }.toList()
        val ioDrop = chain.any {
            val m = it.message.orEmpty()
            it is java.net.SocketException || it is java.io.EOFException || it is java.net.SocketTimeoutException ||
                m.contains("I/O error during system call", true) || m.contains("Connection reset", true) ||
                m.contains("Broken pipe", true) || m.contains("Read error", true)
        }
        if (ioDrop) return false
        return chain.any {
            it is SSLHandshakeException || it is SSLPeerUnverifiedException || it is CertPathValidatorException ||
                it is CertificateException || it is SSLException
        }
    }

    /**
     * The server couldn't be reached or the connection dropped (no network, DNS, refused, timed
     * out, reset), as opposed to the server answering with an error or a local problem.
     */
    fun isUnreachable(t: Throwable): Boolean = generateSequence(t) { it.cause }.any {
        it is java.net.UnknownHostException || it is java.net.ConnectException || it is java.net.NoRouteToHostException ||
            it is java.net.SocketException || it is java.io.InterruptedIOException || it is java.io.EOFException ||
            it is javax.net.ssl.SSLException
    }

    fun isCleartextRefused(t: Throwable): Boolean {
        var e: Throwable? = t
        while (e != null) {
            val m = e.message.orEmpty()
            if (m.contains("CLEARTEXT", ignoreCase = true) || m.contains("unencrypted connection")) return true
            e = e.cause
        }
        return false
    }

    const val HTTPS_REQUIRED =
        "Matty only connects over HTTPS, so your password and messages are always encrypted. Ask your server's admin to turn on HTTPS (for example with Let's Encrypt)."

    const val UNTRUSTED_CERT =
        "This server's security certificate isn't trusted, so Matty won't connect. The certificate may have expired, be self-signed, or someone may be intercepting the connection."

    const val NO_TLS =
        "Couldn't make a secure connection. The server may not support HTTPS at this address — check the address and port."

    fun describe(t: Throwable, siteName: String): String = when {
        isCleartextRefused(t) -> HTTPS_REQUIRED
        isUntrustedCert(t) -> UNTRUSTED_CERT
        isTls(t) -> NO_TLS
        else -> "Couldn't reach $siteName. Check your connection."
    }
}
