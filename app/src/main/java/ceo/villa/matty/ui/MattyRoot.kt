package ceo.villa.matty.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import ceo.villa.matty.IntentKey
import ceo.villa.matty.MainActivity
import ceo.villa.matty.R
import ceo.villa.matty.container
import ceo.villa.matty.data.api.OpenDialogRequest
import ceo.villa.matty.data.repo.ChatRepository
import ceo.villa.matty.data.session.TwoFactor
import ceo.villa.matty.ui.lock.AppLock
import ceo.villa.matty.ui.lock.SecureWindow
import ceo.villa.matty.ui.lock.ServerGate
import ceo.villa.matty.ui.lock.UpdateGate
import ceo.villa.matty.ui.chat.SearchScreen
import ceo.villa.matty.ui.chat.ThreadScreen
import ceo.villa.matty.ui.components.EmptyState
import ceo.villa.matty.ui.components.MediaViewer
import ceo.villa.matty.ui.components.PillButton
import ceo.villa.matty.ui.home.BrowseChannelsScreen
import ceo.villa.matty.ui.home.ChannelInfoScreen
import ceo.villa.matty.ui.home.HomeScreen
import ceo.villa.matty.ui.home.NewMessageScreen
import ceo.villa.matty.ui.home.PinnedScreen
import ceo.villa.matty.ui.login.LoginScreen
import ceo.villa.matty.ui.plugins.InteractiveDialogView
import ceo.villa.matty.ui.profile.EditProfileScreen
import ceo.villa.matty.ui.profile.ProfileSheet
import ceo.villa.matty.ui.settings.LicencesScreen
import ceo.villa.matty.ui.settings.PluginsScreen
import ceo.villa.matty.ui.settings.SettingsScreen
import ceo.villa.matty.ui.theme.Matty
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.TextButton
import ceo.villa.matty.ui.components.MattyDialog

/** Content shared into Matty from another app, waiting for a conversation to be picked. */
object ShareInbox {
    var text by mutableStateOf<String?>(null)
    var uris by mutableStateOf<List<Uri>>(emptyList())
    /** The channel the user picked to share into; it may be the one already open. */
    var target by mutableStateOf<String?>(null)
    val pending: Boolean get() = text != null || uris.isNotEmpty()
    fun clear() { text = null; uris = emptyList(); target = null }
}

@Composable
fun MattyRoot(activity: MainActivity) {
    val c = LocalContext.current.container
    val repo by c.sessions.current.collectAsState()
    val r = repo
    Box(Modifier.fillMaxSize().background(Matty.colors.background)) {
        if (r == null) {
            // No server on screen asks anything of the window (the sign-in screen guards itself).
            SideEffect { SecureWindow.server(activity, secure = false, noRecentsPicture = false) }
            LoginScreen()
        } else key(r.server.id) {
            // The session's own scope: what the navigator starts (opening a DM, a download) must not be
            // cut off, or reported as failed, because the activity was recreated.
            // Discover or Plugins in the main area is saved with the activity, so it comes back with the
            // process as the open chat does (that one from the server's stored place).
            // Made outside the lock, so where the person was is still there once they unlock.
            val railPages = rememberSaveable(r, saver = RailPages.Saver) { RailPages() }
            val nav = remember(r) { Navigator(activity, c, r, r.scope, railPages) }
            // The lock first, then the update page of a server that takes no Matty this old.
            ServerGate(activity, r) { UpdateGate(activity, r, nav) { Session(activity, r, nav) } }
        }
        StatusBarScrimView()
    }
}

/**
 * The status bar is see-through, so a photo or a bright message scrolling under it could swallow
 * the clock and icons when they're the same colour. A light fade from the theme's background,
 * only as tall as the bar (and a touch more), keeps them readable without the dark band the chat
 * used to have. Screens with their own backdrop (the photo viewer) switch it off.
 */
object StatusBarScrim {
    var suppressed by androidx.compose.runtime.mutableIntStateOf(0)
}

@Composable
private fun StatusBarScrimView() {
    if (StatusBarScrim.suppressed > 0) return
    val bg = Matty.colors.background
    Box(
        Modifier.fillMaxWidth()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(bg.copy(alpha = 0.72f), bg.copy(alpha = 0.45f), bg.copy(alpha = 0f))))
            .statusBarsPadding().height(6.dp),
    )
}

