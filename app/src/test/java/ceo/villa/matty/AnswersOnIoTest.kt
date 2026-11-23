package ceo.villa.matty

import ceo.villa.matty.data.api.MattermostClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlin.concurrent.thread

/**
 * No answer of [MattermostClient.execute] is read or closed on the calling thread: closing an
 * answer that was not read whole reads the rest of it from the network, which Android refuses on
 * its main thread. Held with a real local answer whose chunked body comes in two parts, and a
 * caller on a thread that must not read from the network, as Android's main thread.
 */
class AnswersOnIoTest {
    @get:Rule val tmp = TemporaryFolder()

    /** What Android throws when the main thread reads from the network. */
    private class NetworkOnThisThread : RuntimeException("Read from the network on a thread that must not")

    private val mustNotRead = ThreadLocal.withInitial { false }

    private fun guard() { if (mustNotRead.get()) throw NetworkOnThisThread() }

    /** Sockets whose reads refuse a thread marked [mustNotRead]. */
    private val guarded = object : SocketFactory() {
        override fun createSocket(): Socket = object : Socket() {
            override fun getInputStream(): InputStream = object : FilterInputStream(super.getInputStream()) {
                override fun read(): Int { guard(); return super.read() }
                override fun read(b: ByteArray, off: Int, len: Int): Int { guard(); return super.read(b, off, len) }
            }
        }
        override fun createSocket(host: String, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = createSocket(host, port)
        override fun createSocket(host: InetAddress, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = createSocket(host, port)
    }

    private lateinit var server: ServerSocket
    private val answered = AtomicInteger()

    /** Answers every request with [code] and a chunked body sent in two parts, the second a moment later. */
    private fun serve(code: Int) {
        server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { answer(s, code) }
            }
        }
    }

    private fun answer(s: Socket, code: Int) = s.use {
        runCatching {
            val input = s.getInputStream().buffered()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) return@runCatching
                head.append(b.toChar())
            }
            val length = Regex("(?im)^content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toLong() ?: 0L
            var left = length
            while (left > 0) { val n = input.skip(left); if (n <= 0) { if (input.read() < 0) break else left-- } else left -= n }
            val out = s.getOutputStream()
            out.write("HTTP/1.1 $code Done\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n1\r\n{\r\n".toByteArray())
            out.flush()
            answered.incrementAndGet()
            Thread.sleep(500)
            out.write("1\r\n}\r\n0\r\n\r\n".toByteArray())
            out.flush()
        }
    }

    @Before fun start() = serve(201)
    @After fun stop() = server.close()

    /** A client of a server as the app has one, whose requests are answered by the local server above. */
    private fun client(): MattermostClient {
        val port = server.localPort
        val http = OkHttpClient.Builder().socketFactory(guarded).addInterceptor { chain ->
            val r = chain.request()
            chain.proceed(r.newBuilder().url(r.url.newBuilder().scheme("http").host("127.0.0.1").port(port).build()).build())
        }.build()
        return MattermostClient("https://chat.example.test", "token", http)
    }

    /** Runs [block] on a thread that must not read from the network; what it threw, or null. */
    private fun onAThreadThatMustNotRead(block: suspend () -> Unit): Throwable? {
        var thrown: Throwable? = null
        thread {
            mustNotRead.set(true)
            thrown = runCatching { runBlocking { block() } }.exceptionOrNull()
        }.join()
        return thrown
    }

    @Test
    fun theHarnessRefusesAReadOnTheMarkedThread() {
        val c = client()
        val e = onAThreadThatMustNotRead {
            c.apiHttp.newCall(c.request("/system/ping").get().build()).execute().close()
        }
        assertTrue("$e", e is NetworkOnThisThread)
    }

    @Test
    fun thePluginUploadIsAnsweredAndClosedOffTheCallingThread() {
        val c = client()
        val file = File(tmp.root, "b.tar.gz").apply { writeBytes(ByteArray(10_000) { it.toByte() }) }
        assertNull(onAThreadThatMustNotRead { c.uploadPlugin(file, "ceo.villa.matty-4.5.0.tar.gz") })
        assertEquals(1, answered.get())
    }

    @Test
    fun aRequestWhoseAnswerIsNotReadIsClosedOffTheCallingThread() {
        val c = client()
        assertNull(onAThreadThatMustNotRead { c.send("PUT", "/users/me/status/custom", buildJsonObject { put("text", "x") }) })
        assertNull(onAThreadThatMustNotRead { c.send("DELETE", "/users/me/status/custom") })
        assertEquals(2, answered.get())
    }

    @Test
    fun theProfilePictureUploadIsClosedOffTheCallingThread() {
        val c = client()
        assertNull(onAThreadThatMustNotRead { c.uploadProfileImage("u1", ByteArray(5_000), "image/jpeg") })
        assertEquals(1, answered.get())
    }

    private val main = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "app/src/main/java/ceo/villa/matty") }.first { it.isDirectory }

    /**
     * Every call of `execute` in the client is made inside a function that runs on
     * [kotlinx.coroutines.Dispatchers.IO], so that its answer is read and closed there.
     */
    @Test
    fun everyCallOfExecuteInTheClientReadsAndClosesOnIo() {
        val lines = File(main, "data/api/MattermostClient.kt").readLines().map { it.trim() }
        val calls = lines.withIndex().filter { (_, l) ->
            Regex("""(^|[^.\w])execute\(""").containsMatchIn(l) && !l.startsWith("suspend fun execute(") &&
                !l.startsWith("*") && !l.startsWith("//") && !l.startsWith("/*")
        }
        assertTrue("execute is called", calls.size >= 4)
        for ((i, l) in calls) {
            val fn = (i downTo 0).first { j -> Regex("""^(private |internal )?(suspend )?fun """).containsMatchIn(lines[j]) }
            // The function's head, which may take several lines, up to where its body opens.
            val head = lines.subList(fn, (fn..i).first { lines[it].endsWith("{") } + 1).joinToString(" ")
            assertTrue("line ${i + 1} ($l) is in a function on Dispatchers.IO: $head", "withContext(Dispatchers.IO)" in head)
        }
    }
}
