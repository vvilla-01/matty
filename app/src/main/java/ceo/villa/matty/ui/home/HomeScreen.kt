package ceo.villa.matty.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import ceo.villa.matty.data.api.Channel
import ceo.villa.matty.ui.HomeTab
import ceo.villa.matty.ui.RailPage
import ceo.villa.matty.ui.chat.ChannelsButton
import ceo.villa.matty.ui.components.Pill
import ceo.villa.matty.ui.LocalRepo
import ceo.villa.matty.ui.Navigator
import ceo.villa.matty.ui.activity.ActivityScreen
import ceo.villa.matty.ui.chat.ChatScreen
import ceo.villa.matty.ui.components.Avatar
import ceo.villa.matty.ui.components.CountBadge
import ceo.villa.matty.ui.profile.YouScreen
import ceo.villa.matty.ui.theme.Matty
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(nav: Navigator) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    val channels by repo.channels.collectAsState()
    BoxWithConstraints(Modifier.fillMaxSize().background(Matty.colors.background)) {
        val wide = maxWidth >= 840.dp
        val page = nav.railPages.current
        val panels = rememberPanelsState(if (nav.channelId == null && page == null) PanelValue.Start else PanelValue.Center)
        // Members open beside the chat only when all three columns fit (channels 360 + members
        // 300 + a readable chat); on a phone in landscape the members button still opens them.
        val roomForMembers = maxWidth >= 1140.dp
        var showMembers by remember(roomForMembers) { mutableStateOf(roomForMembers) }
        LaunchedEffect(nav.showChatSignal) { if (nav.showChatSignal > 0) panels.open(PanelValue.Center) }

        // The channel counts as "being read" only while the chat is actually on screen.
        val chatVisible = chatOnScreen(nav.tab, wide, panels.target, page)
        LaunchedEffect(chatVisible, nav.channelId) {
            val id = nav.channelId
            if (chatVisible && id != null && channels.containsKey(id)) repo.openChannel(id) else repo.leaveActiveChannel()
        }
        val current = nav.channelId?.let { channels[it] }
        val start: @Composable () -> Unit = {
            Row(Modifier.fillMaxSize()) {
                ServerRail(nav)
                ChannelSidebar(nav, Modifier.weight(1f))
            }
        }
        Crossfade(nav.tab, label = "tab") { tab ->
            when (tab) {
                // Discover or Plugins, pressed on the rail, take the chat's place in the main area (and
                // the members' beside it): the rail and the channel list stay as they were.
                HomeTab.Home -> if (wide) {
                    SideBySidePanels(
                        start = start,
                        center = {
                            Crossfade(page, label = "main") { shown ->
                                if (shown != null) MainPage(nav, shown, onMenu = null)
                                else ChatScreen(nav, nav.channelId, showMenu = false, onMenu = {}, onMembers = { showMembers = !showMembers })
                            }
                        },
                        end = { MemberList(nav.channelId) },
                        showEnd = showMembers && membersBesideChat(current, page),
                    )
                } else {
                    OverlappingPanels(
                        state = panels,
                        endEnabled = membersBesideChat(current, page),
                        start = start,
                        center = {
                            val onMenu = { scope.launch { panels.open(PanelValue.Start) }; Unit }
                            Crossfade(page, label = "main") { shown ->
                                if (shown != null) MainPage(nav, shown, onMenu)
                                else ChatScreen(nav, nav.channelId, showMenu = true,
                                    onMenu = onMenu,
                                    onMembers = { scope.launch { panels.open(PanelValue.End) } },
                                    // The message pill slides away whenever the chat isn't centre stage: under the
                                    // channel list (where the tab pill takes its place) and beside the members.
                                    inView = panels.target == PanelValue.Center)
                            }
                        },
                        end = { MemberList(nav.channelId) },
                    )
                }
                HomeTab.Activity -> ActivityScreen(nav)
                HomeTab.You -> YouScreen(nav)
            }
        }
        val showBar = nav.tab != HomeTab.Home || wide || panels.target == PanelValue.Start
        // The tab pill (60dp, 18dp off the navigation bar), for the app-wide player bar to sit
        // above; beside a wide chat it lives in the channel column, and the bar with it.
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        // One column for the pill and the bar above it, so the two can't drift apart again.
        val pillStart = RailWidth
        val pillColumn = WideStartWidth - RailWidth - ChannelPanelGap
        ceo.villa.matty.ui.ReportBottomChrome(
            "home:tabs", if (showBar) navBottom + 78.dp else null,
            if (wide) pillColumn else null, if (wide) pillStart else 0.dp,
        )
        // Side by side, the bar lives under the channel column on every tab: centred on the
        // whole window it would sit on the chat and cover its message box. It is centred on the
        // channel list's panel, clear of the rail, as on a phone (where the panel's middle is the
        // screen's): centred on the whole column, rail included, it poked out under the rail.
        Box(
            if (wide) Modifier.align(Alignment.BottomStart).padding(start = pillStart).width(pillColumn)
            else Modifier.align(Alignment.BottomCenter),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AnimatedVisibility(
                showBar,
                enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it },
            ) {
                BottomTabs(nav, showList = { fromAnotherTab ->
                    // In the same frame as the tab change, so the old chat never counts as on
                    // screen (and read) on the way. From another tab there's nothing to watch
                    // slide: jump straight to the list.
                    scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        if (fromAnotherTab) panels.snap(PanelValue.Start) else panels.open(PanelValue.Start)
                    }
                })
            }
        }

        // These register late (inside BoxWithConstraints), so they must stand down while a
        // full-screen route is open or they would steal its back press.
        val noRoute = nav.stack.isEmpty()
        BackHandler(enabled = noRoute && nav.tab != HomeTab.Home) { nav.tab = HomeTab.Home }
        val back = homeBack(wide, panels.target, page)
        BackHandler(enabled = noRoute && nav.tab == HomeTab.Home && back != null) {
            when (back) {
                HomeBack.ToCenter -> scope.launch { panels.open(PanelValue.Center) }
                HomeBack.ToStart -> scope.launch { panels.open(PanelValue.Start) }
                HomeBack.ClosePage -> nav.railPages.back()
                null -> {}
            }
        }
    }
}

