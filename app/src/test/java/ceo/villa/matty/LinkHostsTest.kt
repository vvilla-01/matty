package ceo.villa.matty

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import ceo.villa.matty.data.api.FileInfo
import ceo.villa.matty.ui.ChatActions
import ceo.villa.matty.ui.chat.LinkUrlTag
import ceo.villa.matty.ui.chat.Markdown
import ceo.villa.matty.ui.chat.buildInline
import ceo.villa.matty.ui.theme.Palette
import ceo.villa.matty.ui.theme.mattyColors
import ceo.villa.matty.ui.chat.MdInline
import ceo.villa.matty.ui.chat.asOpened
import ceo.villa.matty.ui.chat.openShown
import ceo.villa.matty.ui.chat.linkTextNamesAnotherHost
import ceo.villa.matty.ui.chat.shownText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A link whose words are an address of another site than the one it opens: asked about before it opens. */
class LinkHostsTest {
    private fun asks(text: String, url: String) = linkTextNamesAnotherHost(text, url)

    @Test
    fun anotherHostIsNamed() {
        assertEquals("chat-example.evil", asks("https://chat.example.com/login", "https://chat-example.evil/login"))
        assertEquals("chat.example.com.evil.net", asks("chat.example.com", "https://chat.example.com.evil.net/"))
        assertEquals("evil.net", asks("www.example.com/docs", "http://evil.net/docs"))
        assertEquals("evil.net", asks("example.com:8443", "https://evil.net"))
        // A look-alike in other letters: named as the browser will show it.
        assertEquals("xn--exmple-4nf.com", asks("example.com", "https://exаmple.com/"))
        assertEquals("examp1e.com", asks("example.com", "https://examp1e.com/"))
        // Two sites side by side under one name are not one site.
        assertEquals("attacker.github.io", asks("victim.github.io", "https://attacker.github.io"))
        // Addresses by number.
        assertEquals("10.0.0.9", asks("10.0.0.1", "https://10.0.0.9/"))
        assertEquals("evil.net", asks("192.168.1.1", "https://evil.net/"))
        assertEquals("192.168.1.1", asks("example.com", "https://192.168.1.1/"))
    }

    @Test
    fun theSameSiteOpensAsAlways() {
        assertNull(asks("https://chat.example.com/login", "https://chat.example.com/other"))
        assertNull(asks("CHAT.Example.com", "https://chat.example.com/"))
        assertNull(asks("example.com", "https://www.example.com/"))
        assertNull(asks("docs.example.com", "https://example.com/"))
        assertNull(asks("10.0.0.1", "http://10.0.0.1:8080/"))
    }

    @Test
    fun wordsAndOtherLinksAreNeverAsked() {
        assertNull(asks("click here", "https://evil.net/"))
        assertNull(asks("the docs", "https://evil.net/"))
        assertNull(asks("example", "https://evil.net/"))
        assertNull(asks("v1.2", "https://evil.net/"))
        assertNull(asks("", "https://evil.net/"))
        // Not a web link: mail, a server-relative path.
        assertNull(asks("example.com", "mailto:someone@evil.net"))
        assertNull(asks("example.com", "/team/pl/abc"))
    }

    @Test
    fun theWordsOfAParsedLink() {
        val link = Markdown.parseInlines("[**https://chat.example.com**/login](https://chat-example.evil/login)").filterIsInstance<MdInline.Link>().single()
        assertEquals("https://chat.example.com/login", shownText(link.children))
        assertEquals("chat-example.evil", asks(shownText(link.children), link.url))
        // An address written out as itself opens as always.
        val auto = Markdown.parseInlines("see https://evil.net/x").filterIsInstance<MdInline.Link>().single()
        assertNull(asks(shownText(auto.children), auto.url))
    }

    private class Taps : ChatActions {
        val opened = ArrayList<String>()
        val asked = ArrayList<Pair<String, String>>()
        override fun openUser(userId: String) {}
        override fun openUsername(username: String) {}
        override fun openChannel(channelId: String) {}
        override fun openChannelByName(name: String) {}
        override fun openThread(rootId: String) {}
        override fun openUrl(url: String) { opened += url }
        override fun confirmUrl(url: String, host: String) { asked += url to host }
        override fun openMedia(url: String, isVideo: Boolean, title: String) {}
        override fun openFile(file: FileInfo) {}
        override fun search(terms: String) {}
        override fun openPlugin(pluginId: String, args: Map<String, String>) {}
        override fun messageUser(userId: String) {}
    }

    private fun tapLinks(message: String): Taps {
        val taps = Taps()
        val s = buildInline(Markdown.parseInlines(message), mattyColors(Palette.Oled, Color(0xFF8AB4FF)), "me", emptyMap(), taps, edited = false).first
        for (range in s.getLinkAnnotations(0, s.length)) {
            val link = range.item as LinkAnnotation.Clickable
            if (link.tag == "url") link.linkInteractionListener!!.onClick(link)
        }
        // Each web link says where it goes, for a long press; the words are untouched.
        assertEquals(s.getLinkAnnotations(0, s.length).count { (it.item as LinkAnnotation.Clickable).tag == "url" },
            s.getStringAnnotations(LinkUrlTag, 0, s.length).size)
        return taps
    }

    @Test
    fun aTapOnAnAddressThatGoesElsewhereAsksFirst() {
        val taps = tapLinks("[https://chat.example.com/login](https://chat-example.evil/login) and [the docs](https://docs.example.com) and https://example.com/x")
        assertEquals(listOf("https://chat-example.evil/login" to "chat-example.evil"), taps.asked)
        assertEquals(listOf("https://docs.example.com", "https://example.com/x"), taps.opened)
    }

    @Test
    fun aTargetWithNoSchemeIsReadAsItIsOpened() {
        // Navigator.openUrl opens "evil.net" as https://evil.net: the check reads it the same way.
        assertEquals("evil.net", asks("chat.example.com", "evil.net"))
        assertEquals("evil.net", asks("chat.example.com", "evil.net/login"))
        assertEquals("evil.net", asks("chat.example.com", "//evil.net"))
        assertEquals("evil.net", asks("https://chat.example.com/login", " //evil.net/login "))
        // What a browser makes of too few slashes, or the wrong ones.
        assertEquals("evil.net", asks("chat.example.com", "https:/evil.net"))
        assertEquals("evil.net", asks("chat.example.com", "https:evil.net"))
        assertEquals("evil.net", asks("chat.example.com", "https:\\\\evil.net"))
        assertEquals("evil.net", asks("chat.example.com", "HTTPS://evil.net"))
        assertNull(asks("chat.example.com", "chat.example.com/login"))
        assertNull(asks("example.com", "//www.example.com/x"))
        assertEquals("https://evil.net", asOpened("evil.net"))
        assertEquals("https://evil.net", asOpened("//evil.net"))
        assertEquals("https://evil.net/x", asOpened(" https://evil.net/x "))
        assertEquals("mailto:a@b.c", asOpened("mailto:a@b.c"))
    }

    @Test
    fun anAttachmentsTitleThatIsAnAddressAsksToo() {
        val taps = Taps()
        taps.openShown("https://chat.example.com/login", "https://evil.net")
        taps.openShown("chat.example.com", "evil.net")
        taps.openShown("Release notes", "https://evil.net/notes")
        assertEquals(listOf("https://evil.net" to "evil.net", "evil.net" to "evil.net"), taps.asked)
        assertEquals(listOf("https://evil.net/notes"), taps.opened)
    }
}
