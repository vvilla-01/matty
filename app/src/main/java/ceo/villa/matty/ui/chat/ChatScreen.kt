package ceo.villa.matty.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.AddReaction
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MarkChatUnread
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ceo.villa.matty.container
import ceo.villa.matty.data.api.Post
import ceo.villa.matty.data.emoji.EmojiStore
import ceo.villa.matty.data.giphy.GiphyClient
import ceo.villa.matty.ui.LocalRepo
import ceo.villa.matty.ui.Navigator
import ceo.villa.matty.ui.Route
import ceo.villa.matty.ui.components.ActionRow
import ceo.villa.matty.ui.components.Avatar
import ceo.villa.matty.ui.components.CenteredLoading
import ceo.villa.matty.ui.components.EmptyState
import ceo.villa.matty.ui.components.MattyDialog
import ceo.villa.matty.ui.components.Pill
import ceo.villa.matty.ui.components.PillIconButton
import ceo.villa.matty.ui.picker.ExpressionPicker
import ceo.villa.matty.ui.theme.Matty
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    nav: Navigator, channelId: String?, showMenu: Boolean, onMenu: () -> Unit, onMembers: () -> Unit,
    /** Whether the chat is the panel in view: the message pill rises with it and sinks away with it. */
    inView: Boolean = true,
) {
    val repo = LocalRepo.current
    val c = Matty.colors
    val channels by repo.channels.collectAsState()
    val activeChannel by repo.activeChannelId.collectAsState()
    val ch = channelId?.let { channels[it] }
    var dock by remember { mutableStateOf(DockGuess) }
    // Reading back through the conversation slides the message pill away (see ScrollAway).
    val scrollAway = rememberScrollAway(channelId)
    // The message pill, for the app-wide player bar to float above (while it's in view).
    ceo.villa.matty.ui.ReportBottomChrome("home:chat", if (ch != null && inView && !scrollAway.hidden) dock else null)
    if (ch == null) {
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            if (showMenu) HeaderDock(inView) { Pill(Modifier.padding(12.dp)) { ChannelsButton(null, onMenu) } }
            EmptyState(Icons.Outlined.Tag, "Pick a conversation", "Swipe right or tap the menu to see your channels and DMs.", Modifier.align(Alignment.Center))
        }
        return
    }
    // The member panel's list, read ahead once the conversation has settled on screen (not while
    // hopping through channels), so the panel doesn't open blank even the first time.
    LaunchedEffect(ch.id) {
        if (!ch.isDirect) { kotlinx.coroutines.delay(1200); repo.refreshChannelMembers(ch.id, maxAgeMs = 5 * 60_000L) }
    }
    val timelines by repo.timelines.collectAsState()
    val posts by repo.posts.collectAsState()
    val users by repo.users.collectAsState()
    val sendStates by repo.sendStates.collectAsState()
    val timeline = timelines[ch.id]
    val rows = remember(timeline, posts, sendStates) {
        val list = timeline?.order.orEmpty().mapNotNull { posts[it] }.filter { it.deleteAt == 0L }
        buildRows(list, timeline?.lastViewedAt ?: 0, repo.myId, sendStates, inThread = false)
    }
    val title = repo.channelTitle(ch)
    var picker by remember { mutableStateOf<Pair<PickerMode, (String) -> Unit>?>(null) }
    var reactTo by remember { mutableStateOf<Post?>(null) }
    var sheetFor by remember { mutableStateOf<Post?>(null) }
    var whoFor by remember { mutableStateOf<Pair<Post, String>?>(null) }
    var editing by remember(ch.id) { mutableStateOf<Pair<String, String>?>(null) }
    // Each conversation has its own place in its own list: opened at its newest message, never at
    // wherever the last conversation happened to be scrolled to.
    val listState = remember(ch.id) { androidx.compose.foundation.lazy.LazyListState() }
    // The pill stays while you're typing (the keyboard or the emoji popup is up), editing, or a
    // quote is on its way into it; and it's back whenever the chat comes into view again.
    ScrollAwayEffects(scrollAway, listState, keep = !inView || editing != null || typingHere("${ch.id}|") ||
        ceo.villa.matty.ui.ShareInbox.target == ch.id)
    // Follow the conversation: stick to the newest message when already near it, or when it's ours.
    // "Near it" is judged when the list comes to rest, not mid-glide: several messages landing at
    // once each start the glide again, and measured then it could look scrolled away and be left
    // stranded a few messages up.
    var following by remember(ch.id) { mutableStateOf(true) }
    // Per conversation, like the flag it sets and the list it watches.
    LaunchedEffect(listState, ch.id) {
        androidx.compose.runtime.snapshotFlow { listState.isScrollInProgress }.collect { moving ->
            if (!moving) following = listState.firstVisibleItemIndex <= 2
        }
    }
    // The reader's own drag or flick ends "following" at once (the list's own glide to a new
    // message doesn't): a message landing mid-flick must not cancel the flick and pull the list
    // back down. Where it comes to rest decides again.
    LaunchedEffect(listState, ch.id) {
        listState.interactionSource.interactions.collect {
            if (it is androidx.compose.foundation.interaction.DragInteraction.Start) following = false
        }
    }
    val newest = rows.firstOrNull()?.post
    LaunchedEffect(newest?.id) {
        if (newest != null && (following || listState.firstVisibleItemIndex <= 2 || newest.userId == repo.myId)) listState.animateScrollToItem(0)
    }
    val headerPad = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() } + 68.dp
    // The message pill floats over the conversation; the list keeps clear of it at rest and
    // scrolls on behind it.
    // Room for the app-wide player bar too, when it shows, so the newest message is never under it.
    val bar = if (ceo.villa.matty.ui.BottomChrome.barInStartColumn) 0.dp else ceo.villa.matty.ui.BottomChrome.barSpace

    Box(Modifier.fillMaxSize().imePadding()) {
        when {
            timeline == null || (!timeline.loaded && timeline.loading) -> CenteredLoading(Modifier.padding(top = headerPad, bottom = dock + bar))
            rows.isEmpty() -> Column(Modifier.fillMaxSize().padding(top = headerPad, bottom = dock + bar), verticalArrangement = Arrangement.Bottom) {
                ChannelIntro(nav, ch.id)
            }
            else -> MessageList(
                rows = rows, state = listState, users = users, topPadding = headerPad, bottomPadding = dock + DockClearance + bar,
                modifier = Modifier.nestedScroll(scrollAway.connection),
                hasMore = timeline.hasMoreOlder, loadingMore = timeline.loading,
                onLoadMore = { repo.launchSafe { repo.loadOlder(ch.id) } },
                onLongPress = { sheetFor = it }, onReact = { reactTo = it },
                onShowReactions = { p, e -> whoFor = p to e },
                footer = { ChannelIntro(nav, ch.id) },
            )
        }
        ComposerDock(inView && !scrollAway.hidden, Modifier.align(Alignment.BottomCenter), onHeight = { dock = it }) {
            TypingIndicator(ch.id, "")
            Composer(
                channelId = ch.id, rootId = "",
                placeholder = when { ch.isDirect -> "Message @$title"; ch.isGroup -> "Message $title"; else -> "Message #$title" },
                editing = editing, onCancelEdit = { editing = null },
                onOpenPicker = { mode, insert -> picker = mode to insert },
                active = inView && nav.stack.isEmpty() && activeChannel == ch.id,
                onScreen = inView && nav.stack.isEmpty(),
                reveal = if (scrollAway.hidden) ({ scrollAway.show() }) else null,
            )
        }
        // After the dock, so the fade's top edge doesn't shade it.
        // It follows the pill down when the pill slides away, resting just above the navigation bar.
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val drop by androidx.compose.animation.core.animateDpAsState(
            if (scrollAway.hidden) (dock - navBottom).coerceAtLeast(0.dp) else 0.dp,
            androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow), label = "jumpDrop",
        )
        JumpToPresent(listState, inView, dock, Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = dock + 8.dp + bar)
            .offset { androidx.compose.ui.unit.IntOffset(0, drop.roundToPx()) })
        HeaderDock(inView) { ChatHeader(nav, ch.id, showMenu, onMenu, onMembers) }
    }

    picker?.let { (mode, insert) ->
        val ctx = LocalContext.current
        ExpressionPicker(
            startOnGif = mode == PickerMode.Gif, gifsEnabled = repo.gifEnabled,
            onEmoji = { name -> insert(":$name:"); ctx.container.settings.noteEmojiUsed(name) },
            onGif = { g ->
                val md = GiphyClient.markdownFor(g)
                if (ctx.container.settings.value.sendGifsImmediately) repo.sendMessage(ch.id, "", md) else insert(md)
                picker = null
            },
            onDismiss = { picker = null },
        )
    }
    reactTo?.let { p ->
        ExpressionPicker(startOnGif = false, gifsEnabled = false, onEmoji = { name -> repo.toggleReaction(p.id, name); reactTo = null }, onGif = null, onDismiss = { reactTo = null })
    }
    sheetFor?.let { p ->
        MessageActionsSheet(nav, p, onDismiss = { sheetFor = null }, onMoreReactions = { reactTo = p }, onEdit = { editing = p.id to p.message }, inThread = false)
    }
    whoFor?.let { (p, emoji) -> ReactionsWhoSheet(p, emoji) { whoFor = null } }
}

