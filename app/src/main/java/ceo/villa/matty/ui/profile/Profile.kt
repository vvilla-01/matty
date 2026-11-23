package ceo.villa.matty.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import ceo.villa.matty.BuildConfig
import ceo.villa.matty.container
import ceo.villa.matty.data.api.User
import ceo.villa.matty.data.emoji.EmojiStore
import ceo.villa.matty.plugins.MattyPlugin
import androidx.compose.ui.unit.Dp
import ceo.villa.matty.ui.HomeTab
import ceo.villa.matty.ui.LocalActions
import ceo.villa.matty.ui.LocalRepo
import ceo.villa.matty.ui.Navigator
import ceo.villa.matty.ui.Route
import ceo.villa.matty.ui.chat.EmojiGlyph
import ceo.villa.matty.ui.components.ActionRow
import ceo.villa.matty.ui.components.Avatar
import ceo.villa.matty.ui.components.ChipPill
import ceo.villa.matty.ui.components.FloatingTopBar
import ceo.villa.matty.ui.components.MattyCard
import ceo.villa.matty.ui.components.MattyDialog
import ceo.villa.matty.ui.components.PillButton
import ceo.villa.matty.ui.components.SectionLabel
import ceo.villa.matty.ui.components.nameColor
import ceo.villa.matty.ui.components.sheetVerticalScroll
import ceo.villa.matty.ui.components.statusColor
import ceo.villa.matty.ui.components.statusLabel
import ceo.villa.matty.ui.home.BotTag
import ceo.villa.matty.ui.picker.ExpressionPicker
import ceo.villa.matty.ui.theme.Matty
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
private fun Banner(userId: String, height: androidx.compose.ui.unit.Dp = 110.dp) {
    val c = Matty.colors
    val base = nameColor(userId, true)
    Box(Modifier.fillMaxWidth().height(height).background(Brush.linearGradient(listOf(base.copy(alpha = 0.85f), base.copy(alpha = 0.35f), c.card))))
}

/**
 * The band across the top of a profile, and over it what a plugin draws there (VillaNET: the
 * person's banner, and on your own the button that changes it), [bannerBandHeight] high. Then
 * [picture], which is placed [pictureTop] down, over the band's foot. The profile sheet and the
 * You page both draw their top with this. [inView]: whether it can be seen now (see [youPageInView]).
 */
@Composable
private fun ProfileBand(userId: String, nav: Navigator, plugins: List<MattyPlugin>, inView: Boolean, picture: @Composable (top: Dp) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints {
        val bannerHeight = bannerBandHeight(maxWidth)
        Box(Modifier.fillMaxWidth().height(bannerHeight)) {
            Banner(userId, bannerHeight)
            plugins.forEach { p -> androidx.compose.runtime.key(p.id) { p.ProfileBanner(userId, nav, Modifier.fillMaxSize(), inView) } }
        }
        picture(pictureTop(bannerHeight))
    }
}

/**
 * What plugins keep for as long as the profile [userId] is shown, composed after it and outside
 * any sheet (VillaNET: the crop screen for one's banner).
 */
@Composable
private fun ProfileOvers(userId: String, nav: Navigator, plugins: List<MattyPlugin>) {
    plugins.forEach { p -> androidx.compose.runtime.key(p.id) { p.ProfileOver(userId, nav) } }
}

