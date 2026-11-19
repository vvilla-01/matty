package ceo.villa.matty

import ceo.villa.matty.push.UnifiedPush
import ceo.villa.matty.push.UnifiedPush.Choice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/** The connector's half of the UnifiedPush Android protocol: its names, its choices, and the manifest that declares it. */
class UnifiedPushTest {
    @Test fun theProtocolsNamesExactly() {
        assertEquals("org.unifiedpush.android.distributor.REGISTER", UnifiedPush.ACTION_REGISTER)
        assertEquals("org.unifiedpush.android.distributor.UNREGISTER", UnifiedPush.ACTION_UNREGISTER)
        assertEquals("org.unifiedpush.android.distributor.MESSAGE_ACK", UnifiedPush.ACTION_MESSAGE_ACK)
        assertEquals("org.unifiedpush.android.distributor.feature.BYTES_MESSAGE", UnifiedPush.FEATURE_BYTES_MESSAGE)
        assertEquals("org.unifiedpush.android.connector.NEW_ENDPOINT", UnifiedPush.ACTION_NEW_ENDPOINT)
        assertEquals("org.unifiedpush.android.connector.REGISTRATION_FAILED", UnifiedPush.ACTION_REGISTRATION_FAILED)
        assertEquals("org.unifiedpush.android.connector.MESSAGE", UnifiedPush.ACTION_MESSAGE)
        assertEquals("org.unifiedpush.android.connector.UNREGISTERED", UnifiedPush.ACTION_UNREGISTERED)
        assertEquals("org.unifiedpush.android.connector.TEMP_UNAVAILABLE", UnifiedPush.ACTION_TEMP_UNAVAILABLE)
        assertEquals("org.unifiedpush.android.connector.RAISE_TO_FOREGROUND", UnifiedPush.ACTION_RAISE_TO_FOREGROUND)
        assertEquals(
            listOf("token", "application", "pi", "features", "vapid", "message", "id", "endpoint", "reason", "bytesMessage", "useDistributor", "newDistributor"),
            listOf(UnifiedPush.EXTRA_TOKEN, UnifiedPush.EXTRA_APPLICATION, UnifiedPush.EXTRA_PI, UnifiedPush.EXTRA_FEATURES, UnifiedPush.EXTRA_VAPID,
                UnifiedPush.EXTRA_MESSAGE, UnifiedPush.EXTRA_ID, UnifiedPush.EXTRA_ENDPOINT, UnifiedPush.EXTRA_REASON, UnifiedPush.EXTRA_BYTES_MESSAGE,
                UnifiedPush.EXTRA_USE_DISTRIBUTOR, UnifiedPush.EXTRA_NEW_DISTRIBUTOR),
        )
        assertEquals("org.unifiedpush.dummy_app", UnifiedPush.DUMMY_APP)
    }

    @Test fun aDistributorIsFoundTheWayThePersonWouldExpect() {
        assertEquals(Choice.None, UnifiedPush.choose(null, emptyList()))
        assertEquals(Choice.None, UnifiedPush.choose("org.example.a", emptyList()))
        assertEquals(Choice.Link("org.example.b"), UnifiedPush.choose("org.example.b", listOf("org.example.a", "org.example.b")))
        assertEquals(Choice.Only("org.example.a"), UnifiedPush.choose(null, listOf("org.example.a")))
        // The system's own chooser answering the link is no default.
        assertEquals(Choice.Pick(listOf("org.example.a", "org.example.b")), UnifiedPush.choose("android", listOf("org.example.a", "org.example.b")))
        assertEquals(Choice.Only("org.example.a"), UnifiedPush.choose("org.example.browser", listOf("org.example.a")))
    }

    @Test fun withNoneTakingRegisterTheChosenAppThatNeedsSettingUpIsOpened() {
        assertEquals(Choice.Open("org.example.a"), UnifiedPush.choose(null, emptyList(), notSetUp = "org.example.a"))
        assertEquals(Choice.Open("org.example.a"), UnifiedPush.choose("org.example.a", emptyList(), notSetUp = "org.example.a"))
        assertEquals(Choice.None, UnifiedPush.choose(null, emptyList(), notSetUp = null))
        // Others to choose from: chosen among as ever.
        assertEquals(Choice.Only("org.example.b"), UnifiedPush.choose(null, listOf("org.example.b"), notSetUp = "org.example.a"))
    }

    @Test fun theChosenAppIsGoneOnlyWhenUninstalledOrSignedBySomebodyElse() {
        for (takes in listOf(true, false)) {
            assertEquals(UnifiedPush.Standing.GONE, UnifiedPush.standing(takesRegister = takes, installed = false, signed = true))
            assertEquals(UnifiedPush.Standing.GONE, UnifiedPush.standing(takesRegister = takes, installed = true, signed = false))
            assertEquals(UnifiedPush.Standing.GONE, UnifiedPush.standing(takesRegister = takes, installed = false, signed = false))
        }
        assertEquals(UnifiedPush.Standing.USABLE, UnifiedPush.standing(takesRegister = true, installed = true, signed = true))
        assertEquals("its receiver switched off", UnifiedPush.Standing.NOT_SET_UP, UnifiedPush.standing(takesRegister = false, installed = true, signed = true))
    }

    @Test fun oneTokenIsNotRegisteredTwiceWithinTenSeconds() {
        var now = 1_000_000L
        val registered = ceo.villa.matty.push.Paced(UnifiedPush.REGISTER_SPACING_MS) { now }
        assertEquals(10_000L, UnifiedPush.REGISTER_SPACING_MS)
        assertTrue(registered.due("t1"))
        now += 10
        assertFalse("the second ask is skipped", registered.due("t1"))
        assertTrue("another token goes", registered.due("t2"))
        now += 9_989
        assertFalse(registered.due("t1"))
        now += 1
        assertTrue("ten seconds on", registered.due("t1"))
        assertFalse(registered.due("t1"))
    }

