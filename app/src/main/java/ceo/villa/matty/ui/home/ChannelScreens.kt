package ceo.villa.matty.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ceo.villa.matty.data.api.Channel
import ceo.villa.matty.data.api.Post
import ceo.villa.matty.data.api.User
import ceo.villa.matty.ui.LocalRepo
import ceo.villa.matty.ui.Navigator
import ceo.villa.matty.ui.Route
import ceo.villa.matty.ui.chat.MarkdownText
import ceo.villa.matty.ui.chat.SearchResult
import ceo.villa.matty.ui.components.ActionRow
import ceo.villa.matty.ui.components.Avatar
import ceo.villa.matty.ui.components.CenteredLoading
import ceo.villa.matty.ui.components.ChipPill
import ceo.villa.matty.ui.components.EmptyState
import ceo.villa.matty.ui.components.FloatingTopBar
import ceo.villa.matty.ui.components.MattyCard
import ceo.villa.matty.ui.components.PillButton
import ceo.villa.matty.ui.components.SectionLabel
import ceo.villa.matty.ui.components.SwitchRow
import ceo.villa.matty.ui.theme.Matty
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ChannelInfoScreen(nav: Navigator, channelId: String) {
    val repo = LocalRepo.current
    val c = Matty.colors
    val channels by repo.channels.collectAsState()
    val members by repo.members.collectAsState()
    val ch = channels[channelId] ?: return
    val muted = members[channelId]?.isMuted == true
    var memberCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(channelId) {
        memberCount = runCatching { repo.client.channelStats(channelId)["member_count"]?.toString()?.toIntOrNull() }.getOrNull()
    }
    LazyColumn(Modifier.fillMaxSize().background(c.background), contentPadding = PaddingValues(bottom = 48.dp)) {
        item { FloatingTopBar(if (ch.isDmOrGm) repo.channelTitle(ch) else "#" + repo.channelTitle(ch), subtitle = memberCount?.let { "$it members" }, onBack = { nav.pop() }) }
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (ch.isDirect) {
                    val other = ch.otherUserId(repo.myId)
                    if (other != null) PillButton("View profile", primary = true, modifier = Modifier.fillMaxWidth()) { nav.openUser(other) }
                }
                if (ch.purpose.isNotBlank() || ch.header.isNotBlank()) MattyCard(Modifier.fillMaxWidth()) {
                    if (ch.purpose.isNotBlank()) { SectionLabel("Purpose"); MarkdownText(ch.purpose, style = MaterialTheme.typography.bodyMedium) }
                    if (ch.header.isNotBlank()) { Spacer(Modifier.height(8.dp)); SectionLabel("Header"); MarkdownText(ch.header, style = MaterialTheme.typography.bodyMedium) }
                }
                MattyCard(Modifier.fillMaxWidth(), padding = PaddingValues(8.dp)) {
                    ActionRow(Icons.Outlined.PushPin, "Pinned messages") { nav.push(Route.Pinned(channelId)) }
                    ActionRow(Icons.Outlined.Search, "Search in channel") { nav.push(Route.Search("in:${ch.name} ")) }
                    SwitchRow("Mute channel", "No unread dot or notifications unless you're mentioned", muted) { v ->
                        repo.launchAction(if (v) "Couldn't mute the channel" else "Couldn't unmute the channel") { repo.setChannelMuted(channelId, v) }
                    }
                    if (!ch.isDirect && ch.name != "town-square") ActionRow(Icons.AutoMirrored.Outlined.Logout, "Leave channel", tint = c.destructive) {
                        // Finishes even if this screen is closed meanwhile; then only steps away
                        // from the channel if the user is still in it.
                        repo.launchAction("Couldn't leave the channel") {
                            repo.leaveChannel(channelId)
                            if (nav.channelId == channelId) {
                                nav.channelId = null
                                nav.stack.clear()
                            }
                        }
                    }
                }
            }
        }
    }
    @Suppress("UNUSED_EXPRESSION") listOf(Icons.Outlined.Notifications, Icons.Outlined.NotificationsOff)
}

