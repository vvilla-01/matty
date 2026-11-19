package ceo.villa.matty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The value Matty's own intents carry, so another app can't make it open a channel. */
class IntentKeyTest {
    @Test
    fun onlyTheKeyItselfMatches() {
        val key = IntentKey.create()
        assertTrue(IntentKey.matches(key, key))
        assertTrue(IntentKey.matches(String(key.toCharArray()), key))
        // What another app can send: nothing, nothing at all, a guess, the key cut short or longer.
        assertFalse(IntentKey.matches(null, key))
        assertFalse(IntentKey.matches("", key))
        assertFalse(IntentKey.matches("guess", key))
        assertFalse(IntentKey.matches(key.dropLast(1), key))
        assertFalse(IntentKey.matches(key + "x", key))
        // A key that was never made matches nothing, not even nothing.
        assertFalse(IntentKey.matches("", ""))
    }

    @Test
    fun eachKeyIsNewAnd32RandomBytes() {
        val a = IntentKey.create()
        assertNotEquals(a, IntentKey.create())
        assertEquals(32, java.util.Base64.getUrlDecoder().decode(a).size)
    }

    @Test
    fun openingAChannelNeedsTheKeyAndNothingElseDoes() {
        val key = IntentKey.create()
        assertFalse(IntentKey.ignored(MainActivity.ACTION_OPEN_CHANNEL, key, key))
        assertTrue(IntentKey.ignored(MainActivity.ACTION_OPEN_CHANNEL, null, key))
        assertTrue(IntentKey.ignored(MainActivity.ACTION_OPEN_CHANNEL, "guess", key))
        // Shares and the launcher stay open to every app.
        assertFalse(IntentKey.ignored("android.intent.action.SEND", null, key))
        assertFalse(IntentKey.ignored("android.intent.action.SEND_MULTIPLE", null, key))
        assertFalse(IntentKey.ignored("android.intent.action.MAIN", null, key))
        assertFalse(IntentKey.ignored(null, null, key))
    }
}