/** Picks a photo and makes it your profile picture (from the You tab and your own profile sheet). */
@Composable
private fun rememberAvatarPicker(onPicked: () -> Unit = {}): () -> Unit {
    val repo = LocalRepo.current
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        onPicked()
        val cr = ctx.contentResolver
        val mime = cr.getType(uri) ?: "image/jpeg"
        repo.launchAction("Couldn't update your picture") {
            // Through the same cleaning as an attachment: the server gets the picture, not where it was taken.
            val picture = ceo.villa.matty.data.media.OutgoingMedia.prepare(ctx, uri, mime)
            val (bytes, type) = when (picture) {
                ceo.villa.matty.data.media.OutgoingMedia.Outcome.Refused -> {
                    repo.emitNotice("That picture wasn't used because its hidden details couldn't be removed.")
                    return@launchAction
                }
                ceo.villa.matty.data.media.OutgoingMedia.Outcome.NoRoom -> {
                    repo.emitNotice("That picture wasn't used because the phone needs more free space to remove its hidden details.")
                    return@launchAction
                }
                ceo.villa.matty.data.media.OutgoingMedia.Outcome.AsIs -> withContext(Dispatchers.IO) { cr.openInputStream(uri)!!.use { it.readBytes() } } to mime
                is ceo.villa.matty.data.media.OutgoingMedia.Outcome.Ready -> try {
                    withContext(Dispatchers.IO) { picture.bytes() } to picture.mime
                } finally {
                    picture.discard()
                }
            }
            repo.updateProfileImage(bytes, type)
            repo.emitNotice("Profile picture updated")
        }
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

/** The small camera on your own picture: tapping it (or the picture) changes the picture. */
@Composable
private fun CameraBadge(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Matty.colors
    Box(
        modifier.size(32.dp).background(c.card, CircleShape).padding(3.dp).clip(CircleShape).background(c.selected)
            .clickable(onClickLabel = "Change picture", onClick = onClick)
            .semantics { contentDescription = "Change picture" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.CameraAlt, null, tint = c.icon, modifier = Modifier.size(16.dp))
    }
}

/**
 * Clickable as a circle without clipping to one: the status dot sits on the picture's corner,
 * outside its circle, and a clip would cut it off (and make it look like part of the photo).
 */
@Composable
private fun Modifier.circleClick(radius: androidx.compose.ui.unit.Dp, label: String, onClick: () -> Unit): Modifier =
    clickable(
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        indication = androidx.compose.material3.ripple(bounded = false, radius = radius),
        onClickLabel = label,
        onClick = onClick,
    )

@Composable
fun ProfileSheet(nav: Navigator, userId: String, onDismiss: () -> Unit) {
    val repo = LocalRepo.current
    val actions = LocalActions.current
    val c = Matty.colors
    val users by repo.users.collectAsState()
    val statuses by repo.statuses.collectAsState()
    val plugins by LocalContext.current.container.plugins.active.collectAsState()
    val facts = ceo.villa.matty.plugins.personOf(userId)
    val u = users[userId]
    val mine = userId == repo.myId
    val avatarVersion by repo.avatarVersion.collectAsState()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetScope = rememberCoroutineScope()
    // Picked: the sheet steps aside, so "Profile picture updated" (or what went wrong) shows.
    val pickAvatar = rememberAvatarPicker(onPicked = { sheetScope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() } })
    // Their status, asked afresh: the dot, and when they were last online (kept for this sheet only).
    var lastActive by remember(userId) { mutableStateOf(0L) }
    LaunchedEffect(userId) { lastActive = repo.statusOf(userId)?.lastActivityAt ?: 0L }
    // Anything on the sheet that opens a full-screen view (VillaNET's Watch, for one) slides
    // the sheet down out of the way so what was opened is visible straight away.
    val startDepth = remember { nav.stack.size }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { nav.stack.size }.collect { depth ->
            if (depth > startDepth) { sheet.hide(); onDismiss() }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.card, dragHandle = null,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        // Scrolls: a VillaNET regular's awards can run longer than the screen.
        Column(Modifier.sheetVerticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            // The coloured band, and over it what a plugin draws there (VillaNET: their banner).
            // Seen while it is open: it steps aside when a full-screen view opens.
            ProfileBand(userId, nav, plugins, inView = true) { top ->
                // The picture overlaps the banner's foot by as much as it always has.
                Box(Modifier.padding(start = 16.dp, top = top)) {
                    val st = statuses[userId] ?: "offline"
                    Box(
                        Modifier.background(c.card, CircleShape)
                            .then(if (mine) Modifier.circleClick(52.dp, "Change picture", pickAvatar) else Modifier)
                            // The avatar's dot tells a screen reader the presence itself.
                            .semantics { contentDescription = repo.nameOf(u) }
                            .padding(6.dp),
                    ) {
                        val url = if (mine) repo.client.avatarUrl(userId, maxOf(u?.lastPictureUpdate ?: 0, avatarVersion)) else repo.avatarUrl(userId)
                        Avatar(url, repo.nameOf(u), 92.dp, status = st, ringColor = c.card, live = facts.live, voice = facts.voice)
                    }
                    if (mine) CameraBadge(Modifier.align(Alignment.TopEnd).padding(top = 4.dp), onClick = pickAvatar)
                }
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(8.dp))
                // Who they are, in one glance: name; handle and their local time; what they say
                // about themselves; what they're up to. No cards or labels below to dig through.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(repo.nameOf(u), style = MaterialTheme.typography.headlineSmall, color = c.title, fontWeight = FontWeight.SemiBold)
                    if (u?.isBot == true) BotTag()
                }
                val zone = if (mine) null else u?.zoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                // Each piece wraps on its own, so at a large font the time moves down whole.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("@${u?.username.orEmpty()}", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                    if (zone != null) LocalTime(zone)
                    if (showsLastOnline(statuses[userId], u?.isBot == true, mine, lastActive)) LastOnline(lastActive)
                }
                // Their about line (and a bot's description), up to four lines; a tap shows the rest.
                val bio = listOfNotNull(u?.position?.ifBlank { null }, if (u?.isBot == true) u.botDescription.ifBlank { null } else null)
                if (bio.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    val text = bio.joinToString("\n")
                    var expanded by androidx.compose.runtime.saveable.rememberSaveable(userId) { mutableStateOf(false) }
                    var clipped by remember(text) { mutableStateOf(false) }
                    Text(
                        text, color = c.text, style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                        onTextLayout = { if (!expanded) clipped = it.hasVisualOverflow },
                        modifier = if (clipped || expanded) Modifier.clickable(onClickLabel = if (expanded) "Show less" else "Show more") { expanded = !expanded } else Modifier,
                    )
                }
                // Their own status. VillaNET's live badge, held in its place while they broadcast, is
                // left out: the card below says they're live, with a way to watch.
                ceo.villa.matty.ui.home.ownStatus(u, facts)?.let { cs ->
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.clip(RoundedCornerShape(16.dp)).background(c.pill).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (cs.emoji.isNotBlank()) { EmojiGlyph(cs.emoji, 18.dp); Spacer(Modifier.width(6.dp)) }
                        Text(cs.text, color = c.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(16.dp))
                // Your own picture changes from the camera on it; this is for the rest.
                if (mine) PillButton("Edit profile", icon = Icons.Outlined.Edit, modifier = Modifier.fillMaxWidth()) { nav.push(Route.EditProfile) }
                else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Message", icon = Icons.AutoMirrored.Outlined.Chat, primary = true, modifier = Modifier.weight(1f)) { actions.messageUser(userId) }
                    PillButton("Mention", icon = Icons.Outlined.AlternateEmail) {
                        // Into the message box in view: the thread's when a thread is open, else the
                        // channel's (brought into view if the member list or another tab covers it).
                        val thread = (nav.top as? Route.Thread)?.rootId
                        val key = if (thread != null) repo.posts.value[thread]?.channelId?.let { "$it|$thread" } else nav.channelId?.let { "$it|" }
                        if (key != null && u != null) {
                            ceo.villa.matty.ui.chat.Drafts.append(key, "@${u.username} ")
                            if (thread == null) {
                                // Search, Pinned or Channel Info may cover the chat: close them, as
                                // opening a channel does, so the box with the mention is in view.
                                nav.stack.clear()
                                nav.tab = ceo.villa.matty.ui.HomeTab.Home
                                nav.showChatSignal++
                            }
                        }
                        onDismiss()
                    }
                }
                Spacer(Modifier.height(12.dp))
                plugins.forEach { p -> p.ProfileExtras(userId, nav) }
            }
        }
    }
    // After the sheet: a window a plugin opens over it stays above it when a density change
    // builds both afresh, in this order.
    ProfileOvers(userId, nav, plugins)
}

