package dev.standin.distributor

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Base64

/** What one long poll of the stand-in push server (`GET /fake/push/<id>/next`) came to. */
sealed interface PollAnswer {
    /** A message, as the app's server posted it. */
    class Message(val id: String, val body: ByteArray) : PollAnswer
    /** Nothing came within the wait. */
    data object Nothing : PollAnswer
    /** The endpoint was dropped at the push server (404). */
    data object Gone : PollAnswer
    /** The push server could not be asked or answered something else: ask again later. */
    data object Retry : PollAnswer

    companion object {
        /** The answer from its status and body: `{"id":"m1","body":"<base64>"}` with 200. */
        fun of(status: Int, text: String?): PollAnswer = when (status) {
            200 -> {
                val fields = text?.let(FlatJson::strings)
                val body = fields?.get("body")?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
                if (body == null || body.isEmpty()) Retry else Message(fields["id"] ?: "", body)
            }
            204 -> Nothing
            404 -> Gone
            else -> Retry
        }
    }
}

/** How long to wait before asking again after [failures] failed polls in a row: 1 s, doubling, at most 30 s. */
fun backoffMs(failures: Int): Long = if (failures <= 0) 0 else minOf(30_000L, 1_000L shl minOf(failures - 1, 5))

/**
 * The string fields of one flat JSON object (the stand-in's answers); other values are skipped and
 * anything else (nesting included) is null.
 */
object FlatJson {
    fun strings(text: String): Map<String, String>? = runCatching { Reader(text).obj() }.getOrNull()

    private class Reader(val s: String) {
        var i = 0

        fun obj(): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            space(); expect('{'); space()
            if (peek() == '}') { i++; return out.also { end() } }
            while (true) {
                space()
                val name = string()
                space(); expect(':'); space()
                when (peek()) {
                    '"' -> out[name] = string()
                    't' -> word("true")
                    'f' -> word("false")
                    'n' -> word("null")
                    else -> number()
                }
                space()
                when (s.getOrNull(i++)) {
                    ',' -> continue
                    '}' -> return out.also { end() }
                    else -> error("expected , or }")
                }
            }
        }

        fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                val c = s.getOrNull(i++) ?: error("unterminated string")
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> when (val e = s.getOrNull(i++)) {
                        '"', '\\', '/' -> out.append(e)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            val hex = s.substring(i, i + 4)
                            out.append(hex.toInt(16).toChar())
                            i += 4
                        }
                        else -> error("bad escape")
                    }
                    c < ' ' -> error("control character in string")
                    else -> out.append(c)
                }
            }
        }

        fun number() {
            val start = i
            while (i < s.length && s[i] in "+-0123456789.eE") i++
            if (i == start) error("expected a value")
            s.substring(start, i).toDouble()
        }

        fun word(w: String) {
            if (!s.startsWith(w, i)) error("expected $w")
            i += w.length
        }

        fun peek(): Char? = s.getOrNull(i)
        fun expect(c: Char) { if (s.getOrNull(i++) != c) error("expected $c") }
        fun space() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun end() { space(); if (i != s.length) error("trailing text") }
    }
}

/** [StandIn] over http, and the long poll. Only ever [STAND_IN]'s address. */
class HttpStandIn(private val base: String = STAND_IN) : StandIn {
    override fun put(id: String, vapid: String?): Boolean =
        call("PUT", "/fake/push/${path(id)}?vapid=" + URLEncoder.encode(vapid ?: "", Charsets.UTF_8)) == 200

    override fun delete(id: String): Boolean = call("DELETE", "/fake/push/${path(id)}") == 200

    /**
     * One long poll for [id], waiting at most [waitS] seconds there. [opened] is handed the
     * connection, so another thread can cut it short.
     */
    fun poll(id: String, waitS: Int, opened: (HttpURLConnection) -> Unit = {}): PollAnswer = try {
        val connection = open("GET", "/fake/push/${path(id)}/next?wait=$waitS", readMs = (waitS + 10) * 1000)
        opened(connection)
        try {
            val status = connection.responseCode
            val text = if (status == 200) connection.inputStream.use { it.readBytes().decodeToString() } else null
            PollAnswer.of(status, text)
        } finally {
            connection.disconnect()
        }
    } catch (_: IOException) {
        PollAnswer.Retry
    }

    private fun call(method: String, path: String): Int = try {
        val connection = open(method, path, readMs = 3_000)
        try {
            connection.responseCode.also { runCatching { (if (it < 400) connection.inputStream else connection.errorStream)?.use { s -> s.readBytes() } } }
        } finally {
            connection.disconnect()
        }
    } catch (_: IOException) {
        -1
    }

    private fun open(method: String, path: String, readMs: Int): HttpURLConnection =
        (URI(base + path).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 2_000
            readTimeout = readMs
            instanceFollowRedirects = false
            useCaches = false
        }

    /** An endpoint id is the stand-in's `[A-Za-z0-9_-]`: nothing in it needs escaping, and nothing else is sent. */
    private fun path(id: String): String {
        require(id.matches(Regex("^[A-Za-z0-9_-]{1,100}$"))) { "not an endpoint id" }
        return id
    }
}