    @Test fun aMigrationOnlyToAnInstalledOtherDistributor() {
        val installed = listOf("org.example.a", "org.example.b")
        assertEquals("org.example.b", UnifiedPush.migrationTarget("org.example.b", "org.example.a", installed))
        assertNull(UnifiedPush.migrationTarget("org.example.c", "org.example.a", installed))
        assertNull(UnifiedPush.migrationTarget("org.example.a", "org.example.a", installed))
        assertNull(UnifiedPush.migrationTarget(null, "org.example.a", installed))
        assertNull(UnifiedPush.migrationTarget(" ", "org.example.a", installed))
    }

    @Test fun theLinkActivityAnswersWithItsCreator() {
        assertEquals("org.example.a", UnifiedPush.linkResult(android.app.Activity.RESULT_OK, "org.example.a"))
        assertNull(UnifiedPush.linkResult(android.app.Activity.RESULT_CANCELED, "org.example.a"))
        assertNull(UnifiedPush.linkResult(android.app.Activity.RESULT_OK, null))
        assertNull(UnifiedPush.linkResult(android.app.Activity.RESULT_OK, ""))
    }

    @Test fun onlyExportedReceiversAreDistributors() {
        val found = listOf("org.example.a" to true, "org.example.hidden" to false, null to true, "ceo.villa.matty" to true, "org.example.a" to true)
        assertEquals(listOf("org.example.a"), UnifiedPush.takingRegister(found, "ceo.villa.matty"))
    }

    @Test fun aDistributorIsKnownByItsSigningCertificates() {
        val one = byteArrayOf(1, 2, 3)
        val other = byteArrayOf(4, 5, 6)
        val remembered = UnifiedPush.signers(listOf(one))
        assertEquals(64, remembered.length)
        val sha = java.security.MessageDigest.getInstance("SHA-256")
        val digestOne = sha.digest(one)
        assertTrue(UnifiedPush.sameSigners(remembered) { it.contentEquals(digestOne) })
        assertFalse("another app under the same name", UnifiedPush.sameSigners(remembered) { it.contentEquals(sha.digest(other)) })
        // Two signers: both must still sign it.
        val both = UnifiedPush.signers(listOf(one, other))
        assertTrue(UnifiedPush.sameSigners(both) { true })
        assertFalse(UnifiedPush.sameSigners(both) { it.contentEquals(digestOne) })
        assertFalse("nothing readable is no", UnifiedPush.sameSigners("") { true })
        assertFalse(UnifiedPush.sameSigners("zz".repeat(32)) { true })
    }

    @Test fun onlyAProperKeyAndDescriptionAreRegistered() {
        val key = PushTestKit.b64(PushTestKit.point(PushTestKit.senderPair().public as java.security.interfaces.ECPublicKey))
        assertTrue(UnifiedPush.isVapid(key))
        assertFalse("padded", UnifiedPush.isVapid("$key="))
        assertFalse("standard base64", UnifiedPush.isVapid("+" + key.drop(1)))
        assertFalse("not a point", UnifiedPush.isVapid("A".repeat(87)))
        assertFalse(UnifiedPush.isVapid(key.dropLast(1)))
        assertFalse(UnifiedPush.isVapid(null))
        assertTrue(UnifiedPush.isDescription("d".repeat(100)))
        assertFalse(UnifiedPush.isDescription("é".repeat(51)))
        assertTrue(UnifiedPush.isToken("t".repeat(100)))
        assertFalse(UnifiedPush.isToken("t".repeat(101)))
        assertFalse(UnifiedPush.isToken(""))
    }

    /** The manifest declares the connector exactly as the protocol needs it. */
    @Test fun theManifestDeclaresTheConnector() {
        val file = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .map { java.io.File(it, "app/src/main/AndroidManifest.xml") }.first { it.isFile }
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
        val ns = "http://schemas.android.com/apk/res/android"
        fun Element.children(tag: String) = (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }
        fun Element.a(name: String) = getAttributeNS(ns, name)
        fun Element.actions() = children("intent-filter").flatMap { it.children("action") }.map { it.a("name") }
        val manifest = doc.documentElement
        val app = manifest.children("application").single()

        val receiver = app.children("receiver").single { it.a("name") == ".push.PushReceiver" }
        assertEquals("true", receiver.a("exported"))
        assertEquals("no permission: distributors are other apps", "", receiver.a("permission"))
        assertEquals(UnifiedPush.RECEIVED.sorted(), receiver.actions().sorted())

        val service = app.children("service").single { it.a("name") == ".push.RaiseToForegroundService" }
        assertEquals("true", service.a("exported"))
        assertEquals(listOf(UnifiedPush.ACTION_RAISE_TO_FOREGROUND), service.actions())

        val intents = manifest.children("queries").single().children("intent")
        assertEquals(
            setOf(UnifiedPush.ACTION_REGISTER, UnifiedPush.ACTION_UNREGISTER, "android.intent.action.VIEW"),
            intents.flatMap { it.children("action") }.map { it.a("name") }.toSet(),
        )
        val link = intents.single { i -> i.children("action").any { it.a("name") == "android.intent.action.VIEW" } }.children("data").single()
        assertEquals(UnifiedPush.LINK_SCHEME, link.a("scheme"))
        assertEquals(UnifiedPush.LINK_HOST, link.a("host"))
        assertTrue("waking for a push needs a wake lock",
            manifest.children("uses-permission").any { it.a("name") == "android.permission.WAKE_LOCK" })
    }
}