/**
 * "🕒 4:12 PM   3h ahead": their clock, ticking over each minute, and how far it is from yours
 * (nothing extra when you share a time zone). Two pieces, so each can wrap on its own.
 */
@Composable
private fun LocalTime(zone: ZoneId) {
    val c = Matty.colors
    // The moment ticks ([minuteClock]); the zone is applied here, so the time, the city and the
    // offset always agree.
    val nowMs = minuteClock()
    val time = Instant.ofEpochMilli(nowMs).atZone(zone).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    val apart = offsetFromHere(zone)
    val city = zone.id.substringAfterLast('/').replace('_', ' ')
    val iconSize = with(androidx.compose.ui.platform.LocalDensity.current) { 15.sp.toDp() }
    Row(
        Modifier.semantics(mergeDescendants = true) {
            contentDescription = "Local time $time in $city" + (apart?.let { ", $it" } ?: "")
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Schedule, null, tint = c.textSecondary, modifier = Modifier.size(iconSize))
        Spacer(Modifier.width(4.dp))
        Text(time, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, maxLines = 1)
    }
    if (apart != null) Text(apart, style = MaterialTheme.typography.bodyMedium, color = c.textTertiary, maxLines = 1,
        modifier = Modifier.clearAndSetSemantics {})
}

/** "Last online 5 min ago", in the time's own quiet style, kept right as the minutes pass. */
@Composable
private fun LastOnline(lastActiveMs: Long) {
    val words = lastOnlineWords(lastActiveMs, minuteClock())
    Text(words, style = MaterialTheme.typography.bodyMedium, color = Matty.colors.textSecondary, maxLines = 1)
}