@Composable
fun MessageList(
    rows: List<MessageRowInfo>,
    state: LazyListState,
    users: Map<String, ceo.villa.matty.data.api.User>,
    topPadding: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp = 8.dp,
    hasMore: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    onLongPress: (Post) -> Unit,
    onReact: (Post) -> Unit,
    onShowReactions: (Post, String) -> Unit,
    footer: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val settings by LocalContext.current.container.settings.state.collectAsState()
    // Older messages are fetched well before the top is reached, so scrolling up rarely meets a wait.
    val nearEnd by remember(state) { derivedStateOf { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= state.layoutInfo.totalItemsCount - 24 } }
    LaunchedEffect(nearEnd, rows.size) { if (nearEnd && hasMore && !loadingMore) onLoadMore() }
    LazyColumn(state = state, reverseLayout = true, contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding), modifier = modifier.fillMaxSize()) {
        items(rows, key = { it.post.id }, contentType = { if (it.grouped) 1 else 0 }) { info ->
            MessageRow(info, users, settings, onLongPress, onReact, onShowReactions)
        }
        item(key = "footer") {
            if (loadingMore) CenteredLoading(size = 32.dp) else if (!hasMore) footer()
        }
    }
}

/**
 * Reading back through a conversation: the message pill slides away while you scroll up through
 * older messages, and comes back as soon as you scroll down, or reach the newest message. Only
 * your own drags and flings count (the list following a new message doesn't), each way after
 * [threshold] px of travel, so a wobble doesn't flicker it.
 */
