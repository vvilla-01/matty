package ceo.villa.matty.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import ceo.villa.matty.container
import ceo.villa.matty.push.PushFacts
import ceo.villa.matty.push.PushLine
import ceo.villa.matty.push.UnifiedPush
import ceo.villa.matty.ui.Navigator
import ceo.villa.matty.ui.components.ActionRow
import ceo.villa.matty.ui.components.MattyDialog
import ceo.villa.matty.ui.components.PillButton
import ceo.villa.matty.ui.components.SwitchRow
import ceo.villa.matty.ui.theme.Matty
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How push stands for the server in view: what is known, and the line Settings shows. */
class PushView(val facts: PushFacts, val line: PushLine?) {
    /** Push works for this server: its notifications need no live connection. */
    val working: Boolean get() = line?.working == true
}

/** [PushView] for [serverId], read again whenever push changes or Matty comes back to the screen. */
@Composable
fun rememberPushView(serverId: String, notifGranted: Boolean): PushView {
    val push = LocalContext.current.container.push
    val tick by push.changes.collectAsState()
    // The distributors installed can change while Matty is away (one installed, one removed).
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed++ }
    return remember(tick, serverId, resumed, notifGranted) { push.facts(serverId).let { PushView(it, PushLine.of(it)) } }
}

/**
 * Settings > Notifications' push rows: the switch, "Change UnifiedPush app" once one is chosen,
 * and one line of state for the server in view ([PushLine]).
 */
