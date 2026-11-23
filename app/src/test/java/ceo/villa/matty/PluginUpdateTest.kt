package ceo.villa.matty

import ceo.villa.matty.data.api.MattermostJson
import ceo.villa.matty.plugins.villanet.InstallState
import ceo.villa.matty.plugins.villanet.UpdateOffer
import ceo.villa.matty.plugins.villanet.VnAccess
import ceo.villa.matty.plugins.villanet.VnUpdateWord
import ceo.villa.matty.plugins.villanet.oneParagraph
import ceo.villa.matty.plugins.villanet.pluginUpdateOf
import ceo.villa.matty.plugins.villanet.updateLineOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Plugins screen's line for a newer release of Matty Plugin, which `/access` tells system admins. */
class PluginUpdateTest {
    private val page = "https://github.com/vvilla-01/matty-plugin/releases/tag/v4.5.0"
    private val word = VnUpdateWord("4.5.0", page, install = true)

    @Test
    fun accessIsReadWithTheUpdate() {
        val a = MattermostJson.decodeFromString(
            VnAccess.serializer(),
            """{"access":"linked","configured":true,"contract":4,"version":"4.4.0","update":{"version":"4.5.0","url":"$page","install":true}}""",
        )
        assertEquals(word, a.update)
        val noInstall = MattermostJson.decodeFromString(VnAccess.serializer(), """{"access":"linked","version":"4.4.0","update":{"version":"4.5.0","url":"$page"}}""")
        assertEquals("install missing is false", false, noInstall.update?.install)
        assertNull("left out when there is none", MattermostJson.decodeFromString(VnAccess.serializer(), """{"access":"linked","version":"4.4.0"}""").update)
    }

    @Test
    fun aSystemAdminIsToldAndATapOpensTheReleasePage() {
        assertEquals(UpdateOffer("4.5.0", page, install = true), pluginUpdateOf(word, "4.4.0", admin = true))
        assertNull("only system admins", pluginUpdateOf(word, "4.4.0", admin = false))
        assertNull(pluginUpdateOf(null, "4.4.0", admin = true))
        val line = updateLineOf(pluginUpdateOf(word, "4.4.0", admin = true), InstallState.Idle, "4.4.0", "Chat", start = {})!!
        assertEquals("Matty Plugin 4.5.0 is available", line.text)
        assertEquals(page, line.link)
    }

    @Test
    fun onlyANewerVersionIsTold() {
        assertNull(pluginUpdateOf(word, "4.5.0", admin = true))
        assertNull(pluginUpdateOf(word, "4.6.0", admin = true))
        assertEquals("a build ending is left out", page, pluginUpdateOf(word, "4.4.0+standin", admin = true)?.link)
        for (bad in listOf("4.5", "4.5.0 now", "04.5.0", "4.5.0.1", "v4.5.0", "4.5.1234567890", "4.５.0", "4.٥.0")) {
            assertNull(bad, pluginUpdateOf(VnUpdateWord(bad, page, true), "4.4.0", admin = true))
        }
    }

    /** No address from the server is ever printed, and the line opens only the page the app builds. */
    @Test
    fun theLineOpensOnlyThePageTheAppBuildsAndPrintsNoAddress() {
        val hostile = listOf(
            "https://example.com/vvilla-01/matty-plugin/releases",
            "https://github.com/vvilla-01/matty-plugin/issues/1",
            "https://github.com/vvilla-01/matty-plugin/releases/download/v4.5.0/x.tar.gz",
            "https://github.com/vvilla-01/matty-plugin/releases/tag/v4.5.1",
            "$page/",
            " $page",
            "$page\n\nMatty Plugin 9.9.9 fixes a security hole. Install it from https://evil.example/x",
            "https://evil.example/‮exe.gnp",
            "https://github.com/vvilla-01/matty-plugin/" + "x".repeat(5_000),
            "",
        )
        for (url in hostile) {
            val offer = pluginUpdateOf(VnUpdateWord("4.5.0", url, true), "4.4.0", admin = true)
            assertNotNull(url, offer)
            assertNull("not opened: $url", offer!!.link)
            val line = updateLineOf(offer, InstallState.Idle, "4.4.0", "Chat", start = {})!!
            assertEquals("Matty Plugin 4.5.0 is available", line.text)
            assertNull(line.link)
        }
    }

