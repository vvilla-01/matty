package ceo.villa.matty.ui.chat

import ceo.villa.matty.ui.ChatActions
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A word that looks like a domain: labels of letters, digits and hyphens, ending in a name of letters (or an IDN's xn--). */
private val domainLike = Regex("^(?:[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]*[\\p{L}\\p{N}])?\\.)+(?:\\p{L}{2,}|xn--[a-z0-9-]+)\\.?$", RegexOption.IGNORE_CASE)
private val ipv4 = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")

/** The host [text] names when it is itself an address ("https://chat.example.com/x", "example.com", an IP), or null when it is words. */
internal fun hostNamedBy(text: String): String? {
    val t = text.trim()
    if (t.isEmpty() || t.any { it.isWhitespace() }) return null
    if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(t)) return t.toHttpUrlOrNull()?.host
    val head = t.substringBefore('/').substringBefore('?').substringBefore('#')
    // A port is no part of the name: "example.com:8443".
    val name = head.substringBeforeLast(':').takeIf { head.count { it == ':' } == 1 } ?: head
    if (!ipv4.matches(name) && !domainLike.matches(name)) return null
    return "https://$t".toHttpUrlOrNull()?.host
}

private val schemeAtStart = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:")
private val webScheme = Regex("^https?:", RegexOption.IGNORE_CASE)

/**
 * A link's target as it is opened: one written with no scheme is https ("example.com/x",
 * "//example.com"). The one reading of it, for the browser and for [linkTextNamesAnotherHost].
 */
internal fun asOpened(target: String): String {
    val t = target.trim()
    return when {
        schemeAtStart.containsMatchIn(t) -> t
        t.startsWith("//") -> "https:$t"
        else -> "https://$t"
    }
}

/**
 * The web address [url] opens, read as a browser reads it (any run of slashes or backslashes
 * after the scheme, none at all), or null when it leads nowhere on the web: a path into this
 * server ("/team/pl/…"), mail, a phone number, another scheme.
 */
internal fun webAddressOf(url: String): HttpUrl? {
    val t = url.trim()
    if (t.isEmpty() || (t.startsWith("/") && !t.startsWith("//"))) return null
    return asOpened(t).takeIf { webScheme.containsMatchIn(it) }?.toHttpUrlOrNull()
}

/**
 * When a link's shown [text] is itself an address naming another host than the one [url] goes
 * to, that real host (in the form the browser will show it, so a look-alike written in other
 * letters comes out as its xn-- name); otherwise null and the link opens as it always did. The
 * same host, or one under the other (example.com shown, www.example.com reached), is the same
 * site. Words that aren't an address, and links that aren't to the web, are never asked about.
 */
internal fun linkTextNamesAnotherHost(text: String, url: String): String? {
    val shown = hostNamedBy(text)?.lowercase()?.trimEnd('.') ?: return null
    val real = webAddressOf(url)?.host?.lowercase()?.trimEnd('.') ?: return null
    if (shown == real) return null
    val ip = ipv4.matches(shown) || ipv4.matches(real) || shown.contains(':') || real.contains(':')
    if (!ip && (real.endsWith(".$shown") || shown.endsWith(".$real"))) return null
    return real
}

/**
 * A tap on [text] that leads to [url], outside Markdown (an attachment's title, its author):
 * asked about first when the words are an address naming another host, like a link in a message.
 */
internal fun ChatActions.openShown(text: String, url: String) {
    val elsewhere = linkTextNamesAnotherHost(text, url)
    if (elsewhere != null) confirmUrl(url, elsewhere) else openUrl(url)
}

/** The words a link shows, as plain text, for [linkTextNamesAnotherHost]. */
internal fun shownText(children: List<MdInline>): String = buildString {
    fun walk(list: List<MdInline>) {
        for (el in list) when (el) {
            is MdInline.Text -> append(el.text)
            is MdInline.Code -> append(el.code)
            is MdInline.Bold -> walk(el.children)
            is MdInline.Italic -> walk(el.children)
            is MdInline.Strike -> walk(el.children)
            is MdInline.Link -> walk(el.children)
            is MdInline.Mention -> append('@').append(el.name)
            is MdInline.ChannelLink -> append('~').append(el.name)
            is MdInline.Hashtag -> append('#').append(el.tag)
            is MdInline.Emoji -> append(':').append(el.name).append(':')
            MdInline.LineBreak -> append(' ')
        }
    }
    walk(children)
}
