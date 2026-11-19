package ceo.villa.matty

import ceo.villa.matty.data.session.SavedServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app starts (connects, reads the server, retries sign-outs) only from [MainActivity] and
 * ConnectionService, through `ensureActive`: never because a push or a notification's action
 * started the process. The start paths are held here by the source itself, as the transport test
 * holds the request builders.
 */
class StartPathsTest {
    private val main = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "app/src/main/java/ceo/villa/matty") }.first { it.isDirectory }

    private fun source(path: String) = File(main, path).readText()

    /** The body of the function that starts at [signature] (up to the next function at its depth). */
    private fun body(text: String, signature: String): String {
        val start = text.indexOf(signature).also { assertTrue("$signature is there", it >= 0) }
        val open = text.indexOf('{', start)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) { '{' -> depth++; '}' -> if (--depth == 0) return text.substring(open, i + 1) }
        }
        error("unbalanced $signature")
    }

    private val starts = listOf("ensureActive", "restore(", ".activate(", "switchTo(", "sessions.current")

    @Test
    fun theApplicationItselfStartsNothing() {
        val onCreate = body(source("MattyApp.kt"), "override fun onCreate()")
        for (s in starts) assertFalse("Application.onCreate calls $s", s in onCreate)
    }

    @Test
    fun theActivityAndTheConnectionServiceStartTheAppFirstThing() {
        val activity = body(source("MainActivity.kt"), "override fun onCreate(")
        val at = activity.indexOf("sessions.ensureActive(")
        assertTrue("MainActivity starts the app", at >= 0)
        assertTrue("before the window is held to the server's policy", at < activity.indexOf("SecureWindow.server"))
        val service = body(source("service/ConnectionService.kt"), "override fun onStartCommand(")
        val there = service.indexOf("sessions.ensureActive()")
        assertTrue("ConnectionService starts the app", there >= 0)
        assertTrue("before it asks for the live session", there < service.indexOf("sessions.current"))
    }

    @Test
    fun anActivityMadeAgainStartsWithTheTapThatBroughtItBack() {
        val text = source("MainActivity.kt")
        // Its old intent is not a tap: onCreate leaves the start to what follows it.
        assertTrue("if (savedInstanceState == null) container.sessions.ensureActive(startServer(intent))" in body(text, "override fun onCreate("))
        val newIntent = body(text, "override fun onNewIntent(")
        val at = newIntent.indexOf("container.sessions.ensureActive(startServer(intent))")
        assertTrue("a tap names its server", at >= 0)
        assertTrue("before the screens act on it", at < newIntent.indexOf("pendingIntent.value = intent"))
        assertTrue("otherwise the active one, before anything is drawn", "container.sessions.ensureActive()" in body(text, "override fun onResume()"))
    }

    @Test
    fun pushesAndNotificationActionsNeverStartTheApp() {
        val receivers = listOf(
            source("push/PushReceiver.kt"),
            source("push/RaiseToForegroundService.kt"),
            // What a push hands over is carried out here, inside the receiver.
            source("push/PushCenter.kt"),
            source("service/Notifier.kt").substringAfter("class NotificationActionReceiver"),
        )
        for (text in receivers) for (s in starts) assertFalse("a receiver calls $s", s in text)
    }

    @Test
    fun aNotificationsTapStartsWithItsOwnServer() {
        val key = IntentKey.create()
        assertEquals("s2", MainActivity.startServer(MainActivity.ACTION_OPEN_CHANNEL, "s2", key, key))
        assertNull("without Matty's key", MainActivity.startServer(MainActivity.ACTION_OPEN_CHANNEL, "s2", "guess", key))
        assertNull(MainActivity.startServer(MainActivity.ACTION_OPEN_CHANNEL, "s2", null, key))
        assertNull("only a notification's tap names a server", MainActivity.startServer("android.intent.action.MAIN", "s2", key, key))
        assertNull(MainActivity.startServer(MainActivity.ACTION_OPEN_CHANNEL, null, key, key))
    }

    @Test
    fun theStartServerIsTheNamedOneWhenSavedElseTheActiveOne() {
        fun saved(id: String) = SavedServer(id = id, url = "https://$id.example.test", name = id, userId = "u", username = "victor", sealedToken = "x")
        val a = saved("a"); val b = saved("b")
        assertEquals(b, SessionManager.chooseStart(listOf(a, b), a, "b"))
        assertEquals(a, SessionManager.chooseStart(listOf(a, b), a, null))
        assertEquals("a server no longer saved", a, SessionManager.chooseStart(listOf(a, b), a, "gone"))
        assertNull(SessionManager.chooseStart(emptyList(), null, "b"))
        // A session the server said is over is never started.
        assertEquals(a, SessionManager.chooseStart(listOf(a, b), a, "b", ended = setOf("b")))
        assertNull(SessionManager.chooseStart(listOf(a, b), a, null, ended = setOf("a")))
        assertEquals(b, SessionManager.chooseStart(listOf(a, b), a, "b", ended = setOf("a")))
    }
}