/** Whether the open conversation is on screen, and so being read: not under the side bar, nor behind Discover or Plugins. */
internal fun chatOnScreen(tab: HomeTab, wide: Boolean, panel: PanelValue, page: RailPage?): Boolean =
    tab == HomeTab.Home && (wide || panel != PanelValue.Start) && page == null

/** Whether [channel]'s members can be beside the chat (or slid to, on a phone): a channel's, with its chat in the main area. */
internal fun membersBesideChat(channel: Channel?, page: RailPage?): Boolean = channel != null && !channel.isDirect && page == null

enum class HomeBack { ToCenter, ToStart, ClosePage }

/**
 * What Back does on the home screen. On a phone it slides back a panel at a time, from a rail
 * page as from a chat: to the side bar, its button still lit; there it closes the page, and the
 * team (or direct messages) is lit again. Side by side the page simply closes. Null: Back is not
 * the home screen's.
 */
internal fun homeBack(wide: Boolean, panel: PanelValue, page: RailPage?): HomeBack? = when {
    !wide && panel == PanelValue.End -> HomeBack.ToCenter
    !wide && panel == PanelValue.Center -> HomeBack.ToStart
    page != null -> HomeBack.ClosePage
    else -> null
}

/** Discover or Plugins in the main area. [onMenu]: on a phone, the header's way back to the side bar. */
@Composable
private fun MainPage(nav: Navigator, page: RailPage, onMenu: (() -> Unit)?) {
    when (page) {
        RailPage.Discover -> BrowseChannelsScreen(nav, inMain = true, onMenu = onMenu)
        RailPage.Plugins -> ceo.villa.matty.ui.settings.PluginsScreen(nav, inMain = true, onMenu = onMenu)
    }
}

/** The widest Discover and Plugins get: on a large tablet they are centred at this width, so lines stay readable. */
val PageMaxWidth = 720.dp

/**
 * A page's list (Discover, Plugins), centred at [PageMaxWidth] when there is more room, and kept
 * clear at the bottom of the player bar when that floats over it (in the main area on a phone).
 */
@Composable
fun PageColumn(modifier: Modifier, content: LazyListScope.() -> Unit) {
    BoxWithConstraints(modifier) {
        val side = ((maxWidth - PageMaxWidth) / 2).coerceAtLeast(0.dp)
        val bar = if (ceo.villa.matty.ui.BottomChrome.barInStartColumn) 0.dp else ceo.villa.matty.ui.BottomChrome.barSpace
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = side, end = side, bottom = 48.dp + bar), content = content)
    }
}

/** A page's header button back to the side bar on a phone: the chat's own menu button. */
@Composable
fun MainPageMenu(onMenu: () -> Unit) {
    Pill { ChannelsButton(null, onMenu) }
    Spacer(Modifier.width(10.dp))
}

