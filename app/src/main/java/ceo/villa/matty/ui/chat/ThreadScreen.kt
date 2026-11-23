package ceo.villa.matty.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ceo.villa.matty.container
import ceo.villa.matty.data.api.Post
import ceo.villa.matty.ui.LocalRepo
import ceo.villa.matty.ui.Navigator
import ceo.villa.matty.ui.Route
import ceo.villa.matty.ui.components.CenteredLoading
import ceo.villa.matty.ui.components.EmptyState
import ceo.villa.matty.ui.components.FloatingTopBar
import ceo.villa.matty.ui.components.Pill
import ceo.villa.matty.ui.components.PillIconButton
import ceo.villa.matty.ui.picker.ExpressionPicker
import ceo.villa.matty.ui.theme.Matty
import ceo.villa.matty.data.giphy.GiphyClient
import kotlinx.coroutines.launch

@Composable
fun ThreadScreen(nav: Navigator, rootId: String) {
    val repo = LocalRepo.current
    val c = Matty.colors
    val scope = rememberCoroutineScope()
    val orders by repo.threadOrders.collectAsState()
    val posts by repo.posts.collectAsState()
    val users by repo.users.collectAsState()
    val sendStates by repo.sendStates.collectAsState()
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(rootId) { runCatching { repo.loadThread(rootId) }.onFailure { failed = true } }
    val root = posts[rootId]
    val ids = orders[rootId]
    val rows = remember(ids, posts, sendStates) {
        val list = threadNewestFirst(ids.orEmpty().mapNotNull { posts[it] }.filter { it.deleteAt == 0L }, sendStates)
        buildRows(list, 0, repo.myId, sendStates, inThread = true)
    }
    val ch = root?.let { repo.channels.value[it.channelId] }
    var following by remember(root?.isFollowing) { mutableStateOf(root?.isFollowing ?: true) }
    var picker by remember { mutableStateOf<Pair<PickerMode, (String) -> Unit>?>(null) }
    var reactTo by remember { mutableStateOf<Post?>(null) }
    var sheetFor by remember { mutableStateOf<Post?>(null) }
    var whoFor by remember { mutableStateOf<Pair<Post, String>?>(null) }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }

    Column(Modifier.fillMaxSize().background(c.background).imePadding()) {
        FloatingTopBar("Thread", subtitle = ch?.let { if (it.isDmOrGm) repo.channelTitle(it) else "#" + repo.channelTitle(it) }, onBack = { nav.pop() }) {
            if (repo.collapsedThreads && root != null) Pill {
                PillIconButton(if (following) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsNone, if (following) "Unfollow thread" else "Follow thread") {
                    val teamId = ch?.teamId?.ifEmpty { null } ?: repo.currentTeamId.value ?: return@PillIconButton
                    scope.launch { runCatching { repo.client.followThread(repo.myId, teamId, rootId, !following) }.onSuccess { following = !following } }
                }
            }
        }
        Box(Modifier.weight(1f)) {
            // As in the channel: the reply pill floats over the thread, and rises as it opens.
            var dock by remember { mutableStateOf(if (root != null) 120.dp else 0.dp) }
            // As in the channel: reading back up the thread slides the reply pill away.
            val scrollAway = rememberScrollAway(rootId)
            val threadList = rememberLazyListState()
            // As in the channel: the thread follows its newest reply, yours always.
            FollowNewest(threadList, rootId, rows.firstOrNull()?.post, repo.myId)
            ScrollAwayEffects(scrollAway, threadList, keep = editing != null || typingHere("${root?.channelId}|$rootId") ||
                ceo.villa.matty.ui.ShareInbox.target == "thread:$rootId")
            ceo.villa.matty.ui.ReportBottomChrome("thread:$rootId", if (root != null && !scrollAway.hidden) dock else null)
            val bar = ceo.villa.matty.ui.BottomChrome.barSpace
            when {
                failed && root == null -> EmptyState(Icons.Outlined.Search, "This thread isn't available", "It may have been deleted.")
                ids == null -> CenteredLoading(Modifier.padding(bottom = dock + bar))
                else -> MessageList(
                    rows = rows, state = threadList, users = users, topPadding = 8.dp, bottomPadding = dock + DockClearance + bar,
                    modifier = Modifier.nestedScroll(scrollAway.connection),
                    hasMore = false, loadingMore = false, onLoadMore = {},
                    onLongPress = { sheetFor = it }, onReact = { reactTo = it }, onShowReactions = { p, e -> whoFor = p to e },
                )
            }
            if (root != null) ComposerDock(inView = !scrollAway.hidden, Modifier.align(Alignment.BottomCenter), riseOnOpen = true, onHeight = { dock = it }) {
                TypingIndicator(root.channelId, rootId)
                Composer(root.channelId, rootId, "Reply to thread", editing, { editing = null }, { mode, insert -> picker = mode to insert },
                    active = nav.top == Route.Thread(rootId),
                    reveal = if (scrollAway.hidden) ({ scrollAway.show() }) else null)
            }
        }
    }
    val ctx = LocalContext.current
    picker?.let { (mode, insert) ->
        ExpressionPicker(mode == PickerMode.Gif, repo.gifEnabled, onEmoji = { insert(":$it:"); ctx.container.settings.noteEmojiUsed(it) }, onGif = { g ->
            val md = GiphyClient.markdownFor(g)
            if (ctx.container.settings.value.sendGifsImmediately && root != null) repo.sendMessage(root.channelId, rootId, md) else insert(md)
            picker = null
        }, onDismiss = { picker = null })
    }
    reactTo?.let { p -> ExpressionPicker(false, false, { repo.toggleReaction(p.id, it); reactTo = null }, null) { reactTo = null } }
    sheetFor?.let { p -> MessageActionsSheet(nav, p, { sheetFor = null }, { reactTo = p }, { editing = p.id to p.message }, inThread = true) }
    whoFor?.let { (p, e) -> ReactionsWhoSheet(p, e) { whoFor = null } }
}