@androidx.compose.runtime.Stable
internal class ScrollAway(private val threshold: Float) {
    var hidden by mutableStateOf(false)
        private set
    /** While set, the pill stays (you're typing, editing…); see [ScrollAwayEffects]. */
    internal var keep = false
    private var travel = 0f

    fun show() { hidden = false; travel = 0f }

    val connection = object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
        override fun onPostScroll(
            consumed: androidx.compose.ui.geometry.Offset,
            available: androidx.compose.ui.geometry.Offset,
            source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
        ): androidx.compose.ui.geometry.Offset {
            val dy = consumed.y
            if (dy == 0f) return androidx.compose.ui.geometry.Offset.Zero
            if (keep) { travel = 0f; return androidx.compose.ui.geometry.Offset.Zero }
            // A change of direction starts the count again.
            if (travel != 0f && (travel > 0f) != (dy > 0f)) travel = 0f
            travel += dy
            // In screen terms, whichever way the list is laid out: the finger moving down brings
            // older messages into view (away); moving up heads back to the newest (back again).
            if (travel > threshold) hidden = true else if (-travel > threshold) hidden = false
            return androidx.compose.ui.geometry.Offset.Zero
        }
    }
}

/** A fresh one per conversation: a channel opens with its message pill in view. */
@Composable
internal fun rememberScrollAway(key: Any?): ScrollAway {
    val px = with(LocalDensity.current) { 32.dp.toPx() }
    return remember(key) { ScrollAway(px) }
}