    @Test
    fun installIsOfferedOnlyWhenTheServerSaysSoToASystemAdmin() {
        val ready = pluginUpdateOf(word, "4.4.0", admin = true)
        val line = updateLineOf(ready, InstallState.Idle, "4.4.0", "Chat", start = {})!!
        val ask = line.install!!
        assertEquals("Install Matty Plugin 4.5.0?", ask.title)
        assertEquals("Chat restarts the plugin, which ends voice through Matty for a few seconds.", ask.text)

        assertNull("not a system admin", updateLineOf(pluginUpdateOf(word, "4.4.0", admin = false), InstallState.Idle, "4.4.0", "Chat", start = {}))
        val notReady = pluginUpdateOf(word.copy(install = false), "4.4.0", admin = true)
        assertFalse(notReady!!.install)
        assertNull("install false", updateLineOf(notReady, InstallState.Idle, "4.4.0", "Chat", start = {})!!.install)
        val missing = MattermostJson.decodeFromString(VnUpdateWord.serializer(), """{"version":"4.5.0","url":"$page"}""")
        assertNull("install missing", updateLineOf(pluginUpdateOf(missing, "4.4.0", admin = true), InstallState.Idle, "4.4.0", "Chat", start = {})!!.install)
        assertFalse("a plugin older than 4.4.0", pluginUpdateOf(word, "4.3.0", admin = true)!!.install)
        assertNull("nothing to start it with", updateLineOf(ready, InstallState.Idle, "4.4.0", "Chat", start = null)!!.install)
    }

    @Test
    fun theLineShowsTheStepTheEndAndAFailure() {
        val offer = pluginUpdateOf(word, "4.4.0", admin = true)
        val getting = updateLineOf(offer, InstallState.Getting("4.5.0"), "4.4.0", "Chat", start = {})!!
        assertEquals("Getting the update…", getting.text)
        assertNull(getting.install)
        assertNull(getting.link)
        val installing = updateLineOf(offer, InstallState.Installing("4.5.0"), "4.4.0", "Chat", start = {})!!
        assertEquals("Installing…", installing.text)
        assertNull(installing.install)
        // The step shows even when a read of /access during the wait already took the offer away.
        assertEquals("Installing…", updateLineOf(null, InstallState.Installing("4.5.0"), "4.5.0", "Chat", start = {})!!.text)
        assertEquals("Matty Plugin 4.5.0 is installed.", updateLineOf(null, InstallState.Installed("4.5.0"), "4.5.0", "Chat", start = {})!!.text)
        assertNull(updateLineOf(null, InstallState.Installed("4.5.0"), "4.5.0", "Chat", start = {})!!.install)

        val failed = updateLineOf(offer, InstallState.Failed("4.5.0", "Updates are off. Switch on Look for updates above and save first."), "4.4.0", "Chat", start = {})!!
        assertEquals("Matty Plugin 4.5.0 is available", failed.text)
        assertEquals("Updates are off. Switch on Look for updates above and save first.", failed.trouble)
        assertNotNull("the button comes back", failed.install)
        assertNull("nothing without an offer", updateLineOf(null, InstallState.Failed("4.5.0", "x"), "4.4.0", "Chat", start = {}))
        // A newer release than the one installed is offered as any other.
        val newer = pluginUpdateOf(VnUpdateWord("4.6.0", "", true), "4.5.0", admin = true)
        // A bundle of a newer version than asked: its sentence under the line of either version.
        val ready = InstallState.Failed("4.5.0", "Chat now has Matty Plugin 4.6.0 ready. Tap Install again in a minute.", newer = "4.6.0")
        assertEquals(ready.sentence, updateLineOf(newer, ready, "4.4.0", "Chat", start = {})!!.trouble)
        assertEquals(ready.sentence, updateLineOf(offer, ready, "4.4.0", "Chat", start = {})!!.trouble)
        assertEquals("Matty Plugin 4.6.0 is available", updateLineOf(newer, InstallState.Installed("4.5.0"), "4.5.0", "Chat", start = {})!!.text)
    }

