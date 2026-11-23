package ceo.villa.matty.ui.lock

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ceo.villa.matty.BuildConfig
import ceo.villa.matty.MainActivity
import ceo.villa.matty.R
import ceo.villa.matty.container
import ceo.villa.matty.data.repo.ChatRepository
import ceo.villa.matty.data.session.minimumOf
import ceo.villa.matty.plugins.villanet.VillaNetPlugin
import ceo.villa.matty.ui.Navigator
import ceo.villa.matty.ui.components.PillButton
import ceo.villa.matty.ui.theme.Matty
import kotlinx.coroutines.launch

/**
 * A server that takes no Matty as old as this one (see [ChatRepository.appNeed]): nothing of it
 * is composed, and a page that cannot be dismissed says so, with the way to the update, Check
 * again, and the ways off it the lock screen has. It sits inside [ServerGate]: a server that also
 * asks for an unlock shows its lock first, and this page once unlocked, so nothing of the server,
 * not even what it requires, shows before the person has proved who they are. `GET /access` is
 * read again as the page appears, when Matty comes back to the front, and on Check again, at least
 * five seconds apart; the page goes as soon as an answer takes this version.
 */
@Composable
fun UpdateGate(activity: MainActivity, repo: ChatRepository, nav: Navigator, content: @Composable () -> Unit) {
    val need by repo.appNeed.collectAsState()
    val n = need
    if (n == null) {
        content()
        return
    }
    val container = LocalContext.current.container
    val module = remember { container.plugins.builtIn.firstNotNullOfOrNull { it as? VillaNetPlugin } }
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    fun check() {
        if (checking || module == null) return
        checking = true
        scope.launch { try { module.checkAppVersion(repo) } finally { checking = false } }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        // Added in front, the observer hears ON_START at once: the page's first read.
        val watch = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) check() }
        lifecycle.addObserver(watch)
        onDispose { lifecycle.removeObserver(watch) }
    }
    ClosedServer(activity, repo) { canSwitch, onSwitch, onSignOut ->
        UpdateScreen(
            words = updateWords(repo.server.name, n.minimum, BuildConfig.VERSION_NAME),
            checking = checking, canSwitch = canSwitch,
            onUpdate = { nav.openUrl(updatePage(BuildConfig.REPO_URL)) },
            onCheck = { check() }, onSwitch = onSwitch, onSignOut = onSignOut,
        )
    }
}

/** The update page's sentence: [server] needs [minimum] ("" when it did not say which), and this app is [own]. */
fun updateWords(server: String, minimum: String, own: String): String {
    val m = minimumOf(minimum)
    return (if (m != null) "$server needs Matty $m or newer." else "$server needs a newer version of Matty.") + " You have $own."
}

/** Where "Get the update" goes: the newest release of the app's own repository. */
fun updatePage(repoUrl: String): String = repoUrl.trimEnd('/') + "/releases/latest"

@Composable
private fun UpdateScreen(
    words: String, checking: Boolean, canSwitch: Boolean,
    onUpdate: () -> Unit, onCheck: () -> Unit, onSwitch: () -> Unit, onSignOut: () -> Unit,
) {
    val c = Matty.colors
    ClosedPage {
        Box(Modifier.size(112.dp).background(c.pill, CircleShape), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(112.dp), colorFilter = ColorFilter.tint(c.icon))
        }
        Spacer(Modifier.height(28.dp))
        Text("Update Matty", style = MaterialTheme.typography.titleMedium, color = c.title, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(words, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        val modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth()
        PillButton("Get the update", primary = true, modifier = modifier, height = 56.dp, onClick = onUpdate)
        Spacer(Modifier.height(10.dp))
        PillButton("Check again", modifier = modifier, loading = checking, onClick = onCheck)
        // The ways off it, quiet under the pills, as on the lock screen.
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.Center) {
            if (canSwitch) QuietLink("Switch server", onSwitch)
            QuietLink("Sign out", onSignOut)
        }
    }
}
