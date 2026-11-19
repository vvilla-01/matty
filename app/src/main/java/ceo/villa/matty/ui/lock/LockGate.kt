package ceo.villa.matty.ui.lock

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ceo.villa.matty.IntentKey
import ceo.villa.matty.MainActivity
import ceo.villa.matty.R
import ceo.villa.matty.container
import ceo.villa.matty.data.repo.ChatRepository
import ceo.villa.matty.data.session.SavedServer
import ceo.villa.matty.ui.components.ActionRow
import ceo.villa.matty.ui.components.MattyDialog
import ceo.villa.matty.ui.components.PillButton
import ceo.villa.matty.ui.components.sheetVerticalScroll
import ceo.villa.matty.ui.theme.Matty
import kotlinx.coroutines.flow.first

/** Which servers are unlocked in this process (see [Unlocks]). */
object AppLock {
    val book = Unlocks()
    /** What waits for a server's unlock after the person left its lock for another server. */
    val waiting = WaitingBehindLock<Intent>()
    fun now(): Long = SystemClock.elapsedRealtime()
}

/**
 * What a server requires of the app, applied before any of its content: the window kept out of
 * captures when it says so, and, when it asks for an unlock, nothing of it composed until the
 * person has proved who they are to the phone. Whatever arrives meanwhile (a notification tap, a
 * share) waits in the activity and goes on after the unlock.
 */
@Composable
fun ServerGate(activity: MainActivity, repo: ChatRepository, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val container = ctx.container
    val id = repo.server.id
    val policy by repo.policy.collectAsState()
    // Asked of every server, so one that starts asking mid-session is locked at once.
    var locked by remember(id) { mutableStateOf(AppLock.book.shown(id, AppLock.now())) }
    // The system's prompt opens by itself once each time the lock comes up.
    var autoPrompted by remember(id) { mutableStateOf(false) }
    var secured by remember { mutableStateOf(deviceSecured(ctx)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, id) {
        val watch = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> AppLock.book.hidden(id, AppLock.now())
                Lifecycle.Event.ON_START -> if (AppLock.book.shown(id, AppLock.now()) && !locked) { locked = true; autoPrompted = false }
                // Back from the phone's settings, perhaps with a screen lock now.
                Lifecycle.Event.ON_RESUME -> secured = deviceSecured(ctx)
                else -> {}
            }
        }
        lifecycle.addObserver(watch)
        onDispose {
            lifecycle.removeObserver(watch)
            AppLock.book.hidden(id, AppLock.now())
        }
    }
    val lockShows = policy.asksUnlock && locked
    val secure = windowSecure(policy, lockShows)
    val asksUnlock = policy.asksUnlock
    // Before anything of this server is drawn, and lifted in the very frame its content goes.
    SideEffect { SecureWindow.server(activity, secure, asksUnlock) }

    if (!lockShows) {
        content()
        return
    }
    val pending by activity.pendingIntent
    LaunchedEffect(pending) {
        val intent = pending ?: return@LaunchedEffect
        // Left behind another server's lock: it is that server's, not this one's to act on.
        if (AppLock.waiting.waitsElsewhere(intent, id)) return@LaunchedEffect
        val own = !IntentKey.ignored(intent.action, intent.getStringExtra(MainActivity.EXTRA_KEY), container.intentKey) &&
            intent.action == MainActivity.ACTION_OPEN_CHANNEL
        val signedIn = container.sessionStore.servers.value.mapTo(HashSet()) { it.id }
        when (val next = whileLocked(own, intent.getStringExtra(MainActivity.EXTRA_SERVER_ID), id, signedIn)) {
            // The intent stays pending: the other server's session opens it.
            is WhileLocked.Switch -> container.sessions.switchTo(next.serverId)
            WhileLocked.Waits -> {}
        }
    }
    var prompt by remember { mutableStateOf<CancellationSignal?>(null) }
    DisposableEffect(Unit) { onDispose { prompt?.cancel() } }
    val name = repo.server.name
    fun ask() {
        if (prompt != null) return
        prompt = promptUnlock(activity, name) { outcome ->
            prompt = null
            when (outcome) {
                Outcome.Unlocked -> { AppLock.book.unlocked(id, AppLock.now()); locked = false }
                Outcome.NoScreenLock -> secured = deviceSecured(ctx)
                Outcome.Stayed -> {}
            }
        }
    }
    LaunchedEffect(autoPrompted, secured) {
        if (autoPrompted || !secured) return@LaunchedEffect
        // The prompt can only open over an app in front.
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        autoPrompted = true
        ask()
    }
    val servers by container.sessionStore.servers.collectAsState()
    val exits = lockExits(id, servers.map { it.id })
    var choosing by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    LockScreen(name, secured, canSwitch = exits.switchTo.isNotEmpty(), onUnlock = { ask() }, onSettings = {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }, onSwitch = { choosing = true }, onSignOut = { confirmSignOut = true })
    if (choosing) {
        val others = exits.switchTo.mapNotNull { other -> servers.firstOrNull { it.id == other } }
        SwitchServerSheet(others, onDismiss = { choosing = false }) { target ->
            choosing = false
            // The other server comes up under its own rules (its own lock, if it asks for one);
            // what was waiting here keeps waiting for this server's unlock.
            AppLock.waiting.switchedAway(activity.pendingIntent.value, id)
            container.sessions.switchTo(target.id)
        }
    }
    if (confirmSignOut) {
        MattyDialog("Sign out of $name?", onDismiss = { confirmSignOut = false }, icon = Icons.AutoMirrored.Outlined.Logout, actions = {
            TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") }
            TextButton(onClick = {
                confirmSignOut = false
                // Only while this is still the server on screen: sign-out ends the one in front.
                if (container.sessions.current.value !== repo) return@TextButton
                // A notification tap or share that waited behind this lock goes with it, before
                // the next server could act on it.
                if (AppLock.waiting.signedOut(activity.pendingIntent.value, id)) activity.pendingIntent.value = null
                container.sessions.signOut()
            }) { Text("Sign out") }
        }) {}
    }
}