/**
 * Now, ticking over on each minute, and only while the app is in view, so it's right again the
 * instant you come back to it.
 */
@Composable
private fun minuteClock(): Long {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val nowMs by androidx.compose.runtime.produceState(System.currentTimeMillis(), lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                value = System.currentTimeMillis()
                kotlinx.coroutines.delay(60_000L - System.currentTimeMillis() % 60_000L)
            }
        }
    }
    return nowMs
}

/** "3h ahead", "5h 30m behind", or null when their clock reads the same as yours. */
private fun offsetFromHere(zone: ZoneId): String? {
    val now = Instant.now()
    val minutes = (zone.rules.getOffset(now).totalSeconds - ZoneId.systemDefault().rules.getOffset(now).totalSeconds) / 60
    if (minutes == 0) return null
    val h = kotlin.math.abs(minutes) / 60
    val m = kotlin.math.abs(minutes) % 60
    val amount = when { m == 0 -> "${h}h"; h == 0 -> "${m}m"; else -> "${h}h ${m}m" }
    return amount + if (minutes > 0) " ahead" else " behind"
}


@Composable
fun YouScreen(nav: Navigator) {
    val repo = LocalRepo.current
    val c = Matty.colors
    val ctx = LocalContext.current
    val container = ctx.container
    val me by repo.me.collectAsState()
    val statuses by repo.statuses.collectAsState()
    val servers by container.sessionStore.servers.collectAsState()
    var statusDialog by remember { mutableStateOf(false) }
    val avatarVersion by repo.avatarVersion.collectAsState()
    val plugins by container.plugins.active.collectAsState()
    val pickAvatar = rememberAvatarPicker()
    val lifecycle by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val u = me ?: return
    val myStatus = statuses[u.id] ?: "online"
    // Your banner counts as looked at only while this page can be seen; an animated one plays only then, and a moment after.
    val inView = youPageInView(nav.tab == HomeTab.You, nav.stack.size, lifecycle.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
    // The page scrolls whole; its column is no wider than the profile sheet, and centred, so the
    // band is never wider than the sheet's.
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(bottom = 120.dp + ceo.villa.matty.ui.BottomChrome.barSpace),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = BottomSheetDefaults.SheetMaxWidth).fillMaxWidth()) {
            Box(Modifier.padding(16.dp).clip(Matty.CardShape).background(c.card)) {
                Column {
                    // Your banner, as your profile sheet shows it, with the button that changes it.
                    ProfileBand(u.id, nav, plugins, inView) { top ->
                        Box(Modifier.padding(start = 16.dp, top = top)) {
                            Box(Modifier.background(c.card, CircleShape).circleClick(50.dp, "Change picture", pickAvatar).padding(6.dp)) {
                                Avatar(repo.client.avatarUrl(u.id, maxOf(u.lastPictureUpdate, avatarVersion)), repo.nameOf(u), 88.dp, status = myStatus, ringColor = c.card)
                            }
                            CameraBadge(Modifier.align(Alignment.TopEnd).padding(top = 4.dp), onClick = pickAvatar)
                        }
                    }
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 8.dp)) {
                        Text(repo.nameOf(u), style = MaterialTheme.typography.headlineSmall, color = c.title, fontWeight = FontWeight.SemiBold)
                        Text("@${u.username} · ${repo.siteName}", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                        // Why the last change to your banner failed, when it did.
                        plugins.forEach { p -> androidx.compose.runtime.key(p.id) { p.ProfileBannerNote(u.id, nav) } }
                        Spacer(Modifier.height(12.dp))
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.pill).clickable { statusDialog = true }.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val cs = u.customStatus
                            if (cs?.emoji?.isNotBlank() == true) EmojiGlyph(cs.emoji, 20.dp) else Icon(Icons.Outlined.EmojiEmotions, null, tint = c.textSecondary)
                            Spacer(Modifier.width(10.dp))
                            Text(cs?.text?.ifBlank { null } ?: "Set a custom status", color = if (cs != null) c.text else c.textSecondary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            if (cs != null) Icon(Icons.Outlined.Close, "Clear status", tint = c.textSecondary, modifier = Modifier.size(20.dp).clickable {
                                repo.launchAction("Couldn't clear your status") { repo.clearCustomStatus() }
                            })
                        }
                        Spacer(Modifier.height(10.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("online", "away", "dnd", "offline").forEach { st ->
                                ChipPill(if (st == "offline") "Invisible" else statusLabel(st), selected = myStatus == st,
                                    leading = { Box(Modifier.size(10.dp).background(statusColor(st), CircleShape)) }) {
                                    repo.launchAction("Couldn't change your status") { repo.setMyStatus(st) }
                                }
                            }
                        }
                    }
                }
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MattyCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
                    ActionRow(Icons.Outlined.Edit, "Edit profile") { nav.push(Route.EditProfile) }
                    ActionRow(Icons.Outlined.Bookmark, "Saved messages") { nav.tab = HomeTab.Activity }
                    ActionRow(Icons.Outlined.Settings, "Settings", "Appearance, chat, notifications, GIFs") { nav.push(Route.Settings) }
                    ActionRow(Icons.Outlined.Extension, "Plugins", "What your server runs, and how Matty supports it") { nav.push(Route.Plugins) }
                }
                SectionLabel("Servers")
                MattyCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
                    servers.forEach { s ->
                        ActionRow(Icons.Outlined.Dns, s.name, "@${s.username} · ${s.url.removePrefix("https://")}",
                            trailing = { if (s.id == repo.server.id) Icon(Icons.Outlined.Check, "Active", tint = c.accent) }) {
                            container.sessions.switchTo(s.id)
                        }
                    }
                    ActionRow(Icons.Outlined.Add, "Add a server") { nav.push(Route.AddServer) }
                    ActionRow(Icons.AutoMirrored.Outlined.Logout, "Sign out of ${repo.siteName}", tint = c.destructive) { container.sessions.signOut() }
                }
                MattyCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
                    ActionRow(Icons.Outlined.Code, "Matty ${BuildConfig.VERSION_NAME}", "Connected to Mattermost ${repo.config.value["Version"].orEmpty()}") {
                        nav.openUrl(ceo.villa.matty.BuildConfig.REPO_URL)
                    }
                    ActionRow(Icons.Outlined.Description, "Licences", "The open-source parts inside Matty") { nav.push(Route.Licences) }
                }
            }
        }
    }
    if (statusDialog) CustomStatusDialog(u) { statusDialog = false }
    // After the page, as after the sheet. While your own profile sheet is open over this page,
    // the sheet keeps it instead.
    if (youPageKeepsProfileOver(u.id, nav.profileUserId)) ProfileOvers(u.id, nav, plugins)
}