/** The pill comes back at the newest message, and stays put while [keep] holds. */
@Composable
internal fun ScrollAwayEffects(away: ScrollAway, state: LazyListState, keep: Boolean) {
    androidx.compose.runtime.SideEffect { away.keep = keep }
    LaunchedEffect(away, keep) { if (keep) away.show() }
    val atNewest by remember(state) { derivedStateOf { state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0 } }
    LaunchedEffect(away, atNewest) { if (atNewest) away.show() }
}

/**
 * Typing in (or about to type into) the box keyed [draftKey]: the keyboard or the emoji popup is
 * up, or a quote or mention is on its way into it. (A share on its way in is the callers' to add.)
 */
@Composable
internal fun typingHere(draftKey: String): Boolean =
    WindowInsets.isImeVisible || ceo.villa.matty.ui.BottomChrome.popups.isNotEmpty() || Drafts.added?.key == draftKey

/** Roughly the pill and its margins, until the real height is measured on the first frame. */
private val DockGuess = 120.dp

/**
 * The message box's place on screen: MarkOut's bottom pill floating over the conversation with
 * nothing behind it. It rises into view with the chat and sinks away when the channel list
 * slides over, the same way (and with the same spring) as the tab pill does the other way
 * round. [riseOnOpen] has it rise when it first appears too, as on a thread opening.
 */
@Composable
fun ComposerDock(
    inView: Boolean,
    modifier: Modifier = Modifier,
    riseOnOpen: Boolean = false,
    onHeight: (androidx.compose.ui.unit.Dp) -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val rise = remember { androidx.compose.animation.core.Animatable(if (inView && !riseOnOpen) 1f else 0f) }
    LaunchedEffect(inView) {
        rise.animateTo(if (inView) 1f else 0f, androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow))
    }
    Column(
        modifier.fillMaxWidth()
            .onSizeChanged { onHeight(with(density) { it.height.toDp() }) }
            .graphicsLayer { translationY = (1f - rise.value) * size.height; alpha = rise.value }
            .navigationBarsPadding(),
        content = content,
    )
}

/**
 * The header pills' half of [ComposerDock]: as the message pill sinks off the bottom, the menu,
 * title and tool pills rise off the top, on the same spring, and come back down with the chat.
 */
@Composable
private fun HeaderDock(inView: Boolean, content: @Composable () -> Unit) {
    val rise = remember { androidx.compose.animation.core.Animatable(if (inView) 1f else 0f) }
    LaunchedEffect(inView) {
        rise.animateTo(if (inView) 1f else 0f, androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow))
    }
    Box(Modifier.fillMaxWidth().graphicsLayer { translationY = -(1f - rise.value) * size.height; alpha = rise.value }) { content() }
}

/** The newest message rests this far above the dock. */
internal val DockClearance = 8.dp

/**
 * The way back to the newest message. It sinks and fades with the message pill when the channel
 * list or the member list slides in (the same spring, as far as the pill goes: [dock]), and can't
 * be pressed while it's away.
 */
