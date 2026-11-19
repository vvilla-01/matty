package ceo.villa.matty.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.LayoutDirection
import ceo.villa.matty.ui.RailDirect
import ceo.villa.matty.ui.RailDiscover
import ceo.villa.matty.ui.RailPage
import ceo.villa.matty.ui.RailPlugins
import ceo.villa.matty.ui.railSelection
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ceo.villa.matty.R
import ceo.villa.matty.container
import ceo.villa.matty.data.api.Channel
import ceo.villa.matty.data.api.Team
import ceo.villa.matty.data.emoji.EmojiStore
import ceo.villa.matty.ui.LocalRepo
import ceo.villa.matty.ui.Navigator
import ceo.villa.matty.ui.Route
import ceo.villa.matty.ui.components.Avatar
import ceo.villa.matty.ui.components.CountBadge
import ceo.villa.matty.ui.theme.Matty
import coil3.compose.SubcomposeAsyncImage

/** The vertical team rail, Discord's server list. */
@Composable
fun ServerRail(nav: Navigator, modifier: Modifier = Modifier) {
    val repo = LocalRepo.current
    val c = Matty.colors
    val teams by repo.teams.collectAsState()
    val current by repo.currentTeamId.collectAsState()
    val unreads by repo.teamUnreads.collectAsState()
    val channels by repo.channels.collectAsState()
    val members by repo.members.collectAsState()
    val prefs by repo.preferences.collectAsState()
    val dmMentions = remember(channels, members, prefs) {
        channels.values.filter { it.isDmOrGm }.sumOf { repo.unreadOf(it, members[it.id]).let { u -> if (u.muted) 0L else if (it.isDirect && u.unread) maxOf(1L, u.mentions) else u.mentions } }
    }
    val page = nav.railPages.current
    val selected = railSelection(current, page)
    val list = rememberLazyListState()
    val keys = remember(teams) { listOf(RailDirect, RailDivider) + teams.map { it.id } + listOf(RailDiscover, RailPlugins) }
    val bar = remember { RailBar(selected) }
    LaunchedEffect(selected) { bar.moveTo(selected, list, keys) }
    val barColor = c.title
    LazyColumn(
        modifier.width(RailWidth).fillMaxHeight().statusBarsPadding()
            .drawWithContent { drawContent(); bar.draw(this, list, keys, barColor) },
        state = list,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp + ceo.villa.matty.ui.BottomChrome.barSpace),
    ) {
        item(key = RailDirect) {
            RailItem(selected = selected == RailDirect, unread = false, mentions = dmMentions.toInt(), onClick = { nav.selectSpace(null) }) { sel ->
                Box(Modifier.fillMaxSize().background(if (sel) c.accent else c.pill), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.ic_launcher_foreground), "Direct messages", modifier = Modifier.size(52.dp),
                        colorFilter = ColorFilter.tint(if (sel) c.onAccent else c.icon))
                }
            }
        }
        item(key = RailDivider) { HorizontalDivider(Modifier.width(32.dp), color = c.selected, thickness = 2.dp) }
        items(teams, key = { it.id }) { t ->
            val isCurrent = current == t.id
            // The current team's badge comes from live channel state; others from /teams/unread.
            val (unread, mentions) = if (isCurrent) {
                val list = channels.values.filter { it.teamId == t.id }
                val u = list.map { repo.unreadOf(it, members[it.id]) }
                (u.any { it.unread && !it.muted }) to u.sumOf { it.mentions }.toInt()
            } else {
                val u = unreads[t.id]
                val crt = repo.collapsedThreads
                val msgs = if (crt) u?.msgCountRoot ?: 0 else u?.msgCount ?: 0
                (msgs > 0) to ((if (crt) u?.mentionCountRoot else u?.mentionCount) ?: 0).toInt()
            }
            RailItem(selected = selected == t.id, unread = unread, mentions = mentions, onClick = { nav.selectSpace(t.id) }) { _ -> TeamIcon(t) }
        }
        item(key = RailDiscover) {
            RailItem(selected = selected == RailDiscover, unread = false, mentions = 0, onClick = { nav.openRailPage(RailPage.Discover) }) { _ ->
                Box(Modifier.fillMaxSize().background(c.pill), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Explore, "Browse channels", tint = c.online)
                }
            }
        }
        item(key = RailPlugins) {
            RailItem(selected = selected == RailPlugins, unread = false, mentions = 0, onClick = { nav.openRailPage(RailPage.Plugins) }) { _ ->
                Box(Modifier.fillMaxSize().background(c.pill), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Extension, "Plugins", tint = c.icon)
                }
            }
        }
    }
}

/** The rail's width, and the gap the channel list keeps after it, in every layout. */
val RailWidth = 72.dp
val ChannelPanelGap = 8.dp

private const val RailDivider = "rail:divider"

