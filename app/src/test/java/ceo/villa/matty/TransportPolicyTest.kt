package ceo.villa.matty

import ceo.villa.matty.data.net.Downloader
import ceo.villa.matty.data.net.TransportPolicy
import ceo.villa.matty.ui.login.ServerAddress
import ceo.villa.matty.ui.login.parseServerAddress
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Unit tests run against the debug variant, where the local dev server exception applies. */
class TransportPolicyTest {
    private val server = "https://chat.example.com".toHttpUrl()

    @Test fun httpsAlwaysAllowed_plainHttpOnlyForTheDevServer() {
        assertTrue(TransportPolicy.isAllowed("https://chat.example.com"))
        assertTrue(TransportPolicy.isAllowed("wss://chat.example.com/api/v4/websocket"))
        assertFalse(TransportPolicy.isAllowed("http://chat.example.com"))
        assertFalse(TransportPolicy.isAllowed("ws://chat.example.com/api/v4/websocket"))
        assertFalse(TransportPolicy.isAllowed("http://192.168.1.10:8065"), "LAN http is still cleartext")
        assertFalse(TransportPolicy.isAllowed("intent://x#Intent;end"))
        assertFalse(TransportPolicy.isAllowed("javascript:alert(1)"))
        // Debug builds only (this test variant): the local dev server.
        assertEquals(BuildConfig.DEBUG, TransportPolicy.isAllowed("http://localhost:8065"))
        assertEquals(BuildConfig.DEBUG, TransportPolicy.isAllowed("http://10.0.2.2:8065"))
    }

    @Test fun sameOriginIsExact() {
        assertTrue(TransportPolicy.sameOrigin("https://chat.example.com/api/v4/users/x/image", server))
        assertTrue(TransportPolicy.sameOrigin("https://CHAT.example.com/x", server), "hosts compare case-insensitively")
        assertFalse(TransportPolicy.sameOrigin("https://chat.example.com.evil.net/x", server), "look-alike host")
        assertFalse(TransportPolicy.sameOrigin("https://evil.net/https://chat.example.com", server))
        assertFalse(TransportPolicy.sameOrigin("http://chat.example.com/x", server), "scheme downgrade")
        assertFalse(TransportPolicy.sameOrigin("https://chat.example.com:8443/x", server), "other port")
        assertFalse(TransportPolicy.sameOrigin("https://sub.chat.example.com/x", server), "subdomain")
    }

    @Test fun mediaCarriesTheTokenToTheSignedInServerAndNothingElse() {
        val token = "tok"
        assertEquals(mapOf("Authorization" to "Bearer tok"), TransportPolicy.mediaHeaders("https://chat.example.com/api/v4/files/abc", server, token))
        assertEquals(mapOf("Authorization" to "Bearer tok"), TransportPolicy.mediaHeaders("https://chat.example.com/plugins/ceo.villa.matty/api/v1/clips/video?id=x", server, token))
        // Anywhere else gets nothing at all.
        for (other in listOf("https://media.giphy.com/x.gif", "https://chat.example.com.evil.net/x", "https://chat.example.com:8443/x", "http://chat.example.com/x", "not a url")) {
            assertEquals(emptyMap(), TransportPolicy.mediaHeaders(other, server, token), other)
        }
        assertEquals(emptyMap(), TransportPolicy.mediaHeaders("https://chat.example.com/x", server, null), "signed out")
        assertEquals(emptyMap(), TransportPolicy.mediaHeaders("https://chat.example.com/x", server, ""))
    }

    /**
     * `X-Requested-With` says "the app itself decided to make this request". A posted picture,
     * a video or a download is an address somebody else chose, so nothing that loads one may send
     * it: a plugin that answers a costly read only with that header (VillaNET's soundboard and
     * stream profile) would otherwise answer a post. Only the two builders of the app's own API
     * calls set it.
     */
    @Test fun onlyTheAppsOwnApiCallsSayTheyAreTheApps() {
        val main = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .map { java.io.File(it, "app/src/main/java") }.first { it.isDirectory }
        val literal = "\"X-Requested-With\""
        val setters = main.walkTopDown().filter { it.extension == "kt" }
            .associate { it.name to Regex(Regex.escape(literal)).findAll(it.readText()).count() }.filterValues { it > 0 }
        assertEquals(mapOf("MattermostClient.kt" to 1, "VillaNetApi.kt" to 1), setters)
    }

    @Test fun serverAddresses() {
        assertEquals(ServerAddress.Ok("https://chat.example.com"), parseServerAddress(" chat.example.com/ "))
        assertEquals(ServerAddress.Ok("https://chat.example.com/mm"), parseServerAddress("https://chat.example.com/mm/login"))
        assertIs<ServerAddress.Rejected>(parseServerAddress("http://chat.example.com"), "typed http is refused, not downgraded to")
        assertIs<ServerAddress.Rejected>(parseServerAddress(""))
        assertIs<ServerAddress.Rejected>(parseServerAddress("not a url at all"))
        if (BuildConfig.DEBUG) assertEquals(ServerAddress.Ok("http://localhost:8065"), parseServerAddress("localhost:8065"))
    }

    @Test fun downloadNamesAreBare() {
        assertEquals("passwd", Downloader.safeName("../../etc/passwd"))
        assertEquals("report.pdf", Downloader.safeName("C:\\Users\\x\\report.pdf"))
        assertEquals("bashrc", Downloader.safeName(".bashrc"))
        assertEquals("ab.txt", Downloader.safeName("a\u0000b.txt"))
        assertEquals("file", Downloader.safeName("///"))
    }

    @Test fun onlyWebAddressesAreLoadedAsPictures() {
        assertTrue(TransportPolicy.isWebAddress("https://chat.example.com/api/v4/files/x"))
        assertTrue(TransportPolicy.isWebAddress("http://example.com/p.png"))
        assertTrue(TransportPolicy.isWebAddress(" HTTPS://example.com/p.png "))
        // Addresses a member could write that the image loader would open on the phone itself.
        for (url in listOf(
            "file:///data/user/0/ceo.villa.matty/cache/coil/x.1", "content://media/external/images/1",
            "android.resource://ceo.villa.matty/1", "data:image/png;base64,iVBORw0KGgo=", "/data/x", "x.png", "",
            "file:/sdcard/a.png", "javascript:alert(1)",
        )) assertFalse(TransportPolicy.isWebAddress(url), url)
    }

    @Test fun downloadNamesCantBeMadeToReadAsAnotherType() {
        // A right-to-left override would show "invoicekpa.pdf".
        assertEquals("invoicefdp.apk", Downloader.safeName("invoice\u202Efdp.apk"))
        // The other direction characters, and the invisible zero-width ones.
        for (ch in listOf('\u061C', '\u200B', '\u200E', '\u200F', '\u202A', '\u202B', '\u202C', '\u202D',
            '\u2060', '\u2066', '\u2067', '\u2068', '\u2069', '\uFEFF')) {
            assertEquals("report.pdf", Downloader.safeName("rep${ch}ort.pdf"), "U+%04X".format(ch.code))
        }
        // Nothing but them: still a name.
        assertEquals("file", Downloader.safeName("\u202E\u2066\u200B"))
        // What joins letters and emoji stays: a family emoji, and Persian's non-joiner.
        assertEquals("\uD83D\uDC68\u200D\uD83D\uDC69.png", Downloader.safeName("\uD83D\uDC68\u200D\uD83D\uDC69.png"))
        assertEquals("\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645.txt", Downloader.safeName("\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645.txt"))
    }
}