@Composable
private fun JumpToPresent(state: LazyListState, inView: Boolean, dock: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val show by remember(state) { derivedStateOf { state.firstVisibleItemIndex > 8 } }
    val rise = remember { androidx.compose.animation.core.Animatable(if (inView) 1f else 0f) }
    LaunchedEffect(inView) {
        rise.animateTo(if (inView) 1f else 0f, androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow))
    }
    val density = LocalDensity.current
    AnimatedVisibility(
        show,
        modifier = modifier.graphicsLayer { translationY = (1f - rise.value) * with(density) { dock.toPx() }; alpha = rise.value },
        enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut(),
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(20.dp)).background(Matty.colors.selected)
                .clickable(enabled = inView) { scope.launch { state.glideToPresent() } }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Jump to present", color = Matty.colors.title, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Outlined.KeyboardArrowDown, null, tint = Matty.colors.title, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Back to the newest message in one movement that ends at rest. [LazyListState.animateScrollToItem]
 * from far up runs towards an estimate and then starts again once the item comes into view, which
 * shows as a rush, a stall on a few recent messages, then the rest. Instead: straight to the newest,
 * a known distance back up with no frame drawn in between, and a glide down exactly that far.
 */
internal suspend fun LazyListState.glideToPresent() =
    glideToPresent(layoutInfo.viewportSize.height * 0.8f) { scrollToItem(0) }

/**
 * [glideToPresent] for any scroller, where a positive [ScrollableState.scrollBy] goes towards
 * older messages (for a [LazyListState] that's towards higher indexes, reverseLayout or not: it
 * only turns the gesture round). [snap] goes straight to the newest message, at rest. The glide is
 * an ordinary scroll, so the reader's own touch takes over from it, and a message that lands
 * meanwhile can start its own; only a glide that finishes snaps again, to put the list exactly on
 * the newest message rather than a fraction of a pixel off it.
 */