@Composable
private fun Session(activity: MainActivity, repo: ChatRepository, nav: Navigator) {
    val ctx = LocalContext.current
    val container = ctx.container
    val ready by repo.ready.collectAsState()
    val fatal by repo.fatalError.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<OpenDialogRequest?>(null) }
    var addingServer by remember { mutableStateOf(false) }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(ready) {
        if (ready &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            val p = ctx.getSharedPreferences("matty_device", 0)
            if (!p.getBoolean("asked_notifications", false)) {
                p.edit().putBoolean("asked_notifications", true).apply()
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    LaunchedEffect(repo) {
        repo.notices.collect { msg -> snackbar.showSnackbar(msg) }
    }
    LaunchedEffect(repo) {
        // A slash command's goto_location: links inside this server open straight away (as on
        // the web); anything else waits for a tap.
        repo.gotoLocations.collect { url ->
            if (repo.client.isSameOrigin(url) || url.startsWith("/")) nav.openUrl(if (url.startsWith("/")) repo.client.baseUrl + url else url)
            else {
                val host = runCatching { Uri.parse(url).host }.getOrNull() ?: url
                if (snackbar.showSnackbar("Open $host?", actionLabel = "Open", withDismissAction = true) == SnackbarResult.ActionPerformed) nav.openUrl(url)
            }
        }
    }
    LaunchedEffect(repo) {
        container.sessions.notice.collect { msg ->
            // Only while this session is still the live one; otherwise the sign-in screen shows it.
            if (msg != null && container.sessions.current.value === repo) { container.sessions.notice.value = null; snackbar.showSnackbar(msg) }
        }
    }
    LaunchedEffect(repo) { repo.dialogs.collect { dialog = it } }

    // Notification taps, shares and deep links.
    val pending by activity.pendingIntent
    LaunchedEffect(pending, ready) {
        val intent = pending ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        // Left behind another server's lock: it waits for that server's unlock, and isn't this one's.
        if (AppLock.waiting.waitsElsewhere(intent, repo.server.id)) return@LaunchedEffect
        AppLock.waiting.done(intent)
        activity.pendingIntent.value = null
        // Only Matty's own notifications may open a channel (and so switch the server, or pick
        // where a pending share goes): from any other app it is as if Matty were just opened.
        if (IntentKey.ignored(intent.action, intent.getStringExtra(MainActivity.EXTRA_KEY), container.intentKey)) return@LaunchedEffect
        val serverId = intent.getStringExtra(MainActivity.EXTRA_SERVER_ID)
        if ((intent.action == MainActivity.ACTION_OPEN_CHANNEL || intent.action == MainActivity.ACTION_OPEN_VOICE) && serverId != null && serverId != repo.server.id) {
            // A notification from another signed-in server: switch, then let that session open it.
            if (container.sessionStore.servers.value.any { it.id == serverId }) {
                activity.pendingIntent.value = intent
                container.sessions.switchTo(serverId)
            }
            return@LaunchedEffect
        }
        handleIntent(intent, nav, repo, ctx.packageName, container)
    }

    CompositionLocalProvider(LocalRepo provides repo, LocalActions provides nav) {
        if (!ready) {
            Splash(fatal, onRetry = { container.sessions.activate(repo.server) }, onSignOut = { container.sessions.signOut() })
            return@CompositionLocalProvider
        }
        if (addingServer) {
            LoginScreen(onCancel = { addingServer = false })
            return@CompositionLocalProvider
        }
        Box(Modifier.fillMaxSize()) {
            // While a full-screen view is open, screen readers mustn't read the home screen beneath it.
            Box(if (nav.stack.isNotEmpty()) Modifier.fillMaxSize().clearAndSetSemantics {} else Modifier.fillMaxSize()) {
                HomeScreen(nav)
            }
            // A full-screen view hides the home screen but doesn't stop touches reaching it: a
            // touch goes to the topmost layer with something tappable under the finger, so a tap
            // on a bare part of the view (around "Nobody is live", between the tab chips) went on
            // to the chat beneath, reacting to a post or opening a profile. While any view is open
            // or opening, this catches what the view itself doesn't handle.
            if (nav.stack.isNotEmpty()) Box(Modifier.fillMaxSize().pointerInput(Unit) {})
            AnimatedContent(
                nav.top,
                transitionSpec = { (fadeIn() + slideInHorizontally { it / 3 }) togetherWith (fadeOut() + slideOutHorizontally { it / 3 }) },
                label = "route",
            ) { route ->
                if (route != null) Box(Modifier.fillMaxSize().background(Matty.colors.background)) { RouteScreen(nav, route) { addingServer = true } }
            }
            BackHandler(enabled = nav.stack.isNotEmpty()) { nav.pop() }
            // What a plugin keeps going while you're elsewhere (VillaNET's stream), above the bottom pills.
            FloatingPluginBar(nav)

            AnimatedVisibility(ShareInbox.pending, modifier = Modifier.align(Alignment.TopCenter)) {
                Row(
                    // Solid: a tap on its words mustn't open the channel or header button behind it
                    // (and a channel opened that way would become the share's target).
                    Modifier.statusBarsPadding().padding(12.dp).clip(RoundedCornerShape(20.dp)).background(Matty.colors.accent)
                        .pointerInput(Unit) {}.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(Icons.Outlined.Share, null, tint = Matty.colors.onAccent)
                    Spacer(Modifier.size(8.dp))
                    Text("Pick a conversation to share into", color = Matty.colors.onAccent, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.size(12.dp))
                    Text("Cancel", color = Matty.colors.onAccent, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(4.dp).clickableNoRipple { ShareInbox.clear() })
                }
            }
            val navBottom = androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val keyboard = androidx.compose.foundation.layout.WindowInsets.ime
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).offset {
                // Above the tab pill, and above the player bar when that's higher (read while placing,
                // so the screen doesn't redraw for it). The snackbar keeps 12dp around its surface.
                val resting = maxOf(navBottom + 92.dp, BottomChrome.barTop - 4.dp).roundToPx()
                // With the keyboard up, above it and the message box riding on it: a notice behind
                // the keyboard (a refused edit, say) is no notice at all.
                val keys = keyboard.getBottom(this)
                val typing = if (keys > 0) keys + (pillHeight(nav) - 4.dp).roundToPx() else 0
                androidx.compose.ui.unit.IntOffset(0, -maxOf(resting, typing))
            }) { data ->
                Snackbar(data, shape = RoundedCornerShape(20.dp), containerColor = Matty.colors.selected, contentColor = Matty.colors.title)
            }
        }
        nav.profileUserId?.let { uid -> ProfileSheet(nav, uid) { nav.profileUserId = null } }
        // What plugins float over the app (VillaNET's clip popout).
        val plugins by container.plugins.active.collectAsState()
        plugins.forEach { p -> key(p.id) { p.Overlay(nav) } }
        dialog?.let { d -> InteractiveDialogView(d) { dialog = null } }
        nav.pendingLink?.let { (url, host) ->
            MattyDialog("Open link?", onDismiss = { nav.pendingLink = null }, icon = Icons.Outlined.Link, actions = {
                TextButton(onClick = { nav.pendingLink = null }) { Text("Cancel") }
                TextButton(onClick = { nav.pendingLink = null; nav.openUrl(url) }) { Text("Open") }
            }) {
                Text("This link shows one address but goes to $host.",
                    style = MaterialTheme.typography.bodyMedium, color = Matty.colors.textSecondary)
            }
        }
        nav.heldLink?.let { url ->
            val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            MattyDialog("Link", onDismiss = { nav.heldLink = null }, icon = Icons.Outlined.Link, actions = {
                TextButton(onClick = { nav.heldLink = null; clipboard.setText(androidx.compose.ui.text.AnnotatedString(url)) }) { Text("Copy link") }
                TextButton(onClick = { nav.heldLink = null; nav.openUrl(url) }) { Text("Open") }
            }) {
                Text(url, style = MaterialTheme.typography.bodyMedium, color = Matty.colors.textSecondary, maxLines = 6,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
        nav.pendingDownload?.let { f ->
            MattyDialog("Save File", onDismiss = { nav.pendingDownload = null }, icon = Icons.Outlined.Download, actions = {
                TextButton(onClick = { nav.pendingDownload = null }) { Text("Cancel") }
                TextButton(onClick = { nav.pendingDownload = null; nav.downloadAttachment(f.id, f.name, f.mimeType) }) { Text("Save") }
            }) {
                Text("Save “${ceo.villa.matty.data.net.Downloader.safeName(f.name)}” (${ceo.villa.matty.ui.chat.humanSize(f.size)}) to Downloads/Matty?",
                    style = MaterialTheme.typography.bodyMedium, color = Matty.colors.textSecondary)
            }
        }
    }
}

@Composable
private fun RouteScreen(nav: Navigator, route: Route, onAddServer: () -> Unit) {
    val ctx = LocalContext.current
    when (route) {
        is Route.Thread -> {
            // Reading a collapsed thread here: its notification goes, connected or not.
            LaunchedEffect(route.rootId) { ctx.container.notifier.cancel(nav.repo.server.id, route.rootId) }
            ThreadScreen(nav, route.rootId)
        }
        is Route.Search -> SearchScreen(nav, route.initial)
        Route.Settings -> SettingsScreen(nav)
        Route.Plugins -> PluginsScreen(nav)
        Route.Licences -> LicencesScreen(nav)
        is Route.Plugin -> {
            // Follows the server's plugin list: a plugin switched off closes its panel, and one
            // whose list arrives a moment after the link was tapped opens when it does.
            val active by ctx.container.plugins.active.collectAsState()
            val panel = active.firstOrNull { it.id == route.id }?.panel
            if (panel != null) panel.content(nav, route.args) { nav.pop() }
            else EmptyState(Icons.Outlined.Extension, "Plugin not available", "This server isn't running it right now.")
        }
        is Route.Media -> MediaViewer(nav, route.items, route.start)
        is Route.ChannelInfo -> ChannelInfoScreen(nav, route.channelId)
        is Route.Pinned -> PinnedScreen(nav, route.channelId)
        Route.NewMessage -> NewMessageScreen(nav)
        Route.BrowseChannels -> BrowseChannelsScreen(nav)
        Route.EditProfile -> EditProfileScreen(nav)
        Route.AddServer -> { nav.pop(); onAddServer() }
    }
}

private fun handleIntent(intent: Intent, nav: Navigator, repo: ChatRepository, ownPackage: String, container: ceo.villa.matty.AppContainer) {
    val c = nav.repo
    when (intent.action) {
        MainActivity.ACTION_OPEN_CHANNEL -> {
            val serverId = intent.getStringExtra(MainActivity.EXTRA_SERVER_ID)
            val channelId = intent.getStringExtra(MainActivity.EXTRA_CHANNEL_ID) ?: return
            if (serverId != null && serverId != repo.server.id) return // handled after the switch
            nav.openChannel(channelId)
            // A collapsed thread's notification opens the thread, over its channel.
            intent.getStringExtra(ceo.villa.matty.service.Notifier.EXTRA_ROOT_ID)?.ifEmpty { null }?.let { nav.openThread(it) }
        }
        // The voice call's notification: the voice screen, a page of the Matty plugin's panel.
        MainActivity.ACTION_OPEN_VOICE -> {
            val serverId = intent.getStringExtra(MainActivity.EXTRA_SERVER_ID)
            if (serverId != null && serverId != repo.server.id) return // handled after the switch
            nav.openPanel(ceo.villa.matty.plugins.villanet.VILLANET_ID, mapOf("voice" to "1"))
            // Its Unmute while the call's service had no microphone: unmuted now, with Matty on screen.
            if (intent.getBooleanExtra(MainActivity.EXTRA_UNMUTE, false)) {
                container.plugins.builtIn.firstNotNullOfOrNull { it as? ceo.villa.matty.plugins.villanet.VillaNetPlugin }?.voice?.unmuteOnScreen()
            }
        }
        Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
            ShareInbox.text = intent.getStringExtra(Intent.EXTRA_TEXT)
            val shared = if (intent.action == Intent.ACTION_SEND_MULTIPLE)
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            // Only files another app hands over through a content provider. file:// paths, and
            // anything served by Matty's own provider, could point at Matty's private data.
            ShareInbox.uris = shared.filter { uri ->
                if (!uri.scheme.equals("content", ignoreCase = true)) return@filter false
                // As ContentProvider.getAuthorityWithoutUserId does: "0@authority" is "authority".
                val authority = uri.authority?.substringAfterLast('@') ?: return@filter false
                !authority.startsWith(ownPackage)
            }
            ShareInbox.target = null
            nav.tab = HomeTab.Home
        }
    }
    @Suppress("UNUSED_EXPRESSION") c
}

@Composable
private fun Splash(error: String?, onRetry: () -> Unit, onSignOut: () -> Unit) {
    val c = Matty.colors
    Column(Modifier.fillMaxSize().background(c.background).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(112.dp).background(c.pill, CircleShape), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(112.dp), colorFilter = ColorFilter.tint(c.icon))
        }
        Spacer(Modifier.height(28.dp))
        if (error == null) {
            LoadingIndicator(Modifier.size(48.dp))
        } else {
            Text(if (error == TwoFactor.SET_UP_ON_WEB) "Two-factor authentication needed" else "Couldn't connect", style = MaterialTheme.typography.titleMedium, color = c.title)
            Spacer(Modifier.height(6.dp))
            Text(error, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            PillButton("Try again", primary = true, modifier = Modifier.fillMaxWidth(), height = 56.dp, onClick = onRetry)
            Spacer(Modifier.height(10.dp))
            PillButton("Sign out", modifier = Modifier.fillMaxWidth(), onClick = onSignOut)
        }
    }
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onClick))
