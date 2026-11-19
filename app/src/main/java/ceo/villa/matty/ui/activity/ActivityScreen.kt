package ceo.villa.matty.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ceo.villa.matty.data.api.Post
import ceo.villa.matty.data.api.UserThread
import ceo.villa.matty.ui.LocalRepo
import ceo.villa.matty.ui.Navigator
import ceo.villa.matty.ui.Route
import ceo.villa.matty.ui.chat.SearchResult
import ceo.villa.matty.ui.chat.dayLabel
import ceo.villa.matty.ui.chat.plainPreview
import ceo.villa.matty.ui.chat.sameDay
import ceo.villa.matty.ui.chat.timeLabel
import ceo.villa.matty.ui.components.Avatar
import ceo.villa.matty.ui.components.CenteredLoading
import ceo.villa.matty.ui.components.ChipPill
import ceo.villa.matty.ui.components.CountBadge
import ceo.villa.matty.ui.components.EmptyState
import ceo.villa.matty.ui.components.FloatingTopBar
import ceo.villa.matty.ui.components.Pill
import ceo.villa.matty.ui.components.PillIconButton
import ceo.villa.matty.ui.home.DmRow
import ceo.villa.matty.ui.theme.Matty

/** Discord's "Notifications" tab, Mattermost-style: mentions, followed threads, saved posts. */
@Composable
fun ActivityScreen(nav: Navigator) {
    val repo = LocalRepo.current
    var tab by rememberSaveable { mutableStateOf(if (repo.collapsedThreads) 1 else 0) }
    Column(Modifier.fillMaxSize()) {
        FloatingTopBar("Activity", subtitle = repo.siteName)
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChipPill("Mentions", selected = tab == 0) { tab = 0 }
            if (repo.collapsedThreads) ChipPill("Threads", selected = tab == 1) { tab = 1 }
            ChipPill("Saved", selected = tab == 2) { tab = 2 }
        }
        when (tab) {
            0 -> PostFeed(nav, key = "mentions", empty = { EmptyState(Icons.Outlined.AlternateEmail, "No mentions yet", "When someone @mentions you, it shows up here.") }) { repo.recentMentions() }
            1 -> ThreadsFeed(nav)
            2 -> PostFeed(nav, key = "saved", empty = { EmptyState(Icons.Outlined.BookmarkBorder, "Nothing saved", "Long-press a message and tap Save to keep it here.") }) { repo.savedPosts() }
        }
    }
}

@Composable
private fun PostFeed(nav: Navigator, key: String, empty: @Composable () -> Unit, load: suspend () -> List<Post>) {
    val repo = LocalRepo.current
    val users by repo.users.collectAsState()
    var list by remember(key) { mutableStateOf<List<Post>?>(null) }
    LaunchedEffect(key) { list = runCatching { load() }.getOrDefault(emptyList()) }
    val l = list
    when {
        l == null -> CenteredLoading()
        l.isEmpty() -> empty()
        else -> LazyColumn(contentPadding = PaddingValues(bottom = 120.dp + ceo.villa.matty.ui.BottomChrome.barSpace)) { items(l, key = { it.id }) { SearchResult(nav, it, users) } }
    }
}

@Composable
private fun ThreadsFeed(nav: Navigator) {
    val repo = LocalRepo.current
    val threads by repo.threads.collectAsState()
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { repo.refreshThreads() }; loaded = true }
    when {
        !loaded && threads.isEmpty() -> CenteredLoading()
        threads.isEmpty() -> EmptyState(Icons.Outlined.Forum, "No followed threads", "Threads you start, reply to or are mentioned in land here.")
        else -> LazyColumn(contentPadding = PaddingValues(bottom = 120.dp + ceo.villa.matty.ui.BottomChrome.barSpace)) { items(threads, key = { it.id }) { ThreadRow(nav, it) } }
    }
}

@Composable
private fun ThreadRow(nav: Navigator, t: UserThread) {
    val repo = LocalRepo.current
    val c = Matty.colors
    val ch = repo.channels.value[t.post.channelId]
    val unread = t.unreadReplies > 0
    Row(Modifier.fillMaxWidth().clickable { nav.openThread(t.id) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Avatar(repo.avatarUrl(t.post.userId), repo.nameOf(t.post.userId), 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(repo.nameOf(t.post.userId), style = MaterialTheme.typography.titleSmall, color = c.title, fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                Text(ch?.let { "in " + (if (it.isDmOrGm) repo.channelTitle(it) else "#" + repo.channelTitle(it)) }.orEmpty(),
                    style = MaterialTheme.typography.labelMedium, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (t.unreadMentions > 0) CountBadge(t.unreadMentions)
                else if (unread) Box(Modifier.size(10.dp).background(c.accent, CircleShape))
            }
            Text(plainPreview(t.post, repo), style = MaterialTheme.typography.bodyMedium, color = if (unread) c.text else c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.padding(top = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                t.participants.take(4).forEachIndexed { i, u -> Avatar(repo.avatarUrl(u.id), repo.nameOf(u), 20.dp, modifier = Modifier.offset(x = (-6 * i).dp)) }
                Spacer(Modifier.width(4.dp))
                Text("${t.replyCount} ${if (t.replyCount == 1) "reply" else "replies"}", color = c.accent, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                Text(if (sameDay(t.lastReplyAt, System.currentTimeMillis())) timeLabel(t.lastReplyAt) else dayLabel(t.lastReplyAt), color = c.textTertiary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