internal suspend fun ScrollableState.glideToPresent(distance: Float, snap: suspend () -> Unit) {
    snap()
    var back = 0f
    scroll { back = scrollBy(distance) }
    if (back == 0f) return
    animateScrollBy(-back, tween(300, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
    snap()
}

/** MarkOut chrome: no app bar, just pills floating over the conversation. */
@Composable
private fun ChatHeader(nav: Navigator, channelId: String, showMenu: Boolean, onMenu: () -> Unit, onMembers: () -> Unit) {
    val repo = LocalRepo.current
    val c = Matty.colors
    val channels by repo.channels.collectAsState()
    val users by repo.users.collectAsState()
    val statuses by repo.statuses.collectAsState()
    val plugins by LocalContext.current.container.plugins.active.collectAsState()
    val ch = channels[channelId] ?: return
    val other = ch.otherUserId(repo.myId)
    val otherUser = other?.let { users[it] }
    // A DM's line is the one the member list and the DM list give that person (their status, the
    // game they're in, live), and their presence in words when there's none of that to say.
    val facts = ceo.villa.matty.plugins.personOf(if (ch.isDirect) other else null)
    val live = facts.live
    val activity = facts.activity
    val custom = if (ch.isDirect) ceo.villa.matty.ui.home.ownStatus(otherUser, facts) else null
    val statusLine = ch.isDirect && ceo.villa.matty.ui.home.hasStatusLine(custom, live, activity)
    val subtitle = when {
        ch.isDirect -> ceo.villa.matty.ui.components.statusLabel(statuses[other] ?: "offline")
        else -> ch.purpose.ifBlank { ch.header }.lineSequence().firstOrNull()?.ifBlank { null }
    }
    Row(
        // Just the pills, floating over the conversation: no band or fade behind them.
        Modifier.fillMaxWidth()
            .statusBarsPadding().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showMenu) {
            Pill { ChannelsButton(channelId, onMenu) }
            Spacer(Modifier.width(8.dp))
        }
        Row(
            Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).background(c.pill)
                .clickable { nav.push(Route.ChannelInfo(channelId)) }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                ch.isDirect -> Avatar(other?.let { repo.avatarUrl(it) }, repo.channelTitle(ch), 28.dp, status = statuses[other] ?: "offline", ringColor = c.pill,
                    live = live, voice = facts.voice)
                ch.isGroup -> Icon(Icons.Outlined.Groups, null, tint = c.textSecondary, modifier = Modifier.size(22.dp))
                ch.isPrivate -> Icon(Icons.Outlined.Lock, null, tint = c.textSecondary, modifier = Modifier.size(20.dp))
                else -> Icon(Icons.Outlined.Tag, null, tint = c.textSecondary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(repo.channelTitle(ch), style = MaterialTheme.typography.titleSmall, color = c.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // A channel's purpose is Markdown (**bold**, links…), as on its info page; a DM's line is plain.
                if (statusLine) ceo.villa.matty.ui.home.StatusLine(custom, live, activity, c.textSecondary, MaterialTheme.typography.labelSmall)
                else if (!subtitle.isNullOrBlank()) {
                    if (ch.isDirect) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else InlineMarkdown(subtitle, MaterialTheme.typography.labelSmall, c.textSecondary, maxLines = 1, interactive = false)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Pill {
            plugins.forEach { p ->
                val panel = p.panel ?: return@forEach
                // The Matty plugin's: how many people are live right now.
                val count = p.panelBadge()
                val title = p.panelTitle()
                PillIconButton(panel.icon, if (count > 0) "$title, $count live" else title, badge = count) { nav.push(Route.Plugin(p.id)) }
            }
            if (repo.collapsedThreads) PillIconButton(Icons.Outlined.Forum, "Threads") { nav.tab = ceo.villa.matty.ui.HomeTab.Activity }
            PillIconButton(Icons.Outlined.Search, "Search") { nav.push(Route.Search()) }
            if (!ch.isDirect) PillIconButton(Icons.Outlined.Groups, "Members", onClick = onMembers)
        }
    }
}

@Composable
private fun ChannelIntro(nav: Navigator, channelId: String) {
    val repo = LocalRepo.current
    val c = Matty.colors
    val channels by repo.channels.collectAsState()
    val users by repo.users.collectAsState()
    val ch = channels[channelId] ?: return
    // From the state read here, so a DM's name fills in once the people have loaded (on a cold
    // start the conversation can draw first, which left "Direct message" in place of the name).
    val title = remember(ch, users) { repo.channelTitle(ch) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp)) {
        Box(Modifier.size(64.dp).background(c.pill, CircleShape), contentAlignment = Alignment.Center) {
            if (ch.isDirect) Avatar(ch.otherUserId(repo.myId)?.let { repo.avatarUrl(it) }, title, 64.dp)
            else Icon(if (ch.isPrivate) Icons.Outlined.Lock else Icons.Outlined.Tag, null, tint = c.icon, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(if (ch.isDmOrGm) title else "Welcome to #${title}", style = MaterialTheme.typography.headlineSmall, color = c.title)
        Spacer(Modifier.height(4.dp))
        // Only the channel's own purpose is Markdown; Matty's sentences (with names in them) stay plain.
        val purpose = ch.purpose.takeIf { !ch.isDmOrGm && it.isNotBlank() }
        if (purpose != null) InlineMarkdown(purpose, MaterialTheme.typography.bodyMedium, c.textSecondary)
        else Text(
            when {
                ch.isDirect -> "This is the beginning of your direct message history with ${title}."
                ch.isGroup -> "This is the beginning of this group conversation."
                else -> "This is the start of the #${title} channel."
            },
            style = MaterialTheme.typography.bodyMedium, color = c.textSecondary,
        )
    }
    @Suppress("UNUSED_EXPRESSION") nav
}

@Composable
fun TypingIndicator(channelId: String, parentId: String) {
    val repo = LocalRepo.current
    val c = Matty.colors
    val settings by LocalContext.current.container.settings.state.collectAsState()
    val typing by repo.typing.collectAsState()
    val ids = typing["$channelId|$parentId"]?.keys.orEmpty().toList()
    AnimatedVisibility(ids.isNotEmpty() && settings.showTyping) {
        val names = ids.map { repo.nameOf(it) }
        val text = when (names.size) {
            1 -> "${names[0]} is typing"
            2 -> "${names[0]} and ${names[1]} are typing"
            else -> "Several people are typing"
        }
        val t = rememberInfiniteTransition(label = "dots")
        // Floats over the conversation just above the message pill, so it has a pill of its own.
        Row(
            Modifier.padding(start = 16.dp, bottom = 2.dp).clip(RoundedCornerShape(14.dp)).background(c.pill)
                .pointerInput(Unit) {}.padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) { i ->
                val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(500, delayMillis = i * 150), RepeatMode.Reverse), label = "dot$i")
                Box(Modifier.padding(end = 3.dp).size(6.dp).background(c.textSecondary.copy(alpha = a), CircleShape))
            }
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, color = c.textSecondary, fontWeight = FontWeight.Medium)
        }
    }
}

// ---- long-press actions -------------------------------------------------------------------

@Composable
fun MessageActionsSheet(nav: Navigator, post: Post, onDismiss: () -> Unit, onMoreReactions: () -> Unit, onEdit: () -> Unit, inThread: Boolean) {
    val repo = LocalRepo.current
    val c = Matty.colors
    val settings by LocalContext.current.container.settings.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val mine = post.userId == repo.myId
    val pending = post.id.contains(':')
    var confirmDelete by remember { mutableStateOf(false) }
    fun close() = onDismiss()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.card, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            if (!pending) {
                SheetLazyRow {
                    items(settings.recentEmojis.take(7)) { name ->
                        Box(Modifier.size(48.dp).clip(CircleShape).background(c.pill).clickable { repo.toggleReaction(post.id, name); close() }, contentAlignment = Alignment.Center) {
                            EmojiGlyph(name, 26.dp)
                        }
                    }
                    item {
                        Box(Modifier.size(48.dp).clip(CircleShape).background(c.pill).clickable { close(); onMoreReactions() }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.AddReaction, "More reactions", tint = c.icon)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Column(Modifier.padding(horizontal = 12.dp)) {
                if (!pending && !inThread) ActionRow(Icons.AutoMirrored.Outlined.Reply, "Reply") { close(); nav.openThread(post.rootId.ifEmpty { post.id }) }
                if (post.message.isNotBlank()) ActionRow(Icons.Outlined.FormatQuote, "Quote") {
                    close()
                    val quoted = post.message.lines().joinToString("\n") { "> $it" }
                    // Onto whatever is being written there, live in the box (cursor after it).
                    Drafts.append("${post.channelId}|${if (inThread) post.rootId.ifEmpty { post.id } else ""}", "$quoted\n\n", block = true)
                    nav.selectChannel(post.channelId)
                }
                if (post.message.isNotBlank()) ActionRow(Icons.Outlined.ContentCopy, "Copy text") { clipboard.setText(AnnotatedString(post.message)); close() }
                if (!pending) ActionRow(Icons.Outlined.Link, "Copy link") {
                    val team = repo.channels.value[post.channelId]?.teamId?.let { id -> repo.teams.value.firstOrNull { it.id == id } }
                        ?: repo.teams.value.firstOrNull { it.id == repo.currentTeamId.value } ?: repo.teams.value.firstOrNull()
                    clipboard.setText(AnnotatedString("${repo.client.baseUrl}/${team?.name ?: "_redirect"}/pl/${post.id}"))
                    close()
                }
                if (!pending) ActionRow(if (repo.isFlagged(post.id)) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, if (repo.isFlagged(post.id)) "Remove from saved" else "Save") {
                    val saved = repo.isFlagged(post.id)
                    repo.launchAction(if (saved) "Couldn't remove it from saved" else "Couldn't save it") { repo.toggleFlag(post.id) }; close()
                }
                if (!pending) ActionRow(Icons.Outlined.PushPin, if (post.isPinned) "Unpin from channel" else "Pin to channel") {
                    repo.launchAction(if (post.isPinned) "Couldn't unpin it" else "Couldn't pin it") { repo.togglePin(post) }; close()
                }
                if (!pending && !mine) ActionRow(Icons.Outlined.MarkChatUnread, "Mark unread") {
                    repo.launchAction("Couldn't mark it unread") { repo.markUnread(post) }; close()
                }
                if (mine && !pending && !post.isSystem) {
                    // Past the server's edit window the row stays, to say why, rather than vanish or fail.
                    if (repo.editWindowClosed(post)) ActionRow(
                        Icons.Outlined.Edit, "Edit", tint = c.textTertiary, enabled = false,
                        hint = "This server only allows edits for ${ceo.villa.matty.data.repo.spanWords(repo.postEditLimitSeconds ?: 0)} after posting",
                    ) {} else ActionRow(Icons.Outlined.Edit, "Edit") { close(); onEdit() }
                }
                if (mine && !pending) ActionRow(Icons.Outlined.Delete, "Delete", tint = c.destructive) { confirmDelete = true }
            }
        }
    }
    if (confirmDelete) {
        MattyDialog("Delete Message", onDismiss = { confirmDelete = false }, icon = Icons.Outlined.Delete, actions = {
            androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            androidx.compose.material3.TextButton(onClick = {
                confirmDelete = false
                // On the session's scope: closing the sheet must not cancel the delete half-way.
                repo.launchAction("Couldn't delete the message") { repo.deletePost(post.id) }
                close()
            }) { Text("Delete", color = c.destructive) }
        }) {
            Text("This can't be undone.", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
        }
    }
}

@Composable
fun ReactionsWhoSheet(post: Post, initial: String, onDismiss: () -> Unit) {
    val repo = LocalRepo.current
    val c = Matty.colors
    val posts by repo.posts.collectAsState()
    val users by repo.users.collectAsState()
    val reactions = (posts[post.id] ?: post).metadata.reactions.orEmpty()
    val grouped = reactions.groupBy { it.emojiName }
    var selected by remember { mutableStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.card) {
        SheetLazyRow {
            items(grouped.keys.toList()) { e ->
                Row(
                    Modifier.clip(RoundedCornerShape(18.dp)).background(if (e == selected) c.selected else c.pill).clickable { selected = e }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EmojiGlyph(e, 20.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(grouped[e]?.size.toString(), color = c.title, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val list = androidx.compose.foundation.lazy.rememberLazyListState()
        val sheetScroll = ceo.villa.matty.ui.components.rememberSheetScrolling { list.canScrollForward || list.canScrollBackward }
        LazyColumn(
            Modifier.navigationBarsPadding().padding(bottom = 16.dp).nestedScroll(sheetScroll.connection),
            state = list, overscrollEffect = sheetScroll.overscroll,
        ) {
            items(grouped[selected].orEmpty()) { r ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(repo.avatarUrl(r.userId), repo.nameOf(users[r.userId]), 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(repo.nameOf(users[r.userId]), color = c.title, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** A sideways row inside a bottom sheet, bouncing at its ends straight away like the sheet's lists. */
@Composable
private fun SheetLazyRow(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val row = androidx.compose.foundation.lazy.rememberLazyListState()
    val sheetScroll = ceo.villa.matty.ui.components.rememberSheetScrolling(horizontal = true) { row.canScrollForward || row.canScrollBackward }
    LazyRow(
        Modifier.nestedScroll(sheetScroll.connection), state = row, overscrollEffect = sheetScroll.overscroll,
        contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content,
    )
}

/**
 * The chat's menu button, with a count of the conversations waiting elsewhere (unread DMs and
 * groups, and channels where you were mentioned) so they're visible without leaving the chat.
 */
@Composable
internal fun ChannelsButton(currentId: String?, onMenu: () -> Unit) {
    val repo = LocalRepo.current
    val channels by repo.channels.collectAsState()
    val members by repo.members.collectAsState()
    val waiting = remember(channels, members, currentId) {
        channels.values.count { ch ->
            ch.id != currentId && repo.unreadOf(ch, members[ch.id]).let { u -> !u.muted && (if (ch.isDmOrGm) u.unread else u.mentions > 0) }
        }
    }
    PillIconButton(Icons.Outlined.Menu, if (waiting > 0) "Channels, $waiting waiting" else "Channels", badge = waiting, onClick = onMenu)
}
