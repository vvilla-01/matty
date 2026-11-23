package ceo.villa.matty

import ceo.villa.matty.data.api.ApiError
import ceo.villa.matty.data.api.ApiException
import ceo.villa.matty.data.api.MattermostClient
import ceo.villa.matty.data.api.RequestDeadline
import ceo.villa.matty.plugins.villanet.InstallPort
import ceo.villa.matty.plugins.villanet.InstallState
import ceo.villa.matty.plugins.villanet.PluginInstaller
import ceo.villa.matty.plugins.villanet.UpdateOffer
import ceo.villa.matty.plugins.villanet.VillaNetApi
import ceo.villa.matty.plugins.villanet.VillaNetException
import ceo.villa.matty.plugins.villanet.removeLeftUpdates
import ceo.villa.matty.plugins.villanet.updateLineOf
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * A system admin's install of a newer Matty Plugin from the Plugins screen ([PluginInstaller]):
 * voice left, the checked bundle fetched to a file of the app's own, exactly those bytes handed
 * to Mattermost, the wait for the new version, and the file gone at the end whatever happened.
 */
class PluginInstallTest {
    @get:Rule val tmp = TemporaryFolder()

    private val offer = UpdateOffer("4.5.0", "https://github.com/vvilla-01/matty-plugin/releases/tag/v4.5.0", install = true)
    private val bundle = ByteArray(200_000) { (it * 31 + 7).toByte() }

    /** The server and the app, as the installer sees them; every call is written down with the step shown then. */
    private inner class FakePort : InstallPort {
        override val dir: File = File(tmp.root, "plugin-update")
        override val server = "Chat"
        lateinit var installer: PluginInstaller
        val calls = mutableListOf<String>()
        var clock = 0L
        var body: () -> InputStream = { ByteArrayInputStream(bundle) }
        var length: Long = bundle.size.toLong()
        var said: String? = "4.5.0"
        val left = mutableListOf<Long?>()
        var bodyRead = false
        var fetchFails: Exception? = null
        var uploadFails: Exception? = null
        var fetchHeld: CompletableDeferred<Unit>? = null
        var versions: (Int) -> String? = { n -> if (n < 2) "4.4.0" else "4.5.0" }
        var uploaded: ByteArray? = null
        var uploadedName: String? = null
        var uploadedFile: File? = null
        var fetchedInto: File? = null
        private var reads = 0

        private fun note(what: String) { calls += "$what@${installer.state.value::class.simpleName}" }

        override fun leaveVoice() = note("voice")
        override suspend fun fetchBundle(read: (InputStream, Long, String?) -> Unit) {
            note("fetch")
            fetchHeld?.await()
            fetchFails?.let { throw it }
            fetchedInto = dir.listFiles()?.singleOrNull()
            val inner = body()
            read(object : InputStream() {
                override fun read(): Int { bodyRead = true; return inner.read() }
                override fun read(b: ByteArray, off: Int, len: Int): Int { bodyRead = true; return inner.read(b, off, len) }
            }, length, said)
        }
        override suspend fun upload(file: File, name: String) {
            note("upload")
            uploadFails?.let { throw it }
            uploaded = file.readBytes()
            uploadedName = name
            uploadedFile = file
        }
        override suspend fun runningVersion(): String? {
            note("access")
            left += currentCoroutineContext()[RequestDeadline]?.leftMs()
            return versions(reads++)
        }
        override suspend fun pause(ms: Long) { assertEquals(2_000L, ms); clock += ms }
        override fun now() = clock
    }

