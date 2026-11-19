package ceo.villa.matty

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import ceo.villa.matty.ui.MattyRoot
import ceo.villa.matty.ui.lock.SecureWindow
import ceo.villa.matty.ui.theme.MattyTheme
import ceo.villa.matty.ui.theme.isDark
import ceo.villa.matty.ui.theme.styleSystemBars
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Intents (notification taps, shares, SSO callbacks) waiting for the UI to consume. */
    val pendingIntent = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app starts here (or in ConnectionService), never when a push starts the process.
        // A notification's tap starts with the server it came from, so the active one isn't
        // connected (and shown online) first. An activity made again is handed its old intent:
        // a tap that brought it back arrives in onNewIntent, and onResume starts the active one
        // otherwise; both come before anything is drawn.
        if (savedInstanceState == null) container.sessions.ensureActive(startServer(intent))
        // From the first frame, in Matty's theme (the stored one), not the phone's.
        val systemDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        styleSystemBars(container.settings.state.value.theme.isDark(systemDark))
        super.onCreate(savedInstanceState)
        // Recreated (after the process was killed): the screens aren't restored, so neither is
        // an orientation a full-screen stream asked for.
        if (savedInstanceState != null) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        // Volume keys control media (streams and clips), never the call volume.
        volumeControlStream = AudioManager.STREAM_MUSIC
        // A cold start is held to what the server on screen last required from the first frame,
        // before it has been asked again: out of captures, and behind its lock when it asks for one
        // (the lock is in memory only, so a new process starts locked).
        container.sessionStore.active?.policy?.let { p ->
            SecureWindow.server(this, secure = p.blocksCapture || p.asksUnlock, noRecentsPicture = p.asksUnlock)
        }
        // Away from the screen nothing is drawn, so the gate can't apply what a server starts to
        // require meanwhile (over the background connection): it is applied here, the same way,
        // so the app switcher's picture goes at once instead of when Matty is back. In front, the
        // gate has the last word.
        lifecycleScope.launch {
            container.sessions.current.collectLatest { repo ->
                repo?.policy?.collect { p ->
                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        SecureWindow.server(this@MainActivity, secure = p.blocksCapture || p.asksUnlock, noRecentsPicture = p.asksUnlock)
                    }
                }
            }
        }
        // The launch intent is acted on once. An activity the system makes again after taking the
        // process is handed the same intent: its tap or share was dealt with before, and a share
        // that was waiting behind one server's lock must not land on the server shown now.
        if (savedInstanceState == null) pendingIntent.value = intent
        setContent {
            val settings by container.settings.state.collectAsState()
            MattyTheme(settings.theme, settings.accent, settings.systemDim) {
                MattyRoot(this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        container.sessions.ensureActive()
        SecureWindow.reapply(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Nothing live yet (an activity made again): the tap's own server starts.
        container.sessions.ensureActive(startServer(intent))
        pendingIntent.value = intent
    }

    private fun startServer(intent: Intent): String? =
        startServer(intent.action, intent.getStringExtra(EXTRA_SERVER_ID), intent.getStringExtra(EXTRA_KEY), container.intentKey)

    companion object {
        const val ACTION_OPEN_CHANNEL = "ceo.villa.matty.OPEN_CHANNEL"
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_SERVER_ID = "server_id"
        /** [IntentKey]: an OPEN_CHANNEL without it is taken as the app simply being opened. */
        const val EXTRA_KEY = "ceo.villa.matty.KEY"

        /**
         * The server a launch intent asks to start with: only one of Matty's own notifications
         * ([IntentKey]) names one; anything else starts with the active server (null).
         */
        fun startServer(action: String?, serverId: String?, given: String?, key: String): String? =
            serverId?.takeIf { action == ACTION_OPEN_CHANNEL && !IntentKey.ignored(action, given, key) }
    }
}