/** MarkOut's floating tool pill, holding Discord's four tabs. */
@Composable
private fun BottomTabs(nav: Navigator, showList: (fromAnotherTab: Boolean) -> Unit) {
    val repo = LocalRepo.current
    val c = Matty.colors
    val me by repo.me.collectAsState()
    val statuses by repo.statuses.collectAsState()
    val channels by repo.channels.collectAsState()
    val members by repo.members.collectAsState()
    val threads by repo.threads.collectAsState()
    val avatarVersion by repo.avatarVersion.collectAsState()
    val dmUnread = channels.values.count { it.isDmOrGm && repo.unreadOf(it, members[it.id]).let { u -> u.unread && !u.muted } }
    val activity = threads.sumOf { it.unreadMentions }
    val currentTeam by repo.currentTeamId.collectAsState()
    // Messages is lit while Home shows the Direct Messages space (the rail's top button), so
    // picking DMs or a server in the rail moves the bubble too.
    val index = when (nav.tab) {
        HomeTab.Home -> if (currentTeam == null) 1 else 0
        HomeTab.Activity -> 2
        HomeTab.You -> 3
    }
    // One selection bubble that travels to the tab picked, rather than one fading out and another
    // fading in: the edge facing the new tab sets off first and the other catches up, so it
    // stretches a little on the way and settles round on arrival. Edges are in tab slots (0..3).
    val leftEdge = remember { Animatable(index.toFloat()) }
    val rightEdge = remember { Animatable(index.toFloat()) }
    LaunchedEffect(index) {
        val goingRight = index > leftEdge.value
        val leads = spring<Float>(dampingRatio = 0.72f, stiffness = 600f)
        val follows = spring<Float>(dampingRatio = 0.9f, stiffness = 240f)
        launch { leftEdge.animateTo(index.toFloat(), if (goingRight) follows else leads) }
        launch { rightEdge.animateTo(index.toFloat(), if (goingRight) leads else follows) }
    }
    val bubble = c.selected
    Row(
        Modifier.navigationBarsPadding().padding(bottom = 18.dp).height(60.dp)
            .clip(RoundedCornerShape(30.dp)).background(c.pill)
            // Solid, so a touch between or around the tabs stops here rather than opening the
            // channel row (or You-tab action, like Sign out) scrolled behind the pill.
            .pointerInput(Unit) {}
            .drawBehind {
                // Each tab is a 48dp button with 4dp either side, after the pill's 10dp edge.
                val slot = TabSlot.toPx()
                val left = (10.dp + 4.dp).toPx() + slot * leftEdge.value
                val width = (48.dp.toPx() + slot * (rightEdge.value - leftEdge.value)).coerceAtLeast(36.dp.toPx())
                val h = 48.dp.toPx()
                // The row runs the other way in right-to-left languages.
                val x = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) size.width - left - width else left
                drawRoundRect(bubble, topLeft = Offset(x, (size.height - h) / 2), size = Size(width, h), cornerRadius = CornerRadius(h / 2))
            }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabButton(Icons.Outlined.Home, "Home", index == 0, 0) {
            val fromAnotherTab = nav.tab != HomeTab.Home
            nav.showHome()
            showList(fromAnotherTab)
        }
        TabButton(Icons.AutoMirrored.Outlined.Chat, "Messages", index == 1, dmUnread) {
            val fromAnotherTab = nav.tab != HomeTab.Home
            nav.showDirectMessages()
            showList(fromAnotherTab)
        }
        TabButton(Icons.Outlined.Notifications, "Activity", index == 2, activity) { nav.tab = HomeTab.Activity }
        val youSelected = nav.tab == HomeTab.You
        val ring by androidx.compose.animation.animateColorAsState(if (youSelected) c.selected else c.pill, label = "youRing")
        Box(
            Modifier.padding(horizontal = 4.dp).size(48.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { nav.tab = HomeTab.You }
                .semantics { contentDescription = "You"; selected = youSelected; role = Role.Tab },
            contentAlignment = Alignment.Center,
        ) {
            Avatar(me?.let { repo.client.avatarUrl(it.id, maxOf(it.lastPictureUpdate, avatarVersion)) }, me?.username ?: "?", 30.dp,
                status = statuses[repo.myId] ?: "online", ringColor = ring)
        }
    }
}

private val TabSlot = 56.dp

@Composable
private fun TabButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, selected: Boolean, badge: Int, onClick: () -> Unit) {
    // The bubble behind the row marks the selected tab, so the button draws none of its own;
    // it still tells a screen reader which tab is selected.
    Box(Modifier.padding(horizontal = 4.dp)) {
        Box(
            Modifier.size(48.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
                .semantics { contentDescription = desc; this.selected = selected; role = Role.Tab },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(icon, null, tint = Matty.colors.icon)
        }
        if (badge > 0) CountBadge(badge, Modifier.align(Alignment.TopEnd))
    }
}
