package ceo.villa.matty.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ceo.villa.matty.container
import ceo.villa.matty.data.api.SlashCommand
import ceo.villa.matty.data.session.AccentMode
import ceo.villa.matty.data.session.ThemeMode
import ceo.villa.matty.service.ConnectionService
import ceo.villa.matty.ui.LocalRepo
import ceo.villa.matty.ui.Navigator
import ceo.villa.matty.ui.Route
import ceo.villa.matty.ui.chat.EmojiGlyph
import ceo.villa.matty.ui.components.ActionRow
import ceo.villa.matty.ui.components.CenteredLoading
import ceo.villa.matty.ui.components.ChipPill
import ceo.villa.matty.ui.components.FloatingTopBar
import ceo.villa.matty.ui.components.MattyCard
import ceo.villa.matty.ui.components.PillButton
import ceo.villa.matty.ui.components.SectionLabel
import ceo.villa.matty.ui.components.SwitchRow
import ceo.villa.matty.ui.picker.ExpressionPicker
import ceo.villa.matty.ui.theme.Matty
import ceo.villa.matty.ui.theme.accentFor
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val container = ctx.container
    val repo = LocalRepo.current
    val s by container.settings.state.collectAsState()
    val prefs by repo.preferences.collectAsState()
    val c = Matty.colors
    var pickReaction by remember { mutableStateOf(false) }
    var notifGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifGranted = it }
    fun upd(block: (ceo.villa.matty.data.session.Settings) -> ceo.villa.matty.data.session.Settings) = container.settings.update(block)

    Column(Modifier.fillMaxSize().background(c.background).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        FloatingTopBar("Settings", onBack = { nav.pop() })
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("Appearance")
            MattyCard(Modifier.fillMaxWidth()) {
                Text("Theme", style = MaterialTheme.typography.bodyLarge, color = c.text)
                Spacer(Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ThemeMode.Oled to "OLED black", ThemeMode.Dim to "Dim", ThemeMode.Light to "Light", ThemeMode.System to "System").forEach { (m, label) ->
                        ChipPill(label, selected = s.theme == m) { upd { it.copy(theme = m) } }
                    }
                }
                // What the phone's night mode looks like under System; a fixed theme has no say in it.
                AnimatedVisibility(s.theme == ThemeMode.System, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Dark style", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, modifier = Modifier.weight(1f))
                        DarkStylePill(dim = s.systemDim) { v -> upd { it.copy(systemDim = v) } }
                    }
                }
                Spacer(Modifier.padding(top = 14.dp))
                Text("Accent", style = MaterialTheme.typography.bodyLarge, color = c.text)
                Spacer(Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(AccentMode.MaterialYou to "Material You", AccentMode.Matty to "Matty blue", AccentMode.VillaNet to "Red").forEach { (m, label) ->
                        ChipPill(label, selected = s.accent == m, leading = {
                            Box(Modifier.size(14.dp).background(accentFor(ctx, m, c.isDark), CircleShape))
                        }) { upd { it.copy(accent = m) } }
                    }
                }
                Spacer(Modifier.padding(top = 8.dp))
                SwitchRow("Colorful names", "Give everyone their own name color in chat", s.colorfulNames) { v -> upd { it.copy(colorfulNames = v) } }
            }

            SectionLabel("Chat")
            MattyCard(Modifier.fillMaxWidth()) {
                SwitchRow("Autoplay GIFs", "Animate GIFs in chat as they scroll by", s.autoplayGifs) { v -> upd { it.copy(autoplayGifs = v) } }
                SwitchRow("Send GIFs right away", "Off: a picked GIF goes into the message box first", s.sendGifsImmediately) { v -> upd { it.copy(sendGifsImmediately = v) } }
                SwitchRow("Link previews", "Cards for links, from your server's previews", s.linkPreviews) { v -> upd { it.copy(linkPreviews = v) } }
                SwitchRow("Keep recent messages on this device", "Conversations open at once, then catch up. Encrypted, and cleared when you sign out", s.keepMessages) { v ->
                    upd { it.copy(keepMessages = v) }
                    if (!v) container.wipeSavedMessages()
                }
                SwitchRow("Show who's typing", null, s.showTyping) { v -> upd { it.copy(showTyping = v) } }
                SwitchRow("Let others see you typing", null, s.sendTyping) { v -> upd { it.copy(sendTyping = v) } }
                SwitchRow("Enter sends", "On the on-screen keyboard. Off: Enter adds a new line. A physical keyboard always sends with Enter; Shift+Enter adds a line", s.enterToSend) { v -> upd { it.copy(enterToSend = v) } }
                SwitchRow("Haptics", "A light tap on long-press and send", s.haptics) { v -> upd { it.copy(haptics = v) } }
                Row(Modifier.fillMaxWidth().clip(Matty.RowShape).clickable { pickReaction = true }.padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Double-tap reaction", style = MaterialTheme.typography.bodyLarge, color = c.text)
                        Text("Double-tap any message to react with it", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    }
                    EmojiGlyph(s.doubleTapReaction, 28.dp)
                }
                val crtMode = repo.config.value["CollapsedThreads"]
                if (crtMode == "default_on" || crtMode == "default_off") {
                    val crtOn = remember(prefs) { repo.collapsedThreads }
                    SwitchRow("Collapsed reply threads", "Replies live in threads instead of the channel", crtOn) { v ->
                        repo.launchAction("Couldn't change collapsed threads") { repo.setCollapsedThreads(v) }
                    }
                }
            }

            SectionLabel("Notifications")
            MattyCard(Modifier.fillMaxWidth()) {
                if (!notifGranted) {
                    ActionRow(Icons.Outlined.NotificationsActive, "Allow notifications", "Matty needs permission to tell you about mentions and DMs") {
                        askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                val push = rememberPushView(repo.server.id, notifGranted)
                PushSettings(nav, repo.server.id, push)
                SwitchRow(
                    "Stay connected in the background",
                    "Instant mentions and DMs without Google push — ideal for self-hosted servers and de-Googled phones. Shows a silent ongoing notification.",
                    s.backgroundConnection,
                ) { v -> upd { it.copy(backgroundConnection = v) }; ConnectionService.sync(ctx, v) }
                // Nothing is switched for the person: they are told, and may switch it off themselves.
                if (push.working && s.backgroundConnection) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
                        Text("Push notifications work for this server, so staying connected is no longer needed for its notifications.",
                            style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                        Row(Modifier.padding(top = 8.dp)) {
                            PillButton("Switch it off") { upd { it.copy(backgroundConnection = false) }; ConnectionService.sync(ctx, false) }
                        }
                    }
                }
                SwitchRow(
                    "Notify for every DM",
                    if (push.working) "Pushes follow your notification preferences on this server; this switch is for the live connection"
                    else "Off: only when you're mentioned",
                    s.notifyAllDms,
                ) { v -> upd { it.copy(notifyAllDms = v) } }
                ActionRow(Icons.Outlined.OpenInNew, "System notification settings") {
                    ctx.startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }

            SectionLabel("GIFs")
            MattyCard(Modifier.fillMaxWidth()) {
                Text(
                    if (repo.gifEnabled) "GIFs use your server's Giphy key, the same as Mattermost's desktop app. If it hits its limit, paste your own free key from developers.giphy.com."
                    else "Your server has the GIF picker turned off (System Console → Integrations → GIF).",
                    style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
                )
                Spacer(Modifier.padding(top = 10.dp))
                OutlinedTextField(
                    s.giphyKeyOverride, { v -> upd { it.copy(giphyKeyOverride = v.trim()) } }, singleLine = true,
                    placeholder = { Text("Your own Giphy API key (optional)") }, shape = Matty.FieldShape, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.rim),
                )
            }

            // What this copy of Matty says of itself, and what the server's plugins found of it.
            SectionLabel("Verification")
            MattyCard(Modifier.fillMaxWidth()) {
                val self by ceo.villa.matty.verify.SelfCheck.verdict.collectAsState()
                val official = self == ceo.villa.matty.verify.SelfVerdict.Official
                Text(self?.let { ceo.villa.matty.verify.selfWords(it) } ?: "Checking how this copy of Matty is signed…",
                    style = MaterialTheme.typography.bodySmall, color = if (self == null || official || self == ceo.villa.matty.verify.SelfVerdict.Development) c.textSecondary else c.dnd)
                val plugins by container.plugins.active.collectAsState()
                plugins.forEach { p ->
                    p.checks().forEach { line ->
                        Text(line.text, style = MaterialTheme.typography.bodySmall, color = if (line.warning) c.dnd else c.textSecondary, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }

            // What the server requires of the app: information, not switches (its admin sets them).
            val policy by repo.policy.collectAsState()
            if (policy.blocksCapture || policy.asksUnlock) {
                SectionLabel(repo.siteName)
                MattyCard(Modifier.fillMaxWidth()) {
                    if (policy.blocksCapture) Text("This server blocks screenshots", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    if (policy.asksUnlock) Text("This server asks you to unlock Matty", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
            }
        }
    }
    if (pickReaction) ExpressionPicker(false, false, { e -> upd { it.copy(doubleTapReaction = e) }; pickReaction = false }, null) { pickReaction = false }
}

/**
 * OLED black or Dim, for the System theme at night: one pill, the chosen half filled, the fill
 * gliding across (drawn, so nothing recomposes while it moves).
 */
@Composable
private fun DarkStylePill(dim: Boolean, onChange: (Boolean) -> Unit) {
    val c = Matty.colors
    val at by animateFloatAsState(if (dim) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), label = "darkStyle")
    val fill = c.selected
    Row(
        // Both halves as wide as the wider label needs, at any text size.
        Modifier.height(36.dp).width(IntrinsicSize.Max).clip(RoundedCornerShape(18.dp)).background(c.pill)
            .drawBehind {
                val half = size.width / 2
                val inset = 3.dp.toPx()
                // The row runs the other way in right-to-left languages.
                val x = if (layoutDirection == LayoutDirection.Rtl) half * (1 - at) else half * at
                drawRoundRect(
                    fill, topLeft = Offset(x + inset, inset), size = Size(half - 2 * inset, size.height - 2 * inset),
                    cornerRadius = CornerRadius((size.height - 2 * inset) / 2),
                )
            }
            .selectableGroup(),
    ) {
        listOf(false to "OLED black", true to "Dim").forEach { (value, label) ->
            val chosen = dim == value
            val text by animateColorAsState(if (chosen) c.title else c.textSecondary, label = "darkStyleText")
            Box(
                Modifier.weight(1f).widthIn(min = 92.dp).fillMaxHeight()
                    .selectable(chosen, role = Role.RadioButton, interactionSource = null, indication = null) { if (!chosen) onChange(value) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

// ---- plugins hub --------------------------------------------------------------------------

internal data class KnownPlugin(val name: String, val note: String, val level: Int)

/** How well Matty covers popular plugins (2 native, 1 works through messages/commands, 0 web only). */
internal val knownPlugins = mapOf(
    "com.github.matterpoll.matterpoll" to KnownPlugin("Matterpoll", "Polls render natively with vote buttons.", 1),
    "com.mattermost.calls" to KnownPlugin("Calls", "Voice calls aren't in Matty yet. Join from desktop or web.", 0),
    "playbooks" to KnownPlugin("Playbooks", "Web only. Its /playbook commands work here.", 0),
    "focalboard" to KnownPlugin("Boards", "Web only.", 0),
    "jira" to KnownPlugin("Jira", "Notifications, buttons and /jira commands work.", 1),
    "github" to KnownPlugin("GitHub", "Notifications, buttons and /github commands work.", 1),
    "com.github.manland.mattermost-plugin-gitlab" to KnownPlugin("GitLab", "Notifications, buttons and /gitlab commands work.", 1),
    "com.mattermost.plugin-todo" to KnownPlugin("To Do", "/todo commands and its message buttons work.", 1),
    "com.github.scottleedavis.mattermost-plugin-remind" to KnownPlugin("Remind", "/remind works, with snooze buttons.", 1),
    "com.github.moussetc.mattermost.plugin.giphy" to KnownPlugin("GIF commands", "/gif and its preview buttons work. Matty also has a built-in GIF picker.", 1),
    "mattermost-ai" to KnownPlugin("Agents (AI)", "DM the bot to chat. Its side panel is web only.", 1),
    "com.mattermost.nps" to KnownPlugin("Feedback surveys", "Surveys arrive as messages with buttons.", 1),
)

@Composable
fun PluginsScreen(
    nav: Navigator,
    /** In the main area, beside the side bar (the rail and Back to leave it), not a page of its own. */
    inMain: Boolean = false,
    /** On a phone, the way back to the side bar, as the chat's menu button. */
    onMenu: (() -> Unit)? = null,
) {
    val repo = LocalRepo.current
    val c = Matty.colors
    val ctx = LocalContext.current
    val server by repo.webappPlugins.collectAsState()
    val active by ctx.container.plugins.active.collectAsState()
    var commands by remember { mutableStateOf<List<SlashCommand>?>(null) }
    LaunchedEffect(Unit) {
        val teamId = repo.currentTeamId.value ?: repo.teams.value.firstOrNull()?.id
        commands = if (teamId == null) emptyList() else runCatching { repo.client.teamCommands(teamId) }.getOrDefault(emptyList())
    }
    ceo.villa.matty.ui.home.PageColumn(Modifier.fillMaxSize().background(c.background)) {
        item {
            FloatingTopBar("Plugins", subtitle = "${server.size} active on ${repo.siteName}",
                onBack = if (inMain) null else ({ nav.pop() }), leading = onMenu?.let { { ceo.villa.matty.ui.home.MainPageMenu(it) } })
        }
        item {
            MattyCard(Modifier.padding(16.dp).fillMaxWidth()) {
                Text("How plugins work in Matty", style = MaterialTheme.typography.titleSmall, color = c.title)
                Spacer(Modifier.padding(top = 6.dp))
                Text(
                    "Mattermost's mobile app can't run web plugins, so their features vanish on phones. Matty handles every plugin's slash commands, message buttons and menus, and pop-up dialogs, and ships native modules for plugins it knows — Matty Plugin first. New modules are a small Kotlin file; see docs/PLUGINS.md.",
                    style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
                )
            }
        }
        item { SectionLabel("On this server", Modifier.padding(horizontal = 16.dp)) }
        if (server.isEmpty()) item {
            Text("No plugins with a web part are active, or the server doesn't share its plugin list.", color = c.textSecondary,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        }
        items(server, key = { it.id }) { p ->
            val native = active.firstOrNull { it.id == p.id }
            val k = knownPlugins[p.id]
            val level = if (native != null) 2 else k?.level ?: 1
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(Matty.CardShape).background(c.card)
                    .clickable(enabled = native?.panel != null) { nav.push(Route.Plugin(p.id)) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).background(c.pill, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(native?.panel?.icon ?: Icons.Outlined.Extension, null, tint = c.icon)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    // The name, its version and what Matty does with it share one line, so that
                    // the words under them have the card's whole width: on a phone the card is
                    // narrow, and with the badge at its side they were squeezed into a ribbon.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text(native?.displayName() ?: k?.name ?: p.id, style = MaterialTheme.typography.titleSmall, color = c.title,
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(6.dp))
                            Text("v${p.version}", style = MaterialTheme.typography.labelSmall, color = c.textTertiary, maxLines = 1, softWrap = false)
                        }
                        Spacer(Modifier.width(8.dp))
                        SupportBadge(level)
                    }
                    Text(native?.description ?: k?.note ?: "Commands, message buttons and dialogs work. Web-only panels don't.",
                        style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    // Something to know before expecting it to work (the server's plugin is too old for this app).
                    native?.notice()?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.dnd, modifier = Modifier.padding(top = 4.dp)) }
                    // What the module found when it checked (a signed release; this app verified).
                    native?.checks()?.forEach { line ->
                        Text(line.text, style = MaterialTheme.typography.bodySmall, color = if (line.warning) c.dnd else c.textTertiary, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        item { SectionLabel("Slash commands", Modifier.padding(start = 16.dp, top = 16.dp)) }
        val cmds = commands
        if (cmds == null) item { CenteredLoading() }
        else items(cmds.filter { it.autoComplete || it.trigger.isNotBlank() }.sortedBy { it.trigger }, key = { "cmd:" + it.trigger + it.id }) { cmd ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Terminal, null, tint = c.textSecondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("/${cmd.trigger} ${cmd.autoCompleteHint}".trim(), style = MaterialTheme.typography.bodyMedium, color = c.title, fontWeight = FontWeight.Medium)
                    val d = cmd.autoCompleteDesc.ifBlank { cmd.description }
                    if (d.isNotBlank()) Text(d, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 2)
                }
            }
        }
        item {
            Row(Modifier.padding(16.dp)) {
                PillButton("Write a native module", icon = Icons.Outlined.Info) { nav.openUrl(ceo.villa.matty.BuildConfig.REPO_URL + "/blob/main/docs/PLUGINS.md") }
            }
        }
    }
}

@Composable
private fun SupportBadge(level: Int) {
    val c = Matty.colors
    val (label, color, icon) = when (level) {
        2 -> Triple("Native", c.online, Icons.Outlined.CheckCircle)
        1 -> Triple("Works", c.accent, Icons.Outlined.TouchApp)
        else -> Triple("Web only", c.textTertiary, Icons.Outlined.Info)
    }
    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
    @Suppress("UNUSED_EXPRESSION") Color.Unspecified
}
