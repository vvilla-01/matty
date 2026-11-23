package ceo.villa.matty

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where a server's minimum Matty is joined to the rest of the app, held by the source itself (as
 * PushWiringTest holds push): what the plugin says reaches the keeper, the keeper's word is kept
 * with the saved server, and while the server is closed nothing its plugin would refuse is asked.
 * Only code is matched, never a comment.
 */
class AppNeedWiringTest {
    private val main = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "app/src/main/java/ceo/villa/matty") }.first { it.isDirectory }

    /** [path]'s code: comment lines left out, and what follows `//` on a line. */
    private fun code(path: String): String = File(main, path).readLines()
        .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
        .joinToString("\n") { line -> line.replace(Regex("""\s//\s.*$"""), "") }

    private val plugin = code("plugins/villanet/VillaNetPlugin.kt")
    private val api = code("plugins/villanet/VillaNetApi.kt")
    private val center = code("push/PushCenter.kt")
    private val app = code("MattyApp.kt")
    private val repo = code("data/repo/ChatRepository.kt")
    private val store = code("data/session/SessionStore.kt")
    private val gate = code("ui/lock/UpdateGate.kt")
    private val lock = code("ui/lock/LockGate.kt")

    private fun inOrder(text: String, vararg parts: String) {
        var at = -1
        for (p in parts) {
            val next = text.indexOf(p, at + 1)
            assertTrue("'$p' after what comes before it", next > at)
            at = next
        }
    }

    private fun body(text: String, signature: String): String {
        val start = text.indexOf(signature).also { assertTrue("$signature is there", it >= 0) }
        val open = text.indexOf('{', start)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) { '{' -> depth++; '}' -> if (--depth == 0) return text.substring(open, i + 1) }
        }
        error("unbalanced $signature")
    }

    @Test fun theCheckItselfOnlyMatchesCode() {
        val lines = listOf("    // a.access()", "     * a.access()", "    val x = 1 // a.access()", "    val now = a.access()")
        val f = File.createTempFile("code", ".kt").apply { writeText(lines.joinToString("\n")); deleteOnExit() }
        val kept = f.readLines().filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n") { it.replace(Regex("""\s//\s.*$"""), "") }
        assertTrue(kept.lines().count { "a.access()" in it } == 1)
    }

    @Test fun everyAccessReadTellsTheKeeperWhatItNamed() {
        val read = body(plugin, "private suspend fun readAccess(a: VillaNetApi)")
        // The ticket is taken before the read, and handed back with its answer.
        inOrder(read, "val ticket = needs?.asking()", "a.access()", "needs.answered(ticket, now.app?.minimum, VILLANET_ID)")
    }

    @Test fun everyRefusalTellsTheKeeper() {
        assertTrue("onOutdated = { sentence -> outdatedHeard(this, sentence) }" in body(plugin, "override fun onAttach(repo: ChatRepository)"))
        assertTrue("r.appNeeds.refused(sentence, VILLANET_ID)" in body(plugin, "private fun outdatedHeard("))
        // The plugin's client hears the code on its requests and on its pictures.
        assertTrue("APP_OUTDATED" in api)
        assertTrue(Regex("""onOutdated\??\.invoke\(|onOutdated\(""").containsMatchIn(api))
    }

    @Test fun voiceAndAStreamEndWhenTheServerClosesAndWhatWasHeldIsAskedWhenItOpens() {
        val attach = body(plugin, "override fun onAttach(repo: ChatRepository)")
        inOrder(attach, "repo.appNeed.map { it != null }.distinctUntilChanged().collect { closed ->", "if (closed) { voice.leave(); stop() }",
            "else if (wasClosed) { resync(); c.push.payOwed(repo.server.id) }")
    }

    @Test fun nothingThePluginWouldRefuseIsAskedWhileTheServerIsClosed() {
        assertTrue("private fun closed(): Boolean = repo?.appNeed?.value != null" in plugin)
        // The round reads /access (which the plugin answers) and goes no further.
        inOrder(body(plugin, "private suspend fun round()"), "readAccess(a)", "|| closed()) return", "refreshStreams()")
        inOrder(body(plugin, "fun refreshAccounts()"), "if (closed()) return", "a.accounts(")
        val front = body(plugin, "override fun onForeground(foreground: Boolean)")
        assertTrue("if (!closed()) shelf?.lookedAt()" in front)
        assertTrue("if (_outdated.value == null && !closed()) boardShelf?.cameBack()" in front)
        assertTrue("!closed()) boardShelf?.lookedAt()" in body(plugin, "fun boardsLookedAt()"))
        assertTrue("!closed() }" in body(plugin, "suspend fun boardsProfileOpen()"))
        assertTrue("_outdated.value == null && !closed()) {" in attach())
    }

    private fun attach() = body(plugin, "override fun onAttach(repo: ChatRepository)")

    @Test fun pushSendsAClosedServerNothingButItsDelete() {
        assertTrue("closesTo(c.sessionStore.servers.value.firstOrNull { it.id == serverId }?.appNeed, ceo.villa.matty.BuildConfig.VERSION_NAME)" in center)
        inOrder(body(center, "private suspend fun sync("), "one.plugin.access()", "if (closed(server.id)) return@withLock", "one.plugin.pushState()")
        inOrder(body(center, "private suspend fun putLocked("), "closed(serverId)) return", "one.plugin.pushRegister(")
        inOrder(body(center, "private suspend fun confirmLocked("), "closed(serverId)) return", "one.plugin.pushConfirm(")
        inOrder(body(center, "private fun awaitHello("), "if (closed(serverId)) return@withLock", "c.sessions.oneOff(", "one.plugin.pushState()")
        // The DELETE is the one push route the plugin answers an older Matty: it is not held.
        assertFalse("closed(" in body(center, "private suspend fun payDeleteLocked("))
    }

    @Test fun theNeedIsKeptWithTheSavedServer() {
        assertTrue("rememberAppNeed = { need -> c.sessionStore.rememberAppNeed(server.id, need) }" in app)
        // The saved copy wins, a need saved as gone included.
        assertTrue("appNeed = if (saved != null) saved.appNeed else server.appNeed" in app)
        assertTrue("AppNeedKeeper(server.appNeed, ceo.villa.matty.BuildConfig.VERSION_NAME, rememberAppNeed)" in repo)
        val remember = body(store, "fun rememberAppNeed(id: String, need: AppNeed?)")
        inOrder(remember, "it.copy(appNeed = need)", "persist(list)")
    }

    @Test fun thePagesReadIsPacedAndReadsThePluginListWhenTheModuleIsNotThere() {
        val check = body(plugin, "suspend fun checkAppVersion(r: ChatRepository)")
        inOrder(check, "delay(appCheckPace.claim())", "if (a == null || repo !== r) r.refreshPlugins() else readAccess(a)")
        assertTrue("ReadPace(APP_CHECK_GAP_MS" in plugin)
        // The page asks it as it appears, on coming back to the front, and on Check again.
        assertTrue("module.checkAppVersion(repo)" in gate)
        assertTrue("if (event == Lifecycle.Event.ON_START) check()" in gate)
        assertTrue("onCheck = { check() }" in gate)
    }

    @Test fun bothClosedPagesScroll() {
        assertTrue("verticalScroll(rememberScrollState())" in body(lock, "internal fun ClosedPage("))
        assertTrue("ClosedPage {" in body(lock, "private fun LockScreen("))
        assertTrue("ClosedPage {" in body(gate, "private fun UpdateScreen("))
    }
}