@Composable
private fun CustomStatusDialog(u: User, onDismiss: () -> Unit) {
    val repo = LocalRepo.current
    val c = Matty.colors
    val current = u.customStatus
    var emoji by remember { mutableStateOf(current?.emoji?.ifBlank { null } ?: "speech_balloon") }
    var text by remember { mutableStateOf(current?.text.orEmpty()) }
    var duration by remember { mutableStateOf("today") }
    var pickEmoji by remember { mutableStateOf(false) }
    val presets = listOf(
        Triple("calendar", "In a meeting", "one_hour"), Triple("hamburger", "Out for lunch", "thirty_minutes"),
        Triple("video_game", "Gaming", "four_hours"), Triple("movie_camera", "Streaming", "four_hours"),
        Triple("palm_tree", "On vacation", "this_week"), Triple("face_with_thermometer", "Out sick", "today"),
    )
    MattyDialog("Custom Status", onDismiss = onDismiss, icon = Icons.Outlined.EmojiEmotions, actions = {
        androidx.compose.material3.IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Cancel") }
        androidx.compose.material3.IconButton(onClick = {
            val e = emoji; val t = text.trim(); val d = duration; val expires = expiryFor(duration)
            repo.launchAction("Couldn't set your status") { repo.setCustomStatus(e, t, d, expires) }
            onDismiss()
        }) { Icon(Icons.Outlined.Check, "Save") }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(c.selected).clickable { pickEmoji = true }, contentAlignment = Alignment.Center) { EmojiGlyph(emoji, 26.dp) }
            Spacer(Modifier.width(10.dp))
            OutlinedTextField(text, { text = it.take(100) }, placeholder = { Text("What's happening?") }, singleLine = true, shape = Matty.FieldShape,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.rim), modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        presets.forEach { (e, t, d) ->
            Row(Modifier.fillMaxWidth().clip(Matty.RowShape).clickable { emoji = e; text = t; duration = d }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                EmojiGlyph(e, 20.dp); Spacer(Modifier.width(10.dp)); Text(t, color = c.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Schedule, null, tint = c.textSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Clear after", color = c.textSecondary, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("thirty_minutes" to "30 min", "one_hour" to "1 hour", "four_hours" to "4 hours", "today" to "Today", "this_week" to "This week", "dont_clear" to "Never")
                .forEach { (k, label) -> ChipPill(label, selected = duration == k) { duration = k } }
        }
    }
    if (pickEmoji) ExpressionPicker(false, false, { emoji = it; pickEmoji = false }, null) { pickEmoji = false }
}

private fun expiryFor(duration: String): String? {
    val now = ZonedDateTime.now()
    val t = when (duration) {
        "thirty_minutes" -> now.plusMinutes(30)
        "one_hour" -> now.plusHours(1)
        "four_hours" -> now.plusHours(4)
        "today" -> now.toLocalDate().atTime(23, 59, 59).atZone(now.zone)
        "this_week" -> now.toLocalDate().plusDays((7 - now.dayOfWeek.value).toLong()).atTime(23, 59, 59).atZone(now.zone)
        else -> return null
    }
    return DateTimeFormatter.ISO_INSTANT.format(Instant.from(t))
}

@Composable
fun EditProfileScreen(nav: Navigator) {
    val repo = LocalRepo.current
    val c = Matty.colors
    val me by repo.me.collectAsState()
    val scope = rememberCoroutineScope()
    val u = me ?: return
    var first by remember { mutableStateOf(u.firstName) }
    var last by remember { mutableStateOf(u.lastName) }
    var nick by remember { mutableStateOf(u.nickname) }
    var position by remember { mutableStateOf(u.position) }
    var busy by remember { mutableStateOf(false) }
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.rim)
    Column(Modifier.fillMaxSize().background(c.background).verticalScroll(rememberScrollState())) {
        FloatingTopBar("Edit Profile", onBack = { nav.pop() })
        MattyCard(Modifier.padding(16.dp).fillMaxWidth()) {
            SectionLabel("Display name")
            OutlinedTextField(nick, { nick = it.take(64) }, label = { Text("Nickname") }, singleLine = true, shape = Matty.FieldShape, colors = fieldColors, modifier = Modifier.fillMaxWidth())
            Text("This is what people see in chat. Leave it empty to use your full name.", color = c.textTertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(first, { first = it.take(64) }, label = { Text("First name") }, singleLine = true, shape = Matty.FieldShape, colors = fieldColors, modifier = Modifier.weight(1f))
                OutlinedTextField(last, { last = it.take(64) }, label = { Text("Last name") }, singleLine = true, shape = Matty.FieldShape, colors = fieldColors, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            SectionLabel("About me")
            OutlinedTextField(position, { position = it.take(128) }, placeholder = { Text("A line about you") }, shape = Matty.FieldShape, colors = fieldColors, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            PillButton("Save", primary = true, loading = busy, modifier = Modifier.fillMaxWidth(), height = 56.dp) {
                busy = true
                val f = first.trim(); val l = last.trim(); val n = nick.trim(); val p = position.trim()
                scope.launch {
                    if (repo.runAction("Couldn't save your profile") { repo.updateProfile(f, l, n, p) } != null) nav.pop()
                    busy = false
                }
            }
        }
    }
    @Suppress("UNUSED_EXPRESSION") EmojiStore
}