@Composable
fun PushSettings(nav: Navigator, serverId: String, view: PushView) {
    val ctx = LocalContext.current
    val push = ctx.container.push
    val scope = rememberCoroutineScope()
    val c = Matty.colors
    val facts = view.facts
    val line = view.line
    // Every time Settings comes back (from the UnifiedPush app, say), not only when it opens.
    LifecycleResumeEffect(serverId, facts.on) {
        val looking = if (facts.on) scope.launch { push.look(serverId) } else null
        onPauseOrDispose { looking?.cancel() }
    }

    var picking by remember { mutableStateOf<List<String>?>(null) }
    // Each server's own: what a test or a try for one said never shows under another.
    var tested by remember(serverId) { mutableStateOf<String?>(null) }
    var testing by remember(serverId) { mutableStateOf(false) }
    var trying by remember(serverId) { mutableStateOf(false) }
    // "Sent: …" until the test arrives, or for ten seconds; then, until it arrives, that it has not.
    var sentAt by remember(serverId) { mutableLongStateOf(0L) }
    var now by remember(serverId) { mutableLongStateOf(0L) }
    val arrived by push.testArrived.collectAsState()
    val arrivedAt = arrived[serverId] ?: 0L
    LaunchedEffect(sentAt) {
        if (sentAt == 0L) return@LaunchedEffect
        delay((sentAt + PushLine.TEST_SHOWN_MS - System.currentTimeMillis()).coerceAtLeast(0))
        now = System.currentTimeMillis()
        // Read now, not as composed: a test may have arrived meanwhile.
        val latest = push.testArrived.value[serverId] ?: 0L
        // The server may have found the registration refused or failing meanwhile: its own line then,
        // with Try again. Only read: the one PUT Matty makes by itself stays for a later look.
        if (PushLine.testMissing(sentAt, latest, now)) push.look(serverId, reading = true)
    }
    // Only while the line is the working one: it goes with it.
    val testable = line?.action == PushLine.Action.SEND_TEST
    LaunchedEffect(testable) { if (!testable) sentAt = 0L }

    fun use(pkg: String?) {
        if (pkg == null || pkg !in UnifiedPush.distributors(ctx)) return
        // The app's scope: registering every server goes on when Settings is left.
        ctx.container.appScope.launch { push.switchOn(pkg) }
    }
    val link = rememberLauncherForActivityResult(UnifiedPush.LinkContract()) { use(it) }
    // A distributor with a link activity says yes there; an older one is taken as picked.
    fun ask(pkg: String) {
        if (hasLink(ctx, pkg)) runCatching { link.launch(UnifiedPush.Link.To(pkg)) }.onFailure { use(pkg) } else use(pkg)
    }
    /**
     * Switching on, or setting up again: the default's link activity, the only one, or the
     * person's pick; with none taking REGISTER, the chosen app if it only needs setting up.
     */
    fun choose() {
        val notSetUp = facts.chosen?.takeIf { facts.chosenInstalled }
        when (val choice = UnifiedPush.choose(UnifiedPush.linkDefault(ctx), UnifiedPush.distributors(ctx), notSetUp)) {
            UnifiedPush.Choice.None -> Unit
            is UnifiedPush.Choice.Open -> ctx.packageManager.getLaunchIntentForPackage(choice.distributor)?.let { runCatching { ctx.startActivity(it) } }
            is UnifiedPush.Choice.Link -> ask(choice.distributor)
            is UnifiedPush.Choice.Only -> ask(choice.distributor)
            is UnifiedPush.Choice.Pick -> picking = choice.distributors
        }
    }

    SwitchRow("Push notifications", "Through a UnifiedPush app on this phone, so Matty need not stay connected.", facts.on) { v ->
        if (v) choose() else ctx.container.appScope.launch { push.switchOff() }
    }
    if (facts.on && facts.chosen != null && facts.installed.isNotEmpty()) {
        // Always the choice, never the silent default.
        ActionRow(Icons.Outlined.SwapHoriz, "Change UnifiedPush app", facts.appName.ifBlank { null }) { picking = facts.installed }
    }
    line?.let { l ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
            Text(l.text, style = MaterialTheme.typography.bodySmall, color = if (l.warning) c.dnd else c.textSecondary)
            when (l.action) {
                PushLine.Action.WHAT_IS -> Row(Modifier.padding(top = 8.dp)) {
                    PillButton("What is UnifiedPush?") { nav.openUrl(PushLine.WHAT_IS_URL) }
                }
                PushLine.Action.SET_UP_AGAIN -> Row(Modifier.padding(top = 8.dp)) {
                    PillButton("Set up again") { choose() }
                }
                PushLine.Action.SEND_TEST -> Row(Modifier.padding(top = 8.dp)) {
                    PillButton("Send a test", loading = testing, enabled = !testing) {
                        testing = true
                        tested = null
                        sentAt = 0L
                        // Before asking: the test can arrive before the answer does.
                        val asked = System.currentTimeMillis()
                        scope.launch {
                            tested = push.test(serverId)
                            if (tested == null) sentAt = asked
                            testing = false
                        }
                    }
                }
                PushLine.Action.TRY_AGAIN -> Row(Modifier.padding(top = 8.dp)) {
                    PillButton("Try again", loading = trying, enabled = !trying) {
                        trying = true
                        scope.launch {
                            push.tryAgain(serverId)
                            trying = false
                        }
                    }
                }
                PushLine.Action.NONE -> Unit
            }
            val said = tested?.let { PushLine(it) } ?: PushLine.testSaid(sentAt, arrivedAt, now, facts.appName)
            said?.takeIf { l.action == PushLine.Action.SEND_TEST }?.let {
                Text(it.text, style = MaterialTheme.typography.bodySmall, color = if (it.warning) c.dnd else c.textTertiary, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }

    picking?.let { options ->
        MattyDialog("Choose a UnifiedPush app", onDismiss = { picking = null }, icon = Icons.Outlined.NotificationsActive, actions = {
            TextButton(onClick = { picking = null }) { Text("Cancel") }
        }) {
            for (pkg in options) {
                Text(
                    push.label(pkg),
                    style = MaterialTheme.typography.bodyLarge, color = c.text,
                    modifier = Modifier.fillMaxWidth().clickable { picking = null; ask(pkg) }.padding(vertical = 12.dp, horizontal = 4.dp),
                )
            }
        }
    }
}

/** Whether [pkg] has the link activity of AND_3 distributors. */
private fun hasLink(ctx: Context, pkg: String): Boolean = runCatching {
    ctx.packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, UnifiedPush.LINK).setPackage(pkg), PackageManager.ResolveInfoFlags.of(0)) != null
}.getOrDefault(false)