/** The other signed-in servers, as the You tab lists them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwitchServerSheet(servers: List<SavedServer>, onDismiss: () -> Unit, onPick: (SavedServer) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Matty.colors.card, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.navigationBarsPadding().sheetVerticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
            servers.forEach { s ->
                ActionRow(Icons.Outlined.Dns, s.name, lockSheetHint(s.policy.asksUnlock, s.username, s.url)) { onPick(s) }
            }
        }
    }
}

/** Whether the phone has a screen lock (PIN, pattern or password; fingerprint and face need one too). */
private fun deviceSecured(ctx: Context): Boolean =
    ctx.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

private enum class Outcome { Unlocked, NoScreenLock, Stayed }

/**
 * The system's own prompt: a strong fingerprint or face, or the phone's PIN, pattern or password,
 * as Mattermost's app accepts. Cancelled or failed, the lock simply stays.
 */
private fun promptUnlock(activity: MainActivity, name: String, done: (Outcome) -> Unit): CancellationSignal {
    val signal = CancellationSignal()
    val prompt = BiometricPrompt.Builder(activity)
        .setTitle("Unlock Matty")
        .setSubtitle(name)
        .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
        .build()
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = done(Outcome.Unlocked)
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = done(
            if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL) Outcome.NoScreenLock else Outcome.Stayed,
        )
    }
    runCatching { prompt.authenticate(signal, activity.mainExecutor, callback) }.onFailure { done(Outcome.Stayed) }
    return signal
}

@Composable
private fun LockScreen(
    name: String, secured: Boolean, canSwitch: Boolean,
    onUnlock: () -> Unit, onSettings: () -> Unit, onSwitch: () -> Unit, onSignOut: () -> Unit,
) {
    val c = Matty.colors
    Column(
        Modifier.fillMaxSize().background(c.background).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(112.dp).background(c.pill, CircleShape), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(112.dp), colorFilter = ColorFilter.tint(c.icon))
        }
        Spacer(Modifier.height(28.dp))
        Text(name, style = MaterialTheme.typography.titleMedium, color = c.title, textAlign = TextAlign.Center)
        if (!secured) {
            Spacer(Modifier.height(6.dp))
            Text(
                "$name asks you to unlock Matty. Set a screen lock on this phone first.",
                style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(20.dp))
        val modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth()
        if (secured) PillButton("Unlock", primary = true, modifier = modifier, height = 56.dp, onClick = onUnlock)
        else PillButton("Open settings", primary = true, modifier = modifier, height = 56.dp, onClick = onSettings)
        // The ways out that aren't an unlock, quiet under the pill.
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.Center) {
            if (canSwitch) QuietLink("Switch server", onSwitch)
            QuietLink("Sign out", onSignOut)
        }
    }
}

/** A small text action, as the sign-in screen's "Use an access token". */
@Composable
private fun QuietLink(text: String, onClick: () -> Unit) {
    Text(
        text, color = Matty.colors.textSecondary, style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(10.dp),
    )
}
