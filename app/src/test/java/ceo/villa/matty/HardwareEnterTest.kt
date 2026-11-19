package ceo.villa.matty

import android.view.KeyCharacterMap
import android.view.KeyEvent
import ceo.villa.matty.data.repo.spanWords
import ceo.villa.matty.ui.chat.HardwareEnter
import ceo.villa.matty.ui.chat.hardwareEnter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HardwareEnterTest {
    private fun enter(shift: Boolean = false, ctrlOrAlt: Boolean = false, flags: Int = 0, deviceId: Int = 3, key: Int = KeyEvent.KEYCODE_ENTER) =
        hardwareEnter(key, flags, deviceId, shift, ctrlOrAlt)

    @Test
    fun aPhysicalKeyboardSendsWithEnterAndBreaksWithShiftEnter() {
        assertEquals(HardwareEnter.Send, enter())
        assertEquals(HardwareEnter.NewLine, enter(shift = true))
        assertEquals(HardwareEnter.NewLine, enter(ctrlOrAlt = true))
        assertEquals(HardwareEnter.Send, enter(key = KeyEvent.KEYCODE_NUMPAD_ENTER))
        // The built-in keyboard of a device that has one counts as physical (its id is 0).
        assertEquals(HardwareEnter.Send, enter(deviceId = 0))
    }

    @Test
    fun theOnScreenKeyboardKeepsItsOwnEnter() {
        // Sent by the keyboard app as a key event: marked as such, or from no device at all.
        assertNull(enter(flags = KeyEvent.FLAG_SOFT_KEYBOARD))
        assertNull(enter(deviceId = KeyCharacterMap.VIRTUAL_KEYBOARD))
        assertNull(enter(shift = true, deviceId = KeyCharacterMap.VIRTUAL_KEYBOARD))
    }

    @Test
    fun otherKeysAreNotItsBusiness() {
        assertNull(enter(key = KeyEvent.KEYCODE_A))
        assertNull(enter(key = KeyEvent.KEYCODE_TAB))
    }

    @Test
    fun editWindowsReadAsPlainSpans() {
        assertEquals("5 seconds", spanWords(5))
        assertEquals("1 minute", spanWords(60))
        assertEquals("30 minutes", spanWords(1800))
        assertEquals("1 hour", spanWords(3600))
        assertEquals("2 days", spanWords(172_800))
    }
}
