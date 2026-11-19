package ceo.villa.matty

import ceo.villa.matty.data.api.ApiError
import ceo.villa.matty.data.api.ApiException
import ceo.villa.matty.data.api.MattermostJson
import ceo.villa.matty.data.session.PolicyKeeper
import ceo.villa.matty.data.session.SavedServer
import ceo.villa.matty.data.session.ServerPolicy
import ceo.villa.matty.data.session.TwoFactor
import ceo.villa.matty.ui.login.lockedOutText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a server requires of the app, read from its client config, and how that may change. */
class ServerPolicyTest {
    private val capture = "MobilePreventScreenCapture"
    private val unlock = "MobileEnableBiometrics"
    private val both = mapOf(capture to "true", unlock to "true")
    private val none = mapOf(capture to "false", unlock to "false")

    @Test
    fun readFromMattermostsOwnKeys() {
        // An unlicensed server (Team Edition) leaves the keys out: nothing is required.
        assertEquals(ServerPolicy(), ServerPolicy.of(emptyMap()))
        assertEquals(ServerPolicy(), ServerPolicy.of(mapOf("SiteName" to "x")))
        assertEquals(ServerPolicy(blocksCapture = true), ServerPolicy.of(mapOf(capture to "true")))
        assertEquals(ServerPolicy(asksUnlock = true), ServerPolicy.of(mapOf(unlock to "true", capture to "false")))
        assertEquals(ServerPolicy(true, true), ServerPolicy.of(both))
        assertEquals(ServerPolicy(), ServerPolicy.of(none))
        // "true" in any case is on; anything else is off, as in Mattermost's own app.
        assertEquals(ServerPolicy(true, true), ServerPolicy.of(mapOf(capture to "TRUE", unlock to " True ")))
        assertEquals(ServerPolicy(), ServerPolicy.of(mapOf(capture to "1", unlock to "yes")))
        assertEquals(ServerPolicy(), ServerPolicy.of(mapOf(capture to "", unlock to "truee")))
    }

    @Test
    fun aSuccessfulReadIsTheWholeTruth() {
        val saved = mutableListOf<ServerPolicy>()
        val k = PolicyKeeper(ServerPolicy(true, true)) { saved += it }
        k.answered(k.asking(), none)
        assertEquals(ServerPolicy(), k.policy.value)
        k.answered(k.asking(), mapOf(capture to "true"))
        assertEquals(ServerPolicy(blocksCapture = true), k.policy.value)
        // Each change is kept for the next start, and only changes.
        assertEquals(listOf(ServerPolicy(), ServerPolicy(blocksCapture = true)), saved)
        k.answered(k.asking(), mapOf(capture to "true"))
        assertEquals(2, saved.size)
    }

    @Test
    fun aFailedReadChangesNothing() {
        val k = PolicyKeeper(ServerPolicy(true, true))
        // A read that fails is started but never answered: what was remembered stands.
        k.asking()
        assertEquals(ServerPolicy(true, true), k.policy.value)
    }

    @Test
    fun anOverTakenAnswerCanOnlyAdd() {
        val k = PolicyKeeper(ServerPolicy())
        val old = k.asking()
        val new = k.asking()
        k.answered(new, both)
        // The older read comes back late, saying less: it is out of date.
        k.answered(old, none)
        assertEquals(ServerPolicy(true, true), k.policy.value)
    }

    @Test
    fun aPushAddsAtOnceAndOnlyALaterReadTakesAway() {
        val k = PolicyKeeper(ServerPolicy())
        val before = k.asking()
        k.pushed(mapOf(unlock to "true"))
        assertEquals(ServerPolicy(asksUnlock = true), k.policy.value)
        // A read asked before the push may not undo it...
        k.answered(before, none)
        assertEquals(ServerPolicy(asksUnlock = true), k.policy.value)
        // ...and a push can never say less.
        k.pushed(none)
        assertEquals(ServerPolicy(asksUnlock = true), k.policy.value)
        // A read asked afterwards is the server's word now.
        k.answered(k.asking(), none)
        assertEquals(ServerPolicy(), k.policy.value)
    }

    @Test
    fun rememberedWithTheSessionAndReadBackFromOlderSaves() {
        val s = SavedServer("id", "https://chat.example", "Example", "u", "user", "sealed", policy = ServerPolicy(blocksCapture = true))
        val json = MattermostJson.encodeToString(SavedServer.serializer(), s)
        assertEquals(s, MattermostJson.decodeFromString(SavedServer.serializer(), json))
        // A server saved before this version requires nothing until it is asked.
        val old = """{"id":"id","url":"https://chat.example","name":"Example","userId":"u","username":"user","sealedToken":"sealed"}"""
        assertEquals(ServerPolicy(), MattermostJson.decodeFromString(SavedServer.serializer(), old).policy)
    }

    @Test
    fun twoFactorRequiredIsTheServersOwnRefusal() {
        val required = ApiException(403, ApiError(TwoFactor.REQUIRED_ID, "Multi-factor authentication is required on this server.", 403), "x")
        assertTrue(TwoFactor.isRequired(required))
        assertFalse(TwoFactor.isRequired(ApiException(403, ApiError("api.context.permissions.app_error"), "x")))
        assertFalse(TwoFactor.isRequired(ApiException(401, ApiError(TwoFactor.REQUIRED_ID), "x")))
        assertFalse(TwoFactor.isRequired(java.io.IOException("offline")))
        assertFalse(TwoFactor.isRequired(null))
    }

    @Test
    fun aLockedAccountSaysSoInsteadOfWrongPassword() {
        val words = "Your account is locked because of too many failed password attempts. Please reset your password."
        val locked = ApiException(401, ApiError("api.user.check_user_login_attempts.too_many.app_error", words, 401), words)
        assertEquals(words, lockedOutText(locked))
        assertTrue(lockedOutText(ApiException(401, ApiError("api.user.check_user_login_attempts.too_many.app_error"), "x"))!!.contains("locked"))
        assertNull(lockedOutText(ApiException(401, ApiError("api.user.login.invalid_credentials_email_username"), "x")))
    }
}