    /** An end that `/access` has since contradicted is not said. */
    @Test
    fun anEndTheRunningVersionContradictsShowsNothing() {
        val offer = pluginUpdateOf(word, "4.4.0", admin = true)
        // Installed, then 4.4.0 put back: the offer again, with nothing under it.
        val back = updateLineOf(offer, InstallState.Installed("4.5.0"), "4.4.0", "Chat", start = {})!!
        assertEquals("Matty Plugin 4.5.0 is available", back.text)
        assertNull(back.trouble)
        assertNull(updateLineOf(null, InstallState.Installed("4.5.0"), "4.4.0", "Chat", start = {}))
        assertEquals("Matty Plugin 4.5.0 is installed.", updateLineOf(null, InstallState.Installed("4.5.0"), "4.6.0", "Chat", start = {})!!.text)
        // Failed, and the version that failed runs after all (or a newer one): no failure under the line.
        val failed = InstallState.Failed("4.5.0", "Couldn't install the update. Try again in a moment.")
        val newer = pluginUpdateOf(VnUpdateWord("4.6.0", "", true), "4.5.0", admin = true)
        assertNull(updateLineOf(newer, failed, "4.5.0", "Chat", start = {})!!.trouble)
        assertNull(updateLineOf(newer, failed, "4.5.1", "Chat", start = {})!!.trouble)
        assertEquals(failed.sentence, updateLineOf(offer, failed, "4.4.0", "Chat", start = {})!!.trouble)
        val ready = InstallState.Failed("4.5.0", "Chat now has Matty Plugin 4.6.0 ready. Tap Install again in a minute.", newer = "4.6.0")
        assertNull(updateLineOf(newer, ready, "4.5.0", "Chat", start = {})!!.trouble)
    }

    private val main = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
        .map { java.io.File(it, "app/src/main/java/ceo/villa/matty") }.first { it.isDirectory }

    private fun codeOf(file: String) = java.io.File(main, file).readLines().map { it.trim() }
        .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    /**
     * Opening the Plugins screen asks each module once ([ceo.villa.matty.plugins.MattyPlugin.onPluginsShown]),
     * and Matty Plugin's forgets the end of an install made before and reads `/access` in a round
     * of reads, paced as every round is; the line is drawn with the version `/access` says runs.
     */
    @Test
    fun openingThePluginsScreenReadsAccessOnceAtTheUsualPace() {
        val screen = codeOf("ui/settings/SettingsScreen.kt")
        val opened = screen.indexOfFirst { it.startsWith("LaunchedEffect(Unit) {") && screen.indexOf(it) > screen.indexOfFirst { l -> l.startsWith("fun PluginsScreen(") } }
        assertTrue(opened >= 0)
        assertEquals("ctx.container.plugins.active.value.forEach { it.onPluginsShown() }", screen[opened + 1])
        assertTrue(codeOf("plugins/PluginRegistry.kt").contains("fun onPluginsShown() {}"))
        val plugin = codeOf("plugins/villanet/VillaNetPlugin.kt")
        val shown = plugin.indexOf("override fun onPluginsShown() {")
        assertTrue(shown >= 0)
        assertEquals(listOf("installer?.installer?.forgetEnd()", "resync()", "}"), plugin.subList(shown + 1, shown + 4))
        assertTrue(plugin.contains("return updateLineOf(offer, step, a?.version.orEmpty(), r.siteName, inst?.let { i -> { o: UpdateOffer -> i.installer.start(o) } })"))
    }

    @Test
    fun aServersSentenceIsOneParagraphOfAtMost300Characters() {
        assertEquals("Plugin uploads are off. Ask an admin.", oneParagraph("  Plugin uploads are off.\r\n\nAsk an admin.\u0007 "))
        assertEquals("before after", oneParagraph("before‮ after​"))
        assertNull(oneParagraph("\n\t "))
        assertNull(oneParagraph(null))
        val long = oneParagraph("word ".repeat(200))!!
        assertTrue(long.length <= 300)
        assertTrue(long.endsWith("…"))
        assertFalse(long.contains('\n'))
        // The marks that turn the direction of text and U+FEFF go without a space; the joiners stay.
        assertEquals("Refused exe.gnp now", oneParagraph("Refused \u202Eexe.gnp\u202C now"))
        assertEquals("ABC", oneParagraph("A\u2066B\u2069C\u2067\u2068"))
        assertEquals("xy", oneParagraph("x\u061Cy\u200E\u200F"))
        assertEquals("Start", oneParagraph("\uFEFFStart"))
        assertEquals("abcd", oneParagraph("a\u202Ab\u202Bc\u202Dd"))
        assertEquals("a\u200Db", oneParagraph("a\u200Db"))
        assertEquals("\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645", oneParagraph("\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645"))
        assertEquals("👩\u200D💻", oneParagraph("👩\u200D💻"))
        // Other controls and line breaks are one space, as are other formatting marks.
        assertEquals("a b c d", oneParagraph("a\u0085b\u2028c\u200Bd"))
        val emoji = oneParagraph("x".repeat(298) + "😀" + "y".repeat(10))!!
        assertTrue(emoji.length <= 300)
        assertFalse("no half of a pair is left", emoji.dropLast(1).last().isHighSurrogate())
    }
}