/**
 * A thread's [replies], newest first, as its list shows them. Your own replies still being sent
 * ([sending] says [SendState.Sending][ceo.villa.matty.data.repo.SendState.Sending]) come first,
 * so the list follows them there even when a reply from somebody else carries a later time of the
 * server's. One that failed sorts by its own time, which is never behind what the phone held of
 * the channel when it was written ([ceo.villa.matty.data.repo.pendingCreateAt]): it keeps its
 * place below the replies it came after, and newer ones pass it, as in the channel, once their
 * time is later than its stamp (on a phone whose clock is ahead, that takes as long as it is ahead).
 */
internal fun threadNewestFirst(replies: List<Post>, sending: Map<String, ceo.villa.matty.data.repo.SendState>): List<Post> =
    replies.sortedWith(compareByDescending<Post> { sending[it.id] == ceo.villa.matty.data.repo.SendState.Sending }.thenByDescending { it.createAt })

/** Search across the current team, with jump-to-message. */
@Composable
fun SearchScreen(nav: Navigator, initial: String) {
    val repo = LocalRepo.current
    val c = Matty.colors
    var query by remember { mutableStateOf(initial) }
    var results by remember { mutableStateOf<List<Post>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val users by repo.users.collectAsState()
    fun run() {
        if (query.isBlank()) return
        busy = true
        scope.launch { results = runCatching { repo.search(query.trim()) }.getOrDefault(emptyList()); busy = false }
    }
    LaunchedEffect(Unit) { if (initial.isNotBlank()) run() else runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().background(c.background)) {
        FloatingTopBar("Search", subtitle = repo.teams.value.firstOrNull { it.id == repo.currentTeamId.value }?.displayName, onBack = { nav.pop() })
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(24.dp)).background(c.pill).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, null, tint = c.textSecondary)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Messages, from:user, in:channel, #tags", color = c.textTertiary, style = MaterialTheme.typography.bodyLarge)
                BasicTextField(query, { query = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { run() }), modifier = Modifier.fillMaxWidth().focusRequester(focus))
            }
        }
        val r = results
        when {
            busy -> CenteredLoading()
            r == null -> EmptyState(Icons.Outlined.Search, "Search everything", "Try from:alice, in:off-topic, or a #hashtag.")
            r.isEmpty() -> EmptyState(Icons.Outlined.Search, "No results", "Nothing matched “$query”.")
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                items(r, key = { it.id }) { p -> SearchResult(nav, p, users) }
            }
        }
    }
}

@Composable
fun SearchResult(nav: Navigator, p: Post, users: Map<String, ceo.villa.matty.data.api.User>) {
    val repo = LocalRepo.current
    val c = Matty.colors
    val ch = repo.channels.value[p.channelId]
    val settings = LocalContext.current.container.settings.state.value
    Column(Modifier.fillMaxWidth().clickable {
        if (p.rootId.isNotEmpty() || p.replyCount > 0) nav.openThread(p.rootId.ifEmpty { p.id }) else nav.openChannel(p.channelId)
    }.padding(vertical = 6.dp)) {
        Text(ch?.let { if (it.isDmOrGm) repo.channelTitle(it) else "#" + repo.channelTitle(it) } ?: "", style = MaterialTheme.typography.labelMedium,
            color = c.textSecondary, modifier = Modifier.padding(start = 66.dp))
        MessageRow(MessageRowInfo(p, false, null, false, null, inThread = true), users, settings, {}, {}, { _, _ -> })
        HorizontalDivider(color = c.divider, modifier = Modifier.padding(top = 6.dp, start = 16.dp, end = 16.dp))
    }
    @Suppress("UNUSED_EXPRESSION") Modifier.size(1.dp)
}