/**
 * The rail's one selection bar, sliding from button to button on a spring with a little bounce.
 * Where it is comes from the list's own layout as it is drawn, so it follows the rail's scrolling
 * and moves without the rail recomposing: only [progress] animates, and only the drawing reads it.
 */
private class RailBar(initial: String) {
    private var from: String = initial
    /** How far from [from]'s button the slide set off (it may have been mid-way to another). */
    private var fromDelta = 0f
    private var to: String = initial
    private val progress = Animatable(1f)

    /** The middle of [key]'s button in the list's own space, or a place just off the end it's beyond. */
    private fun centre(state: LazyListState, keys: List<String>, key: String): Float? {
        val info = state.layoutInfo
        info.visibleItemsInfo.firstOrNull { it.key == key }?.let { return it.offset - info.viewportStartOffset + it.size / 2f }
        val index = keys.indexOf(key)
        val first = info.visibleItemsInfo.firstOrNull() ?: return null
        if (index < 0) return null
        val height = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
        return if (index < first.index) -height / 4 else height + height / 4
    }

    private fun place(state: LazyListState, keys: List<String>): Pair<Float, Float>? {
        val target = centre(state, keys, to) ?: return null
        val start = centre(state, keys, from)?.plus(fromDelta) ?: target
        return start + (target - start) * progress.value to start
    }

    suspend fun moveTo(key: String, state: LazyListState, keys: List<String>) {
        if (key == to) return
        // From wherever the bar is now, mid-slide too, so it never jumps.
        val now = place(state, keys)?.first
        val was = centre(state, keys, to)
        fromDelta = if (now != null && was != null) now - was else 0f
        from = to
        to = key
        progress.snapTo(0f)
        progress.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow))
    }

    fun draw(scope: ContentDrawScope, state: LazyListState, keys: List<String>, color: Color) = with(scope) {
        val (y, start) = place(state, keys) ?: return@with
        val p = progress.value
        // The selected mark's 40dp, stretched a little mid-way and settling back on arrival.
        val stretch = minOf(kotlin.math.abs(y - start), 24.dp.toPx()) * (4 * p * (1 - p)).coerceAtLeast(0f)
        val length = 40.dp.toPx() + stretch
        val w = 4.dp.toPx()
        // Rounded on the inner side only: the outer half is cut off at the rail's edge.
        val x = if (layoutDirection == LayoutDirection.Rtl) size.width - w else -w
        clipRect(0f, 0f, size.width, size.height) {
            drawRoundRect(color, topLeft = Offset(x, y - length / 2), size = Size(2 * w, length), cornerRadius = CornerRadius(w))
        }
    }
}

@Composable
private fun RailItem(selected: Boolean, unread: Boolean, mentions: Int, onClick: () -> Unit, content: @Composable (Boolean) -> Unit) {
    val c = Matty.colors
    // Discord's morph: circles become squircles when selected. A pill on the left marks an
    // unread team; the selected one's is the rail's travelling bar (see RailBar).
    val radius by animateDpAsState(if (selected) 16.dp else 26.dp, label = "radius")
    val pill by animateDpAsState(if (unread && !selected) 8.dp else 0.dp, label = "pill")
    Box(Modifier.width(RailWidth).height(52.dp)) {
        Box(Modifier.align(Alignment.CenterStart).width(4.dp).height(pill).background(c.title, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)))
        Box(Modifier.align(Alignment.Center).size(52.dp).clip(RoundedCornerShape(radius)).clickable(onClick = onClick)) { content(selected) }
        if (mentions > 0) CountBadge(mentions, Modifier.align(Alignment.BottomEnd).padding(end = 6.dp))
    }
}