@Composable
fun PinnedScreen(nav: Navigator, channelId: String) {
    val repo = LocalRepo.current
    val users by repo.users.collectAsState()
    var list by remember { mutableStateOf<List<Post>?>(null) }
    LaunchedEffect(channelId) { list = runCatching { repo.pinnedPosts(channelId) }.getOrDefault(emptyList()) }
    Column(Modifier.fillMaxSize().background(Matty.colors.background)) {
        FloatingTopBar("Pinned", onBack = { nav.pop() })
        val l = list
        when {
            l == null -> CenteredLoading()
            l.isEmpty() -> EmptyState(Icons.Outlined.PushPin, "Nothing pinned", "Long-press a message and pin it so everyone can find it.")
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) { items(l, key = { it.id }) { SearchResult(nav, it, users) } }
        }
    }
}

/** Find people and start a DM, or pick several for a group. */
@Composable
fun NewMessageScreen(nav: Navigator) {
    val repo = LocalRepo.current
    val c = Matty.colors
    val scope = rememberCoroutineScope()
    val statuses by repo.statuses.collectAsState()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<User>>(emptyList()) }
    val picked = remember { mutableStateListOf<User>() }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        delay(200)
        results = ceo.villa.matty.data.runCatchingCancellable {
            if (query.isBlank()) repo.client.autocompleteUsers("", repo.currentTeamId.value, null)
            else repo.client.searchUsers(query.trim(), null)
        }.getOrDefault(emptyList()).filter { it.id != repo.myId && it.deleteAt == 0L }
        repo.users.value = repo.users.value + results.associateBy { it.id }
        repo.refreshStatuses(results.map { it.id })
    }
    Column(Modifier.fillMaxSize().background(c.background).navigationBarsPadding()) {
        FloatingTopBar("New Message", subtitle = if (picked.size > 1) "Group of ${picked.size + 1}" else null, onBack = { nav.pop() })
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(24.dp)).background(c.pill).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, null, tint = c.textSecondary)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Who do you want to talk to?", color = c.textTertiary, style = MaterialTheme.typography.bodyLarge)
                BasicTextField(query, { query = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
            }
        }
        if (picked.isNotEmpty()) FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            picked.forEach { u -> ChipPill(repo.nameOf(u), selected = true, leading = { Icon(Icons.Outlined.Close, null, modifier = Modifier.size(14.dp)) }) { picked.remove(u) } }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(results, key = { it.id }) { u ->
                val sel = picked.any { it.id == u.id }
                Row(Modifier.fillMaxWidth().clickable { if (sel) picked.removeAll { it.id == u.id } else picked += u }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(repo.avatarUrl(u.id), repo.nameOf(u), 40.dp, status = statuses[u.id] ?: "offline")
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(repo.nameOf(u), style = MaterialTheme.typography.bodyLarge, color = c.title)
                            if (u.isBot) BotTag()
                        }
                        Text("@${u.username}", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    }
                    Box(Modifier.size(26.dp).clip(CircleShape).background(if (sel) c.accent else c.pill), contentAlignment = Alignment.Center) {
                        if (sel) Icon(Icons.Outlined.Check, null, tint = c.onAccent, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        PillButton(if (picked.size > 1) "Start group" else "Message", primary = true, enabled = picked.isNotEmpty(), loading = busy,
            modifier = Modifier.fillMaxWidth().padding(16.dp), height = 56.dp) {
            busy = true
            val ids = picked.map { it.id }
            scope.launch {
                val ch = repo.runAction("Couldn't start the conversation") {
                    if (ids.size == 1) repo.openDirect(ids.first()) else repo.openGroup(ids)
                }
                if (ch != null) { nav.pop(); nav.selectTeam(null); nav.openChannel(ch.id) }
                busy = false
            }
        }
    }
}

private fun Navigator.selectTeam(id: String?) = repo.selectTeam(id)

@Composable
fun BrowseChannelsScreen(
    nav: Navigator,
    /** In the main area, beside the side bar (the rail and Back to leave it), not a page of its own. */
    inMain: Boolean = false,
    /** On a phone, the way back to the side bar, as the chat's menu button. */
    onMenu: (() -> Unit)? = null,
) {
    val repo = LocalRepo.current
    val c = Matty.colors
    val scope = rememberCoroutineScope()
    val teamId = repo.currentTeamId.value ?: repo.teams.value.firstOrNull()?.id
    val mine by repo.channels.collectAsState()
    var list by remember { mutableStateOf<List<Channel>?>(null) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(teamId) {
        list = if (teamId == null) emptyList() else runCatching { repo.client.browseChannels(teamId) }.getOrDefault(emptyList()).filter { it.deleteAt == 0L }.sortedBy { it.displayName.lowercase() }
    }
    PageColumn(Modifier.fillMaxSize().background(c.background)) {
        item {
            FloatingTopBar("Channels", subtitle = repo.teams.value.firstOrNull { it.id == teamId }?.displayName,
                onBack = if (inMain) null else ({ nav.pop() }), leading = onMenu?.let { { MainPageMenu(it) } })
        }
        item {
            if (creating) CreateChannelCard(teamId ?: "", onDone = { ch ->
                creating = false
                if (ch != null) { repo.channels.value = repo.channels.value + (ch.id to ch); repo.launchSafe { teamId?.let { repo.loadTeam(it) } }; nav.openChannel(ch.id) }
            }) else Row(Modifier.padding(16.dp)) { PillButton("Create a channel", icon = Icons.Outlined.Add, primary = true) { creating = true } }
        }
        val l = list
        if (l == null) item { CenteredLoading() }
        else items(l, key = { it.id }) { ch ->
            val joined = mine.containsKey(ch.id)
            Row(Modifier.fillMaxWidth().clickable {
                if (joined) nav.openChannel(ch.id) else scope.launch {
                    if (repo.runAction("Couldn't join the channel") { repo.joinChannel(ch.id, teamId) } != null) nav.openChannel(ch.id)
                }
            }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (ch.isPrivate) Icons.Outlined.Lock else Icons.Outlined.Tag, null, tint = c.textSecondary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(ch.displayName, style = MaterialTheme.typography.bodyLarge, color = c.title)
                    if (ch.purpose.isNotBlank()) Text(ch.purpose, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(if (joined) "Joined" else "Join", color = if (joined) c.textTertiary else c.accent, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    @Suppress("UNUSED_EXPRESSION") Icons.Outlined.Groups
}

@Composable
private fun CreateChannelCard(teamId: String, onDone: (Channel?) -> Unit) {
    val repo = LocalRepo.current
    val c = Matty.colors
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var purpose by remember { mutableStateOf("") }
    var private by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.rim)
    MattyCard(Modifier.padding(16.dp).fillMaxWidth()) {
        SectionLabel("New channel")
        OutlinedTextField(name, { name = it.take(64) }, placeholder = { Text("Name") }, singleLine = true, shape = Matty.FieldShape, colors = colors, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(purpose, { purpose = it.take(250) }, placeholder = { Text("What's it for? (optional)") }, shape = Matty.FieldShape, colors = colors, modifier = Modifier.fillMaxWidth())
        SwitchRow("Private", "Only invited people can see it", private) { private = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Cancel", enabled = !busy) { onDone(null) }
            PillButton("Create", primary = true, enabled = name.isNotBlank(), loading = busy) {
                busy = true
                val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60).ifBlank { "channel-${System.currentTimeMillis() % 100000}" }
                val title = name.trim(); val about = purpose.trim(); val isPrivate = private
                scope.launch {
                    val created = repo.runAction("Couldn't create the channel") {
                        repo.client.createChannel(teamId, slug, title, about, isPrivate).also { ch -> repo.channels.value = repo.channels.value + (ch.id to ch) }
                    }
                    if (created != null) onDone(created) else busy = false
                }
            }
        }
    }
}
