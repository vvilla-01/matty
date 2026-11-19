package ceo.villa.matty.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ceo.villa.matty.plugins.villanet.VoiceTestSignal

/**
 * Debug builds only: switches the test voice on or off, so that an emulator can talk in a voice
 * channel. `adb shell am broadcast -n ceo.villa.matty.debug/ceo.villa.matty.debug.TestVoice --ez on true`
 * (false, or no extra, switches it off). The microphone must still be allowed and the session
 * unmuted: the signal takes the place of what the microphone records.
 */
class TestVoice : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        VoiceTestSignal.on = intent.getBooleanExtra("on", false)
        Log.i("Matty", "test voice ${if (VoiceTestSignal.on) "on" else "off"}")
    }
}