@Composable
private fun TeamIcon(t: Team) {
    val repo = LocalRepo.current
    val c = Matty.colors
    val initials = t.displayName.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase()
    if (t.lastTeamIconUpdate > 0) {
        Box(Modifier.fillMaxSize().background(c.pill), contentAlignment = Alignment.Center) {
            Text(initials, color = c.title)
            coil3.compose.AsyncImage(model = repo.client.teamIconUrl(t.id, t.lastTeamIconUpdate), contentDescription = t.displayName,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    } else {
        Box(Modifier.fillMaxSize().background(c.pill), contentAlignment = Alignment.Center) {
            Text(initials, color = c.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ---- channel sidebar ----------------------------------------------------------------------

@Composable
fun ChannelSidebar(nav: Navigator, modifier: Modifier = Modifier) {
    val repo = LocalRepo.current
    val current by repo.currentTeamId.collectAsState()
    Box(modifier.fillMaxSize().padding(end = ChannelPanelGap).statusBarsPadding().padding(top = 8.dp)
        .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Matty.colors.card)) {
        if (current == null) DmSidebar(nav) else TeamSidebar(nav, current!!)
    }
}

@Composable
private fun SearchPill(text: String, onClick: () -> Unit) {
    val c = Matty.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(40.dp).clip(RoundedCornerShape(20.dp)).background(c.pill)
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = c.textSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = c.textSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TeamSidebar(nav: Navigator, teamId: String) {
    val repo = LocalRepo.current
    val c = Matty.colors
    val teams by repo.teams.collectAsState()
    val channels by repo.channels.collectAsState()
    val members by repo.members.collectAsState()
    val cats by repo.categories.collectAsState()
    val threads by repo.threads.collectAsState()
    val team = teams.firstOrNull { it.id == teamId }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    val categories = cats[teamId].orEmpty().filter { it.type != "direct_messages" }
    // What the server's modules add after the categories (VillaNET: its voice channels), each asked under its own key.
    val plugins by LocalContext.current.container.plugins.active.collectAsState()
    val sections = plugins.mapNotNull { p -> androidx.compose.runtime.key(p.id) { p.sidebarSection(nav, teamId) } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp + ceo.villa.matty.ui.BottomChrome.barSpace)) {
        item {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 10.dp)) {
                Text(team?.displayName.orEmpty(), style = MaterialTheme.typography.titleLarge, color = c.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(repo.siteName, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
            SearchPill("Search ${team?.displayName.orEmpty()}") { nav.push(Route.Search()) }
            Spacer(Modifier.height(10.dp))
            if (repo.collapsedThreads) {
                val unreadThreads = threads.count { it.unreadReplies > 0 }
                SidebarRow(
                    icon = { Icon(Icons.Outlined.Forum, null, tint = if (unreadThreads > 0) c.title else c.textSecondary, modifier = Modifier.size(20.dp)) },
                    name = "Threads", unread = unreadThreads > 0, mentions = threads.sumOf { it.unreadMentions }, selected = false, muted = false,
                ) { nav.tab = ceo.villa.matty.ui.HomeTab.Activity }
            }
        }
        if (categories.isEmpty()) {
            val list = channels.values.filter { it.teamId == teamId && !it.isDmOrGm }.sortedBy { it.displayName.lowercase() }
            items(list, key = { it.id }) { ch -> ChannelRow(nav, ch) }
        }
        categories.forEach { cat ->
            val ids = cat.channelIds.filter { channels.containsKey(it) }
            if (ids.isEmpty() && cat.type != "favorites") return@forEach
            if (ids.isEmpty()) return@forEach
            val isCollapsed = collapsed[cat.id] ?: cat.collapsed
            item(key = "cat:${cat.id}") {
                SidebarCategoryHeader(cat.displayName, isCollapsed) { collapsed[cat.id] = !isCollapsed }
            }
            val sorted = when (cat.sorting) {
                "alpha" -> ids.sortedBy { channels[it]?.displayName?.lowercase() }
                "recent" -> ids.sortedByDescending { channels[it]?.lastPostAt }
                else -> ids
            }
            items(sorted, key = { "${cat.id}:$it" }) { id ->
                val ch = channels[id] ?: return@items
                val u = repo.unreadOf(ch, members[id])
                // Collapsed categories still show unread channels, like Mattermost and Discord.
                AnimatedVisibility(!isCollapsed || u.unread || u.mentions > 0 || nav.channelId == id,
                    enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    ChannelRow(nav, ch)
                }
            }
        }
        for (section in sections) section(this)
        item {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 14.dp)) {
                SidebarRow(icon = { Icon(Icons.Outlined.Add, null, tint = c.textSecondary, modifier = Modifier.size(20.dp)) },
                    name = "Browse or create channels", unread = false, mentions = 0, selected = false, muted = false) { nav.push(Route.BrowseChannels) }
            }
        }
    }
}

@Composable
private fun ChannelRow(nav: Navigator, ch: Channel) {
    val repo = LocalRepo.current
    val c = Matty.colors
    val members by repo.members.collectAsState()
    val u = repo.unreadOf(ch, members[ch.id])
    val icon = when {
        ch.isPrivate -> Icons.Outlined.Lock
        ch.isGroup -> Icons.Outlined.Groups
        else -> Icons.Outlined.Tag
    }
    SidebarRow(
        icon = { Icon(icon, null, tint = if (u.unread && !u.muted) c.title else c.textTertiary, modifier = Modifier.size(20.dp)) },
        name = repo.channelTitle(ch),
        unread = u.unread && !u.muted, mentions = u.mentions.toInt(), selected = nav.openChannelId == ch.id, muted = u.muted,
    ) { nav.selectChannel(ch.id) }
}

/** A category's header in the channel list: its name in capitals and a chevron that folds it. */
@Composable
internal fun SidebarCategoryHeader(title: String, collapsed: Boolean, onToggle: () -> Unit) {
    val c = Matty.colors
    val rot by animateFloatAsState(if (collapsed) -90f else 0f, label = "chev")
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = if (collapsed) "Expand" else "Collapse", onClick = onToggle)
            .padding(start = 12.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.ExpandMore, null, tint = c.textTertiary, modifier = Modifier.size(16.dp).rotate(rot))
        Spacer(Modifier.width(4.dp))
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = c.textSecondary, fontWeight = FontWeight.SemiBold)
    }
}

/** One row of the channel list: an icon, the name (and a line under it), and its marks at the end. */
@Composable
internal fun SidebarRow(
    icon: @Composable () -> Unit,
    name: String,
    unread: Boolean,
    mentions: Int,
    selected: Boolean,
    muted: Boolean,
    subtitle: String? = null,
    /** In place of [subtitle], for a line that's more than words (a DM's status line). */
    subtitleContent: (@Composable () -> Unit)? = null,
    /** Drawn at the end, before the muted mark and the count (a voice channel's "2/4"). */
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = Matty.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp).clip(Matty.RowShape)
            .background(if (selected) c.selected else Color.Transparent)
            // Sized (and so clipped) outside the padding: the badges on an avatar's rim overhang it.
            .clickable(onClick = onClick).animateContentSize().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                color = when { selected || unread -> c.title; muted -> c.textTertiary; else -> c.textSecondary },
                fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (subtitleContent != null) subtitleContent()
            else if (subtitle != null) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
        }
        trailing?.invoke()
        if (muted) Icon(Icons.Outlined.NotificationsOff, "Muted", tint = c.textTertiary, modifier = Modifier.size(16.dp))
        if (mentions > 0) { Spacer(Modifier.width(6.dp)); CountBadge(mentions) }
    }
}