    private fun run(port: FakePort, limit: Long = 64L * 1024 * 1024): PluginInstaller = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val installer = PluginInstaller(port, scope, limit = limit, io = Dispatchers.Unconfined)
        port.installer = installer
        assertTrue(installer.start(offer))
        while (installer.running) yield()
        installer
    }

    private fun leftIn(port: FakePort) = port.dir.listFiles()?.toList().orEmpty()

    @Test
    fun voiceIsLeftThenTheBundleIsFetchedAndExactlyThoseBytesAreInstalled() {
        val port = FakePort()
        val installer = run(port)
        assertEquals(
            listOf("voice@Getting", "fetch@Getting", "upload@Installing", "access@Installing", "access@Installing", "access@Installing"),
            port.calls,
        )
        assertEquals(InstallState.Installed("4.5.0"), installer.state.value)
        assertArrayEquals("the bytes the plugin answered, unchanged", bundle, port.uploaded)
        assertEquals("ceo.villa.matty-4.5.0.tar.gz", port.uploadedName)
        assertEquals("a file in the app's own folder", port.dir, port.uploadedFile?.parentFile)
        assertFalse("removed at the end", port.uploadedFile!!.exists())
        assertTrue(leftIn(port).isEmpty())
    }

    @Test
    fun aVersionNewerStillEndsTheWaitAndNoAnswerDoesNot() {
        val port = FakePort().apply { versions = { n -> listOf(null, "4.4.0", null, "4.6.0").getOrNull(n) } }
        assertEquals("the end names the version that answered", InstallState.Installed("4.6.0"), run(port).state.value)
        assertEquals(4, port.calls.count { it.startsWith("access") })
    }

    @Test
    fun aBundleOfAnotherVersionThanAskedIsNotUploadedAndTheLineFollows() {
        val port = FakePort().apply { said = "4.6.0"; versions = { "4.4.0" } }
        val installer = run(port)
        assertEquals(listOf("voice@Getting", "fetch@Getting", "access@Getting"), port.calls)
        assertFalse("nothing of it was read", port.bodyRead)
        assertTrue(leftIn(port).isEmpty())
        val failed = InstallState.Failed("4.5.0", "Chat now has Matty Plugin 4.6.0 ready. Tap Install again in a minute.", newer = "4.6.0")
        assertEquals(failed, installer.state.value)
        // Under the offer /access gives now, and under the old one while it has not answered.
        val now = UpdateOffer("4.6.0", null, install = true)
        assertEquals(failed.sentence, updateLineOf(now, failed, "4.4.0", "Chat", start = {})!!.trouble)
        assertEquals("Matty Plugin 4.6.0 is available", updateLineOf(now, failed, "4.4.0", "Chat", start = {})!!.text)
        assertEquals(failed.sentence, updateLineOf(offer, failed, "4.4.0", "Chat", start = {})!!.trouble)
    }

    @Test
    fun aBundleThatSaysNoVersionOrOneThatCannotBeReadIsNotUploaded() {
        for (said in listOf(null, "", "v4.5.0", "4.5", "4.5.0-rc1", "04.5.0", "4.5.0 ")) {
            val port = FakePort().apply { this.said = said }
            val installer = run(port)
            assertEquals("$said", InstallState.Failed("4.5.0", "Couldn't get the update. Try again in a moment."), installer.state.value)
            assertFalse(port.calls.any { it.startsWith("upload") })
            assertFalse(port.bodyRead)
            assertTrue(leftIn(port).isEmpty())
            assertEquals("read again", 1, port.calls.count { it.startsWith("access") })
        }
    }

    @Test
    fun aBundleThatIsEmptyOrShorterThanItSaidIsNotUploaded() {
        val cases = listOf(
            "empty, of no length said" to FakePort().apply { body = { ByteArrayInputStream(ByteArray(0)) }; length = -1 },
            "empty, of length 0" to FakePort().apply { body = { ByteArrayInputStream(ByteArray(0)) }; length = 0 },
            "shorter than it said" to FakePort().apply { body = { ByteArrayInputStream(bundle.copyOf(1_000)) } },
        )
        for ((what, port) in cases) {
            val installer = run(port)
            assertEquals(what, InstallState.Failed("4.5.0", "Couldn't get the update. Try again in a moment."), installer.state.value)
            assertFalse(what, port.calls.any { it.startsWith("upload") })
            assertTrue(what, leftIn(port).isEmpty())
        }
    }

    @Test
    fun eachReadOfTheWaitIsHeldToWhatIsLeftOfItsMinute() {
        val port = FakePort().apply { versions = { "4.4.0" } }
        run(port)
        assertEquals((1..30).map { 60_000L - it * 2_000L }, port.left)
    }

    @Test
    fun theWaitEndsAfterSixtySeconds() {
        val port = FakePort().apply { versions = { "4.4.0" } }
        val installer = run(port)
        assertEquals(
            InstallState.Failed("4.5.0", "Chat took Matty Plugin 4.5.0 but is not running it yet. Look in System Console > Plugins."),
            installer.state.value,
        )
        assertEquals(60_000L, port.clock)
        assertEquals("every 2 s for 60 s", 30, port.calls.count { it.startsWith("access") })
    }

    @Test
    fun aRefusalOfTheFetchIsSaidInThePluginsWordsAndNothingIsUploaded() {
        val sentence = "Updates are off. Switch on Look for updates above and save first."
        val port = FakePort().apply { fetchFails = VillaNetException("conflict", sentence, 409, sentence) }
        val installer = run(port)
        assertEquals(InstallState.Failed("4.5.0", sentence), installer.state.value)
        assertEquals("read again, so that an Install that cannot work stops being offered", listOf("voice@Getting", "fetch@Getting", "access@Getting"), port.calls)
        assertTrue(leftIn(port).isEmpty())
    }

    @Test
    fun everyRefusalOfTheBundleReadsAccessAgainAndAFailureToReachItDoesNot() {
        val refusals = listOf(
            VillaNetException("conflict", "x", 409, "Mattermost does not take plugin uploads here."),
            VillaNetException("rate_limited", "x", 429, "A bundle was fetched less than a minute ago. Try again in a minute."),
            VillaNetException("upstream", "x", 502, null),
            VillaNetException("forbidden", "x", 403, "Only system admins can do this."),
        )
        for (e in refusals) {
            val port = FakePort().apply { fetchFails = e }
            run(port)
            assertEquals(e.code, listOf("voice@Getting", "fetch@Getting", "access@Getting"), port.calls)
        }
        val unreached = FakePort().apply { fetchFails = IOException("reset") }
        run(unreached)
        assertEquals(listOf("voice@Getting", "fetch@Getting"), unreached.calls)
    }

    @Test
    fun aRefusalOfTheUploadIsSaidInMattermostsWordsAsOneParagraph() {
        val port = FakePort().apply {
            uploadFails = ApiException(501, ApiError(message = "Plugins and/or plugin uploads have been disabled.\n\nAsk‮ your admin."), "x")
        }
        val installer = run(port)
        assertEquals(InstallState.Failed("4.5.0", "Plugins and/or plugin uploads have been disabled. Ask your admin."), installer.state.value)
        assertEquals("it ends at once", listOf("voice@Getting", "fetch@Getting", "upload@Installing"), port.calls)
        assertTrue(leftIn(port).isEmpty())
    }

    /** Mattermost answers the upload only once the new version runs: what /access says then decides. */
    @Test
    fun anUploadThatGaveNoAnswerEndsInstalledWhenTheVersionTurnsUp() {
        for (e in listOf(IOException("reset"), RuntimeException("closed on the wrong thread"), ApiException(502, null, "HTTP 502"))) {
            val port = FakePort().apply { uploadFails = e }
            val installer = run(port)
            assertEquals("$e", InstallState.Installed("4.5.0"), installer.state.value)
            assertEquals("$e", listOf("voice@Getting", "fetch@Getting", "upload@Installing", "access@Installing", "access@Installing", "access@Installing"), port.calls)
            assertTrue(leftIn(port).isEmpty())
            assertEquals("Matty Plugin 4.5.0 is installed.", updateLineOf(null, installer.state.value, "4.5.0", "Chat", start = {})!!.text)
        }
        val newer = FakePort().apply { uploadFails = IOException("reset"); versions = { n -> if (n < 1) "4.4.0" else "4.6.0" } }
        assertEquals(InstallState.Installed("4.6.0"), run(newer).state.value)
    }

    /** An end answers the tap that got it: it is said until the Plugins screen is opened again. */
    @Test
    fun anEndIsForgottenWhenThePluginsScreenIsOpenedAgain() {
        val failed = run(FakePort().apply { uploadFails = IOException("reset"); versions = { "4.4.0" } })
        assertTrue(failed.state.value is InstallState.Failed)
        failed.forgetEnd()
        assertEquals(InstallState.Idle, failed.state.value)
        val installed = run(FakePort())
        assertEquals(InstallState.Installed("4.5.0"), installed.state.value)
        installed.forgetEnd()
        assertEquals(InstallState.Idle, installed.state.value)
        // Nothing over: nothing to forget, and the offer can be started as before.
        installed.forgetEnd()
        assertEquals(InstallState.Idle, installed.state.value)
    }

    /** A proxy that turns the upload away (a 4xx with no words of Mattermost's) installed nothing: no wait. */
    @Test
    fun anUploadRefusedWithoutWordsEndsAtOnce() {
        for (status in listOf(400, 403, 413, 499)) {
            val port = FakePort().apply { uploadFails = ApiException(status, null, "HTTP $status"); versions = { "4.4.0" } }
            val installer = run(port)
            assertEquals("$status", InstallState.Failed("4.5.0", "Couldn't install the update. Try again in a moment."), installer.state.value)
            assertEquals("$status: no wait", 0L, port.clock)
            assertEquals("$status", 0, port.calls.count { it.startsWith("access") })
            assertTrue(leftIn(port).isEmpty())
        }
        // A 5xx without words may be a proxy that lost Mattermost's answer: that one waits.
        val lost = FakePort().apply { uploadFails = ApiException(504, null, "HTTP 504") }
        assertEquals(InstallState.Installed("4.5.0"), run(lost).state.value)
    }

    @Test
    fun anUploadThatGaveNoAnswerAndAVersionThatNeverMovesEndsAfterTheWait() {
        val port = FakePort().apply { uploadFails = IOException("reset"); versions = { "4.4.0" } }
        val installer = run(port)
        assertEquals(InstallState.Failed("4.5.0", "Couldn't install the update. Try again in a moment."), installer.state.value)
        assertEquals(60_000L, port.clock)
        assertEquals(30, port.calls.count { it.startsWith("access") })
        assertTrue(leftIn(port).isEmpty())
    }

    @Test
    fun withNoSentenceTheAppSaysWhichStepFailed() {
        val got = run(FakePort().apply { fetchFails = IOException("reset") })
        assertEquals(InstallState.Failed("4.5.0", "Couldn't get the update. Try again in a moment."), got.state.value)
        val put = run(FakePort().apply { uploadFails = IOException("reset"); versions = { "4.4.0" } })
        assertEquals(InstallState.Failed("4.5.0", "Couldn't install the update. Try again in a moment."), put.state.value)
        val long = run(FakePort().apply { fetchFails = VillaNetException("upstream", "x", 502, "y".repeat(1_000)) })
        assertTrue(((long.state.value as InstallState.Failed).sentence).length <= 300)
    }

    @Test
    fun aBundleOverTheLimitIsRefusedWhetherItSaysSoOrNot() {
        val said = FakePort().apply { length = 64L * 1024 * 1024 + 1 }
        assertEquals(InstallState.Failed("4.5.0", "The update is larger than 64 MB."), run(said).state.value)
        assertFalse(said.calls.any { it.startsWith("upload") })
        assertTrue(leftIn(said).isEmpty())

        val unsaid = FakePort().apply { length = -1 }
        assertEquals(InstallState.Failed("4.5.0", "The update is larger than 64 MB."), run(unsaid, limit = bundle.size - 1L).state.value)
        assertFalse(unsaid.calls.any { it.startsWith("upload") })
        assertTrue(leftIn(unsaid).isEmpty())

        val exact = FakePort().apply { length = -1 }
        assertEquals(InstallState.Installed("4.5.0"), run(exact, limit = bundle.size.toLong()).state.value)
        assertArrayEquals(bundle, exact.uploaded)
    }

    @Test
    fun aFileLeftByARunThatEndedEarlyIsRemovedWhenTheNextStarts() {
        val port = FakePort()
        port.dir.mkdirs()
        val left = File(port.dir, "bundle-123.tar.gz").apply { writeBytes(ByteArray(10)) }
        run(port)
        assertFalse(left.exists())
        assertTrue("the fetch had only its own file there", port.fetchedInto != null && port.fetchedInto != left)
    }

    @Test
    fun aSecondTapWhileOneRunsDoesNothing() = runBlocking {
        val port = FakePort().apply { fetchHeld = CompletableDeferred() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val installer = PluginInstaller(port, scope, io = Dispatchers.Unconfined)
        port.installer = installer
        assertTrue(installer.start(offer))
        assertTrue(installer.running)
        assertFalse(installer.start(offer))
        assertFalse(installer.start(offer))
        port.fetchHeld!!.complete(Unit)
        while (installer.running) yield()
        assertEquals(1, port.calls.count { it.startsWith("fetch") })
        assertEquals(1, port.calls.count { it.startsWith("upload") })
        // Over, it can start again.
        assertTrue(installer.start(offer))
    }

    @Test
    fun nothingStartsWhenInstallIsNotOffered() = runBlocking {
        val port = FakePort()
        val installer = PluginInstaller(port, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), io = Dispatchers.Unconfined)
        port.installer = installer
        assertFalse(installer.start(offer.copy(install = false)))
        assertTrue(port.calls.isEmpty())
        assertEquals(InstallState.Idle, installer.state.value)
    }

    @Test
    fun leavingTheServerInTheMiddleRemovesTheFile() = runBlocking {
        val port = FakePort().apply { versions = { "4.4.0" } }
        val held = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val installer = PluginInstaller(object : InstallPort by port {
            override suspend fun upload(file: File, name: String) { port.uploadedFile = file; held.await() }
        }, scope, io = Dispatchers.Unconfined)
        port.installer = installer
        assertTrue(installer.start(offer))
        assertTrue(port.uploadedFile!!.exists())
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
        assertFalse(port.uploadedFile!!.exists())
        assertEquals(InstallState.Idle, installer.state.value)
    }

    @Test
    fun whatAStoppedAppLeftIsRemoved() {
        val cache = tmp.newFolder("cache")
        val dir = File(cache, "plugin-update").apply { mkdirs() }
        File(dir, "bundle-1.tar.gz").writeBytes(ByteArray(10))
        File(dir, "odd").apply { mkdirs(); File(this, "x").writeText("x") }
        val other = File(cache, "coil").apply { mkdirs(); File(this, "keep").writeText("keep") }
        removeLeftUpdates(cache)
        assertTrue(dir.listFiles()!!.isEmpty())
        assertTrue("nothing else is touched", File(other, "keep").exists())
        removeLeftUpdates(tmp.newFolder("empty"))
    }

    private val main = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "app/src/main/java/ceo/villa/matty") }.first { it.isDirectory }

    private fun codeOf(file: String) = File(main, file).readLines().map { it.trim() }
        .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    /** The lines of the block that starts at the code line beginning with [signature]. */
    private fun block(lines: List<String>, signature: String): List<String> {
        val start = lines.indexOfFirst { it.startsWith(signature) }.also { assertTrue("$signature is there", it >= 0) }
        var depth = 0
        var opened = false
        for (i in start until lines.size) {
            for (ch in lines[i]) {
                if (ch == '{') { depth++; opened = true } else if (ch == '}') depth--
            }
            if (opened && depth == 0) return lines.subList(start, i + 1)
        }
        error("unbalanced $signature")
    }

    /**
     * The module's port, held by its source: voice left as when the server closes, the bundle in
     * the cache folder, the two clients, and nothing done for a server the module is no longer
     * attached to.
     */
    @Test
    fun theModuleWiresTheInstallToVoiceTheCacheAndTheSignedInServer() {
        val plugin = codeOf("plugins/villanet/VillaNetPlugin.kt")
        assertTrue(plugin.any { it == "override fun leaveVoice() { if (repo === r) voice.leave() }" })
        assertTrue(plugin.any { it == "override val dir = pluginUpdateDir(c.app.cacheDir)" })
        val fetch = block(plugin, "override suspend fun fetchBundle(")
        assertEquals("val a = api?.takeIf { repo === r } ?: throw java.io.IOException(\"Not attached\")", fetch[1])
        assertEquals("a.updateBundle(read)", fetch[2])
        assertTrue(plugin.any { "r.client.uploadPlugin(file, name)" in it })
        val running = block(plugin, "override suspend fun runningVersion()")
        assertTrue(running.contains("val a = api?.takeIf { repo === r } ?: return null"))
        assertTrue(running.indexOf("val a = api?.takeIf { repo === r } ?: return null") < running.indexOf("readAccess(a)"))
        assertTrue(running.contains("return _access.value?.version?.takeIf { api === a }"))
    }

    /** What an app stopped in the middle left goes when the app starts and with a sign-out's wipe. */
    @Test
    fun theAppRemovesALeftBundleWhenItStartsAndWhenItWipes() {
        val app = codeOf("MattyApp.kt")
        val start = block(app, "override fun onCreate()")
        assertTrue(start.any { "removeLeftUpdates(cacheDir)" in it && "Dispatchers.IO" in it })
        val wipe = block(app, "private fun wipeDiskCaches()")
        assertTrue(wipe.any { "removeLeftUpdates(app.cacheDir)" in it })
    }

    // ---- the two requests, through the app's own clients (answered here, nothing leaves) ----

    private fun answering(seen: AtomicReference<Pair<Request, Long>>, answer: (Request) -> Response) = OkHttpClient.Builder().addInterceptor { chain ->
        seen.set(chain.request() to chain.readTimeoutMillis().toLong())
        answer(chain.request())
    }.build()

    private fun ok(r: Request, body: ByteArray) = Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .body(body.toResponseBody("application/gzip".toMediaType())).build()

    @Test
    fun theBundleIsAskedOfThePluginOnTheSignedInServerWithAThreeMinuteReadAndStreamedToTheFile() {
        val seen = AtomicReference<Pair<Request, Long>>()
        val mm = MattermostClient("https://chat.example.test", "token", answering(seen) {
            ok(it, bundle).newBuilder().header("X-Matty-Update-Version", "4.5.0").build()
        })
        val api = VillaNetApi(mm)
        val to = tmp.newFile()
        var said = 0L
        var version: String? = null
        runBlocking { api.updateBundle { body, length, v -> said = length; version = v; PluginInstaller.copyAtMost(body, to, 64L * 1024 * 1024) } }
        val (r, readMs) = seen.get()
        assertEquals("POST", r.method)
        assertEquals("https://chat.example.test/plugins/ceo.villa.matty/api/v1/admin/update/bundle", r.url.toString())
        assertEquals("Bearer token", r.header("Authorization"))
        assertEquals(180_000L, readMs)
        assertEquals("the whole call is over within four minutes", 240_000, api.bundleHttp.callTimeoutMillis)
        assertEquals(bundle.size.toLong(), said)
        assertEquals("4.5.0", version)
        assertArrayEquals(bundle, to.readBytes())
        // The plugin's other calls keep the client's own read timeout.
        runBlocking { runCatching { api.access() } }
        assertEquals(mm.apiHttp.readTimeoutMillis.toLong(), seen.get().second)
    }

    @Test
    fun theBundlesRefusalCarriesThePluginsSentence() {
        val seen = AtomicReference<Pair<Request, Long>>()
        val sentence = "No newer version is known. Press Check now first."
        val mm = MattermostClient("https://chat.example.test", "token", answering(seen) {
            Response.Builder().request(it).protocol(Protocol.HTTP_1_1).code(409).message("Conflict")
                .body("""{"error":"$sentence","code":"conflict"}""".toResponseBody("application/json".toMediaType())).build()
        })
        val e = runCatching { runBlocking { VillaNetApi(mm).updateBundle { _, _, _ -> error("not read") } } }.exceptionOrNull()
        assertTrue(e is VillaNetException)
        assertEquals(sentence, (e as VillaNetException).serverMessage)
    }

    @Test
    fun thePluginStoppingInTheMiddleIsShownLikeAnyRefusal() {
        val seen = AtomicReference<Pair<Request, Long>>()
        val sentence = "The plugin stopped while it got the bundle. Try again once it is back."
        val mm = MattermostClient("https://chat.example.test", "token", answering(seen) {
            Response.Builder().request(it).protocol(Protocol.HTTP_1_1).code(503).message("Service Unavailable")
                .body("""{"error":"$sentence","code":"stopping"}""".toResponseBody("application/json".toMediaType())).build()
        })
        val e = runCatching { runBlocking { VillaNetApi(mm).updateBundle { _, _, _ -> error("not read") } } }.exceptionOrNull()
        assertEquals(sentence, (e as VillaNetException).serverMessage)
        assertEquals("stopping", e.code)
        val port = FakePort().apply { fetchFails = e }
        assertEquals(InstallState.Failed("4.5.0", sentence), run(port).state.value)
    }

    /** A body larger than the limit: fewer bytes than it holds are read before it is refused. */
    @Test
    fun theBodyIsStreamedAndALargeOneIsRefusedBeforeItIsReadWhole() {
        val big = 4L * 1024 * 1024
        var served = 0L
        val source = object : okio.Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (served >= big) return -1
                val n = minOf(byteCount, 8_192L, big - served)
                sink.write(ByteArray(n.toInt()))
                served += n
                return n
            }
            override fun timeout() = okio.Timeout.NONE
            override fun close() {}
        }
        val seen = AtomicReference<Pair<Request, Long>>()
        val mm = MattermostClient("https://chat.example.test", "token", answering(seen) {
            Response.Builder().request(it).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("X-Matty-Update-Version", "4.5.0")
                .body(source.buffer().asResponseBody("application/gzip".toMediaType(), -1)).build()
        })
        val limit = 256L * 1024
        var readWhenRefused = -1L
        val e = runCatching {
            runBlocking {
                VillaNetApi(mm).updateBundle { body, _, _ ->
                    try { PluginInstaller.copyAtMost(body, tmp.newFile(), limit) } catch (e: IOException) { readWhenRefused = served; throw e }
                }
            }
        }.exceptionOrNull()
        assertTrue(e is IOException)
        assertTrue("refused after the limit: $readWhenRefused", readWhenRefused > limit)
        assertTrue("and long before the end: $readWhenRefused", readWhenRefused < big / 4)
    }

    @Test
    fun theUploadIsMattermostsOwnWithThePluginFieldAndForce() {
        val seen = AtomicReference<Pair<Request, Long>>()
        val mm = MattermostClient("https://chat.example.test", "token", answering(seen) {
            Response.Builder().request(it).protocol(Protocol.HTTP_1_1).code(201).message("Created")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        })
        val file = tmp.newFile().apply { writeBytes(bundle) }
        runBlocking { mm.uploadPlugin(file, "ceo.villa.matty-4.5.0.tar.gz") }
        val r = seen.get().first
        assertEquals("Mattermost's answer is waited for up to two minutes", 120_000L, seen.get().second)
        assertEquals("POST", r.method)
        assertEquals("https://chat.example.test/api/v4/plugins", r.url.toString())
        assertEquals("Bearer token", r.header("Authorization"))
        val body = r.body as okhttp3.MultipartBody
        assertEquals(2, body.size)
        val plugin = body.part(0)
        assertEquals("form-data; name=\"plugin\"; filename=\"ceo.villa.matty-4.5.0.tar.gz\"", plugin.headers!!["Content-Disposition"])
        assertArrayEquals(bundle, Buffer().also { plugin.body.writeTo(it) }.readByteArray())
        assertEquals("form-data; name=\"force\"", body.part(1).headers!!["Content-Disposition"])
        assertEquals("true", Buffer().also { body.part(1).body.writeTo(it) }.readUtf8())
    }

    @Test
    fun theUploadsRefusalCarriesMattermostsSentence() {
        val seen = AtomicReference<Pair<Request, Long>>()
        val mm = MattermostClient("https://chat.example.test", "token", answering(seen) {
            Response.Builder().request(it).protocol(Protocol.HTTP_1_1).code(501).message("Not Implemented")
                .body("""{"id":"app.plugin.upload_disabled.app_error","message":"Plugins and/or plugin uploads have been disabled.","status_code":501}""".toResponseBody("application/json".toMediaType())).build()
        })
        val e = runCatching { runBlocking { mm.uploadPlugin(tmp.newFile(), "x.tar.gz") } }.exceptionOrNull()
        assertEquals("Plugins and/or plugin uploads have been disabled.", (e as ApiException).serverMessage)
    }
}
