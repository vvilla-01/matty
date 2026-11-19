package ceo.villa.matty

import ceo.villa.matty.data.api.MattermostJson
import ceo.villa.matty.data.repo.enabledPluginId
import ceo.villa.matty.plugins.MattyPlugin
import ceo.villa.matty.plugins.linkTarget
import ceo.villa.matty.plugins.villanet.AccessRefusal
import ceo.villa.matty.plugins.villanet.PREDECESSOR_ID
import ceo.villa.matty.plugins.villanet.VILLANET_ID
import ceo.villa.matty.plugins.villanet.VillaNetException
import ceo.villa.matty.plugins.villanet.accessRefusal
import ceo.villa.matty.plugins.villanet.outOfStepWords
import ceo.villa.matty.plugins.villanet.panelArgsOf
import ceo.villa.matty.ui.settings.knownPlugins
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The plugin's id, the one it had before, and what each opens. */
class MattyPluginIdTest {
    /** The module as the registry sees it: its id, its earlier one, and its links. */
    private val module = object : MattyPlugin {
        override val id = VILLANET_ID
        override val name = "Matty"
        override val description = ""
        override val formerIds = setOf(PREDECESSOR_ID)
        override fun panelArgsForLink(path: String) = panelArgsOf(path)
    }
    private val other = object : MattyPlugin {
        override val id = "com.example.other"
        override val name = "Other"
        override val description = ""
        override fun panelArgsForLink(path: String) = mapOf("other" to path)
    }
    private val site = "https://chat.example.com"

    @Test
    fun theIdsAreThePluginsNowAndBefore() {
        assertEquals("ceo.villa.matty", VILLANET_ID)
        assertEquals("ceo.villa.villanet", PREDECESSOR_ID)
    }

    @Test
    fun aLinkUnderTheIdOpensTheClipOrThePlan() {
        val clip = linkTarget(listOf(other, module), "$site/plug/ceo.villa.matty/clip/00000000000000a1", site)
        assertSame(module, clip?.first)
        assertEquals(mapOf("clip" to "00000000000000a1"), clip?.second)
        assertEquals(mapOf("plan" to "p1"), linkTarget(listOf(module), "$site/plug/ceo.villa.matty/plan/p1", site)?.second)
    }

    @Test
    fun aLinkPostedBeforeTheNewIdOpensAsTheNewOnesDo() {
        for (path in listOf("/clip/00000000000000a1", "/plan/p1", "/clip/not-a-clip", "")) {
            val now = linkTarget(listOf(module), "$site/plug/ceo.villa.matty$path", site)
            val before = linkTarget(listOf(module), "$site/plug/ceo.villa.villanet$path", site)
            assertSame(path, module, before?.first)
            assertEquals(path, now?.second, before?.second)
        }
        // Server-relative, as a post's text may have it.
        assertEquals(mapOf("clip" to "00000000000000a1"), linkTarget(listOf(module), "/plug/ceo.villa.villanet/clip/00000000000000a1", site)?.second)
        // Without the module (the server runs no Matty plugin) it opens nothing natively.
        assertNull(linkTarget(listOf(other), "$site/plug/ceo.villa.villanet/clip/00000000000000a1", site))
    }

    @Test
    fun aModuleThatHasTheIdComesBeforeOneThatHadIt() {
        val now = object : MattyPlugin {
            override val id = PREDECESSOR_ID
            override val name = "Now"
            override val description = ""
            override fun panelArgsForLink(path: String) = emptyMap<String, String>()
        }
        assertSame(now, linkTarget(listOf(module, now), "$site/plug/ceo.villa.villanet/clip/00000000000000a1", site)?.first)
    }

    @Test
    fun theKnownPluginsTableNamesNeitherId() {
        // The plugin has a module, and a server running one under the earlier id is shown as any
        // other plugin Matty doesn't know.
        assertFalse(knownPlugins.containsKey(VILLANET_ID))
        assertFalse(knownPlugins.containsKey(PREDECESSOR_ID))
    }

    @Test
    fun refusalsOfAccess() {
        assertEquals(AccessRefusal.SignedOut, accessRefusal(VillaNetException("", "x", 401), "4.0.0"))
        assertEquals(AccessRefusal.TooOld, accessRefusal(VillaNetException("", "x", 404), ""))
        assertEquals(AccessRefusal.TooOld, accessRefusal(VillaNetException("locked", "x", 403), ""))
        assertEquals(AccessRefusal.Stands, accessRefusal(VillaNetException("", "x", 404), "4.0.0"))
        // A 503 is a server error like any other: what was known stands.
        assertEquals(AccessRefusal.Stands, accessRefusal(VillaNetException("", "x", 503), "4.0.0"))
        assertEquals(AccessRefusal.Stands, accessRefusal(VillaNetException("", "x", 503), ""))
    }

    @Test
    fun beingOutOfStepIsAboutThePluginAsSoftware() {
        assertEquals(
            "This server runs Matty Plugin 3.8.0. This version of Matty works with Matty Plugin 4.0.0 or newer.",
            outOfStepWords("3.8.0"),
        )
        assertTrue(outOfStepWords("").startsWith("This server runs a version of Matty Plugin older than this app works with."))
    }

    @Test
    fun aPluginSwitchedOnAgainIsKnownByItsManifestsId() {
        fun data(json: String) = MattermostJson.parseToJsonElement(json).jsonObject
        // As Mattermost sends it: the manifest as an object.
        assertEquals(
            VILLANET_ID,
            enabledPluginId(data("""{"manifest": {"id": "ceo.villa.matty", "version": "4.0.0", "webapp": {"bundle_path": "webapp/dist/main.js"}}}""")),
        )
        // Or as a string of JSON.
        assertEquals(
            VILLANET_ID,
            enabledPluginId(data("""{"manifest": "{\"id\": \"ceo.villa.matty\", \"version\": \"4.0.0\"}"}""")),
        )
        // Another plugin is that plugin, and the module that speaks for this one does not take it for its own.
        val other = enabledPluginId(data("""{"manifest": {"id": "com.mattermost.calls", "version": "1.0.0"}}"""))
        assertEquals("com.mattermost.calls", other)
        assertFalse(other == VILLANET_ID)
        // Nothing that names a plugin: no id.
        assertNull(enabledPluginId(data("""{}""")))
        assertNull(enabledPluginId(data("""{"manifest": "not json"}""")))
        assertNull(enabledPluginId(data("""{"manifest": {"version": "4.0.0"}}""")))
    }
}