// ---- direct messages ----------------------------------------------------------------------

@Composable
fun DmSidebar(nav: Navigator) {
    val repo = LocalRepo.current
    val c = Matty.colors
    val channels by repo.channels.collectAsState()
    val list = remember(channels) { channels.values.filter { it.isDmOrGm }.sortedByDescending { it.lastPostAt } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp + ceo.villa.matty.ui.BottomChrome.barSpace)) {
        item {
            Text("Direct Messages", style = MaterialTheme.typography.titleLarge, color = c.title,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 10.dp))
            SearchPill("Find or start a conversation") { nav.push(Route.NewMessage) }
            Spacer(Modifier.height(8.dp))
        }
        if (list.isEmpty()) item {
            Text("No conversations yet. Start one with anyone on ${repo.siteName}.", color = c.textSecondary,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
        }
        items(list, key = { it.id }) { ch -> DmRow(nav, ch) }
    }
}

@Composable
fun DmRow(nav: Navigator, ch: Channel, large: Boolean = false) {
    val repo = LocalRepo.current
    val c = Matty.colors
    val users by repo.users.collectAsState()
    val statuses by repo.statuses.collectAsState()
    val members by repo.members.collectAsState()
    val other = ch.otherUserId(repo.myId)
    val user = other?.let { users[it] }
    val u = repo.unreadOf(ch, members[ch.id])
    // A group has nobody to say it about.
    val facts = ceo.villa.matty.plugins.personOf(if (ch.isGroup) null else other)
    val live = facts.live
    val custom = ownStatus(user, facts)
    val activity = facts.activity
    // A DM's line is the member list's (custom status, what they're playing, live); a group's says who.
    val statusLine = !ch.isGroup && hasStatusLine(custom, live, activity)
    val subtitle = when {
        ch.isGroup -> "${ch.displayName.count { it == ',' } + 1} members"
        statusLine -> null
        user?.isBot == true -> "Bot"
        else -> null
    }
    val selected = nav.openChannelId == ch.id
    SidebarRow(
        icon = {
            if (ch.isGroup) Box(Modifier.size(if (large) 44.dp else 36.dp).background(c.pill, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Groups, null, tint = c.icon, modifier = Modifier.size(20.dp))
            } else Avatar(other?.let { repo.avatarUrl(it) }, repo.channelTitle(ch), if (large) 44.dp else 36.dp,
                // Cut out of the row behind them: the open conversation's row is highlighted.
                status = other?.let { statuses[it] ?: "offline" }, ringColor = if (selected) c.selected else c.card, live = live,
                voice = facts.voice)
        },
        name = repo.channelTitle(ch), unread = u.unread && !u.muted,
        mentions = if (ch.isDirect && u.unread && !u.muted) maxOf(1, u.mentions.toInt()) else u.mentions.toInt(),
        selected = selected, muted = u.muted, subtitle = subtitle,
        subtitleContent = if (statusLine) { { StatusLine(custom, live, activity, c.textTertiary) } } else null,
    ) { nav.selectChannel(ch.id) }
}

@Suppress("unused")
private fun unusedCombined() = Modifier.combinedClickable { }
