package ceo.villa.matty.plugins.villanet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.min
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import ceo.villa.matty.ui.components.MattyDialog
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.togetherWith
import ceo.villa.matty.container
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import ceo.villa.matty.data.api.Post
import ceo.villa.matty.plugins.PluginHost
import ceo.villa.matty.ui.components.Avatar
import ceo.villa.matty.ui.components.CenteredLoading
import ceo.villa.matty.ui.components.EmptyState
import ceo.villa.matty.ui.components.MattyCard
import ceo.villa.matty.ui.components.Pill
import ceo.villa.matty.ui.components.PillButton
import ceo.villa.matty.ui.components.PillIconButton
import ceo.villa.matty.ui.components.SectionLabel
import ceo.villa.matty.ui.components.VideoPlayer
import ceo.villa.matty.ui.theme.Matty
import ceo.villa.matty.ui.theme.VillaNetRed
import ceo.villa.matty.ui.theme.VillaNetRedOnBlack
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import java.text.DateFormat
import java.util.Date

private val LiveRed = Color(0xFFFF3B47)
private val VideoShape = RoundedCornerShape(20.dp)
private val TileShape = RoundedCornerShape(16.dp)
private val RowThumbShape = RoundedCornerShape(12.dp)
/** Badges over pictures. */
private val Glass = Color(0x99000000)
/** Buttons over the playing stream. */
private val ControlGlass = Color(0x66000000)

/**
 * Paints [color] outside a rounded rect, over everything drawn inside. A SurfaceView (the stream)
 * ignores Compose clipping, so this is what rounds its corners. Goes before `clip` in the chain.
 */
private fun Modifier.cornerMask(radius: Dp, color: Color) = drawWithCache {
    val r = radius.toPx()
    val path = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(Rect(Offset.Zero, size))
        addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(r)))
    }
    onDrawWithContent { drawContent(); drawPath(path, color) }
}

/** Lets a sideways rail run to the screen's edges, past the feed's side padding. */
private fun Modifier.bleed(x: Dp) = layout { m, c ->
    val px = x.roundToPx()
    val p = m.measure(c.copy(minWidth = c.minWidth + 2 * px, maxWidth = c.maxWidth + 2 * px))
    layout(c.maxWidth, p.height) { p.placeRelative(-px, 0) }
}

private fun openStream(plugin: VillaNetPlugin, host: PluginHost, s: VnStream) {
    // Twitch is watched on Twitch; everything else plays here.
    if (s.isTwitch) host.openUrl("https://www.twitch.tv/${s.twitchLogin}") else plugin.play(s.handle)
}

private fun liveCount(snap: VnSnapshot?) = snap?.streams.orEmpty().count { !it.alwaysOn && !it.paused && it.coveredBy.isEmpty() }

// ---- panel --------------------------------------------------------------------------------

@Composable
fun VillaNetPanel(plugin: VillaNetPlugin, host: PluginHost, args: Map<String, String>, onClose: () -> Unit) {
    VillaNetColors { VillaNetPanelBody(plugin, host, args, onClose) }
}

/** Inside VillaNET (and its bar elsewhere), the accent is VillaNET's own red, whatever the app accent is. */
@Composable
internal fun VillaNetColors(content: @Composable () -> Unit) {
    val base = Matty.colors
    val red = if (base.isDark) VillaNetRedOnBlack else VillaNetRed
    androidx.compose.runtime.CompositionLocalProvider(
        ceo.villa.matty.ui.theme.LocalMattyColors provides base.copy(accent = red, onAccent = Color.White, mentionBar = red, mention = red.copy(alpha = 0.10f)),
        content = content,
    )
}

@Composable
private fun VillaNetPanelBody(plugin: VillaNetPlugin, host: PluginHost, args: Map<String, String>, onClose: () -> Unit) {
    val me by plugin.access.collectAsState()
    val outdated by plugin.outdated.collectAsState()
    val snap by plugin.snapshot.collectAsState()
    val snapError by plugin.error.collectAsState()
    val refused by plugin.appRefused.collectAsState()
    DisposableEffect(Unit) {
        plugin.setVisible(true)
        onDispose { plugin.setVisible(false) }
    }
    var tab by rememberSaveable { mutableStateOf(if (args.containsKey("clip")) 1 else if (args.containsKey("plan")) 2 else 0) }
    var openClip by remember { mutableStateOf(args["clip"]) }
    val c = Matty.colors

    // The window, not the device: a phone on its side has a short window, a tablet a wide one.
    val conf = LocalConfiguration.current
    val landscape = conf.screenWidthDp > conf.screenHeightDp
    val compactHeight = conf.screenHeightDp < 480
    val wide = conf.screenWidthDp >= 600
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // The header floats over the content (no band): this is how far down the content starts.
    val headerH = statusTop + if (compactHeight) 56.dp else 64.dp

    val playing by plugin.playingHandle.collectAsState()
    val watched = snap?.streams?.firstOrNull { it.handle == playing }?.takeIf { !it.isTwitch }

    // Full screen follows from the situation rather than being switched on and off: watching on
    // the Live tab with the phone on its side, or asked for with the button. Leaving it on a
    // phone that's still sideways keeps the side-by-side view until the phone is upright again.
    var fsRequested by rememberSaveable { mutableStateOf(false) }
    var rotatedByUs by rememberSaveable { mutableStateOf(false) }
    var sideBySide by rememberSaveable { mutableStateOf(false) }
    // Never over the page that says why the server refuses this Matty.
    val fullscreen = watched != null && refused == null && (fsRequested || (landscape && compactHeight && tab == 0 && !sideBySide))
    val activity = LocalContext.current as? android.app.Activity
    fun restoreOrientation() {
        if (rotatedByUs) {
            rotatedByUs = false
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    fun enterFullscreen() {
        fsRequested = true
        // An upright phone turns for it, as video apps do; a tablet letterboxes in its window.
        if (!landscape && !wide) {
            rotatedByUs = true
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }
    fun leaveFullscreen() {
        fsRequested = false
        if (landscape) sideBySide = true
        restoreOrientation()
    }
    val watching = watched != null
    // On the plugin's own signal, which arrives even while the app is in the background (the
    // stream stops there) and the screen isn't being drawn.
    LaunchedEffect(Unit) {
        plugin.playingHandle.collect { if (it == null) { fsRequested = false; restoreOrientation() } }
    }
    LaunchedEffect(landscape) { if (!landscape) { sideBySide = false; if (!rotatedByUs) fsRequested = false } }
    DisposableEffect(Unit) {
        onDispose { if (rotatedByUs) activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    // While a stream plays (or connects), the screen stays on; an error lets it sleep.
    val playerFailed = plugin.player?.state?.collectAsState()?.value == WhepState.Failed
    val holdScreen = watching && !playerFailed
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(holdScreen) {
        view.keepScreenOn = holdScreen
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(enabled = fullscreen) { leaveFullscreen() }

    // The streamer ended it: stop the player, and let the slot say so for a moment rather than
    // vanishing under the finger.
    var lastWatched by remember { mutableStateOf<VnStream?>(null) }
    var ended by remember { mutableStateOf<VnStream?>(null) }
    LaunchedEffect(watched) { if (watched != null) { lastWatched = watched; ended = null } }
    LaunchedEffect(playing, snap) {
        val list = snap?.streams ?: return@LaunchedEffect
        if (playing != null && list.none { it.handle == playing }) {
            ended = lastWatched?.takeIf { it.handle == playing }
            plugin.stop()
        }
    }
    LaunchedEffect(ended) { if (ended != null) { kotlinx.coroutines.delay(4_000); ended = null } }
    // The very frame the stream leaves the list, before the effect above has caught up: the slot
    // says it ended rather than flashing the browse grid.
    val endingNow = if (watched == null && playing != null && snap?.streams?.none { it.handle == playing } == true)
        lastWatched?.takeIf { it.handle == playing } else null
    val endedShown = ended ?: endingNow

    val tabsVisible = outdated == null && me != null && me?.configured == true && me?.access != "locked"
    val name = nameOf(me)
    Box(Modifier.fillMaxSize().background(c.background).semantics { paneTitle = name }) {
        Box(
            Modifier.fillMaxSize()
                // The camera cutout and side navigation bar, once, for the header and every tab.
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .then(if (fullscreen) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            // On the Live tab too while a refusal takes the player's place, until the stream stops.
            val mini = (tab != 0 || refused != null) && watched != null
            val bottom = navBottom + 24.dp + if (mini) 68.dp else 0.dp
            when (panelPage(outdated, me, refused)) {
                PanelPage.OutOfStep -> OutOfStep(host, outdated.orEmpty(), top = headerH)
                PanelPage.Waiting -> if (snapError != null) SnapshotPending(plugin, snapError, Icons.Outlined.LiveTv, headerH) else CenteredLoading(Modifier.padding(top = headerH))
                PanelPage.Refused -> SnapshotPending(plugin, snapError, tabIcons[tab.coerceIn(tabIcons.indices)], headerH)
                PanelPage.NotSetUp -> PanelMessage(Icons.Outlined.Lock, "$name is not set up yet", "An admin sets the Broadcast Box URL in the System Console.", top = headerH)
                PanelPage.Locked -> LockScreen(plugin, top = headerH, bottom = navBottom + 16.dp)
                PanelPage.Tabs -> when (tab) {
                    0 -> LiveTab(
                        plugin, host, snap, snapError,
                        shown = watched ?: endedShown, ended = watched == null && endedShown != null,
                        side = landscape && wide, top = headerH, navBottom = navBottom,
                        videoElsewhere = fullscreen,
                        onFullscreen = { enterFullscreen() },
                        onCloseEnded = { ended = null },
                    )
                    1 -> ClipsTab(plugin, host, top = headerH, bottom = bottom) { openClip = it }
                    2 -> PlansTab(plugin, snap, snapError, highlight = args["plan"], top = headerH, bottom = bottom)
                    3 -> BoardsTab(plugin, snap, snapError, top = headerH, bottom = bottom)
                }
            }
            // Another tab while a stream plays: a pill to get back to it (or stop it), so its
            // sound never plays on with nothing to control it.
            AnimatedVisibility(
                mini, modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
                exit = slideOutVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it } + fadeOut(),
            ) {
                (watched ?: lastWatched)?.let { s -> MiniPlayer(plugin, host, s, onOpen = { tab = 0 }) }
            }
            VillaNetHeader(
                plugin, host, me, tabsVisible = tabsVisible, tab = tab, onTab = { tab = it },
                liveCount = liveCount(snap), wide = wide, compactHeight = compactHeight, onBack = onClose,
            )
        }
        if (fullscreen && watched != null) FullscreenPlayer(plugin, host, snap, watched, onExit = { leaveFullscreen() })
        // Only while the reader may watch, and never over a VillaNET too old for this app: a
        // locked reader unlocks on the lock screen, and the clip asked for opens after that.
        if (clipViewerShown(outdated, me)) openClip?.let { id ->
            // Here the lock is already under the viewer: the server saying "locked" has the
            // reader's access asked again, and the lock screen takes the viewer's place.
            ClipViewer(plugin, host, id, onUnlock = { plugin.resync() }) { openClip = null }
        }
    }
}

/** One floating row: back, the tabs (or the name, before there are any), and the menu. */
@Composable
private fun VillaNetHeader(
    plugin: VillaNetPlugin, host: PluginHost, me: VnAccess?, tabsVisible: Boolean, tab: Int, onTab: (Int) -> Unit,
    liveCount: Int, wide: Boolean, compactHeight: Boolean, onBack: () -> Unit,
) {
    val c = Matty.colors
    Row(
        Modifier.fillMaxWidth().statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = if (compactHeight) 4.dp else 8.dp).height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pill { PillIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onClick = onBack) }
        if (!tabsVisible || wide) {
            // A pill like the rest, so the feed scrolling under it never shows through the name.
            Spacer(Modifier.width(8.dp))
            Pill {
                Spacer(Modifier.width(8.dp))
                AsyncImage(model = plugin.api?.iconUrl, contentDescription = null, modifier = Modifier.size(28.dp).clip(CircleShape))
                Spacer(Modifier.width(8.dp))
                // The server's name for the plugin, up to 32 characters: never at the cost of the tabs.
                Text(nameOf(me), style = MaterialTheme.typography.titleMedium, color = c.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp))
                Spacer(Modifier.width(14.dp))
            }
        }
        if (tabsVisible) {
            Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                SegmentedPill(
                    labels = listOf("Live", "Clips", "Plans", "Boards"),
                    icons = tabIcons,
                    selected = tab, onSelect = onTab,
                    // Someone's live and you're elsewhere: a dot on Live.
                    dotAt = if (liveCount > 0 && tab != 0) 0 else -1,
                    dotLabel = "Live, $liveCount live now",
                    modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth(),
                )
            }
        } else Spacer(Modifier.weight(1f))
        if (me != null) Pill { PanelMenu(plugin, host, me) }
        // Still waiting on VillaNET: the one thing worth doing is asking again.
        else Pill { PillIconButton(Icons.Outlined.Refresh, "Refresh") { plugin.askAgain() } }
    }
}

/** What the panel shows under its header: see [panelPage]. */
internal enum class PanelPage { OutOfStep, Waiting, Refused, NotSetUp, Locked, Tabs }

/**
 * What the panel shows under its header, for the plugin [outdated] (too old for this app), the
 * reader's [access] and [refused] (the server's sentence while it refuses this Matty for the app
 * check). A refusal is shown on every tab, whether or not a snapshot is in hand, until a gated
 * read is answered again: never what the server is refusing.
 */
internal fun panelPage(outdated: String?, access: VnAccess?, refused: String?): PanelPage = when {
    outdated != null -> PanelPage.OutOfStep
    access == null -> PanelPage.Waiting
    refused != null -> PanelPage.Refused
    !access.configured -> PanelPage.NotSetUp
    access.access == "locked" -> PanelPage.Locked
    else -> PanelPage.Tabs
}

/** The tabs' icons, in the tabs' order: Live, Clips, Plans, Boards. */
private val tabIcons = listOf(Icons.Outlined.LiveTv, Icons.Outlined.Movie, Icons.Outlined.CalendarMonth, Icons.Outlined.EmojiEvents)

/**
 * MarkOut's segmented control: one solid pill, a bubble that travels to the picked segment.
 * Labels share the width evenly; when they don't fit (large text), each gets what its label
 * needs; failing that, icons stand in (the labels still read out).
 */
@Composable
private fun SegmentedPill(
    labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
    icons: List<ImageVector>? = null, dotAt: Int = -1, dotLabel: String? = null,
) {
    val c = Matty.colors
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    val natural = remember(labels, style, density.density, density.fontScale) {
        labels.map { l -> with(density) { measurer.measure(l, style, maxLines = 1).size.width.toDp() } }
    }
    BoxWithConstraints(modifier.height(48.dp)) {
        val n = labels.size
        val avail = maxWidth - 4.dp
        val fittedTotal = natural.fold(0.dp) { a, w -> a + w + 12.dp }
        val equal = (natural.maxOrNull() ?: 0.dp) + 16.dp <= avail / n
        val fitted = !equal && fittedTotal <= avail
        val iconsOnly = !equal && !fitted && icons != null
        val widths = if (fitted) {
            val extra = (avail - fittedTotal) / n
            natural.map { it + 12.dp + extra }
        } else List(n) { avail / n }
        val offsets = widths.runningFold(0.dp) { a, w -> a + w }
        val sel = selected.coerceIn(0, n - 1)
        val left = remember { Animatable(offsets[sel], Dp.VectorConverter) }
        val right = remember { Animatable(offsets[sel + 1], Dp.VectorConverter) }
        val shownSel = remember { intArrayOf(sel) }
        LaunchedEffect(sel, offsets) {
            if (sel == shownSel[0]) {
                // Widths changed (rotation, text size): the bubble just goes there.
                left.snapTo(offsets[sel]); right.snapTo(offsets[sel + 1])
            } else {
                shownSel[0] = sel
                // The edge facing the new segment sets off first and the other catches up.
                val goingRight = offsets[sel] > left.value
                val leads = spring<Dp>(dampingRatio = 0.72f, stiffness = 600f)
                val follows = spring<Dp>(dampingRatio = 0.9f, stiffness = 240f)
                launch { left.animateTo(offsets[sel], if (goingRight) follows else leads) }
                launch { right.animateTo(offsets[sel + 1], if (goingRight) leads else follows) }
            }
        }
        val bubble = c.selected
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        Row(
            Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(c.pill).pointerInput(Unit) {}
                .drawBehind {
                    val l = (2.dp + left.value + 3.dp).toPx()
                    val w = (right.value - left.value - 6.dp).toPx().coerceAtLeast(0f)
                    val x = if (rtl) size.width - l - w else l
                    drawRoundRect(bubble, topLeft = Offset(x, 4.dp.toPx()), size = Size(w, 40.dp.toPx()), cornerRadius = CornerRadius(20.dp.toPx()))
                }
                .padding(horizontal = 2.dp).selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            labels.forEachIndexed { i, label ->
                val on = i == sel
                val tint by animateColorAsState(if (on) c.title else c.textSecondary, label = "segment")
                Box(
                    Modifier.width(widths[i]).fillMaxHeight()
                        .selectable(selected = on, interactionSource = null, indication = null, role = Role.Tab) { onSelect(i) }
                        .semantics {
                            if (i == dotAt && dotLabel != null) contentDescription = dotLabel
                            else if (iconsOnly) contentDescription = label
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (iconsOnly) Icon(icons!![i], null, tint = tint, modifier = Modifier.size(22.dp))
                        else Text(label, color = tint, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // A badge on the corner: shown in either tier, and it never moves the layout.
                        if (i == dotAt) Box(
                            Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = if (iconsOnly) (-1).dp else 1.dp)
                                .size(6.dp).background(c.accent, CircleShape),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LockScreen(plugin: VillaNetPlugin, top: Dp, bottom: Dp) {
    val c = Matty.colors
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // Scrolls, so a phone on its side with the keyboard up can still reach the field and button.
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = top + 8.dp, bottom = bottom),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MattyCard(Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
            Box(Modifier.size(64.dp).background(c.pill, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Lock, null, tint = VillaNetRedOnBlack, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text("Unlock ${plugin.displayName()}", style = MaterialTheme.typography.titleLarge, color = c.title)
            Spacer(Modifier.height(6.dp))
            Text(
                "Paste the bearer token your streaming software publishes with. It is checked once by Broadcast Box and never stored on this phone.",
                style = MaterialTheme.typography.bodyMedium, color = c.textSecondary,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = token, onValueChange = { token = it; status = null },
                placeholder = { Text("Bearer token") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = Matty.FieldShape, modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = VillaNetRedOnBlack, unfocusedBorderColor = c.rim),
            )
            AnimatedVisibility(status != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Text(status.orEmpty(), color = c.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(16.dp))
            PillButton("Unlock", primary = true, loading = busy, modifier = Modifier.fillMaxWidth(), height = 56.dp) {
                busy = true
                scope.launch {
                    status = plugin.link(token)
                    if (status == null) token = ""
                    busy = false
                }
            }
        }
    }
}

/** The header's ⋮: who you are on the plugin, Refresh, and the rarely-used account action. */
@Composable
private fun PanelMenu(plugin: VillaNetPlugin, host: PluginHost, me: VnAccess) {
    val c = Matty.colors
    val self by host.repo.me.collectAsState()
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    Box {
        PillIconButton(Icons.Outlined.MoreVert, "More") { open = true }
        DropdownMenu(open, { open = false }, shape = RoundedCornerShape(24.dp), containerColor = c.pill) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(model = plugin.api?.iconUrl, contentDescription = null, modifier = Modifier.size(28.dp).clip(CircleShape))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(nameOf(me), color = c.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        when (me.access) {
                            "linked" -> self?.username?.let { "Logged in as @$it" } ?: "Linked"
                            "letIn" -> "Watch only"
                            "locked" -> "Locked"
                            else -> "Not connected"
                        },
                        color = c.textSecondary, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            // Which release this server presents, and whether it verified this app.
            val checks = plugin.checks()
            if (checks.isNotEmpty()) Column(Modifier.widthIn(max = 320.dp).padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
                checks.forEach { line ->
                    Text(line.text, color = if (line.warning) c.dnd else c.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider(color = c.divider)
            DropdownMenuItem(
                text = { Text("Refresh", color = c.title) },
                leadingIcon = { Icon(Icons.Outlined.Refresh, null, tint = c.icon) },
                // While the server refuses this Matty for the app check, Refresh proves it once more, as Try again does.
                onClick = { open = false; plugin.askAgain() },
            )
            if (me.linked) DropdownMenuItem(
                text = { Text("Unlink my stream", color = c.destructive) },
                leadingIcon = { Icon(Icons.Outlined.LinkOff, null, tint = c.destructive) },
                onClick = { open = false; confirm = true },
            )
        }
    }
    if (confirm) {
        MattyDialog("Unlink Stream", onDismiss = { confirm = false }, icon = Icons.Outlined.LinkOff, actions = {
            TextButton(onClick = { confirm = false }) { Text("Cancel") }
            TextButton(onClick = {
                confirm = false
                // On the session's scope, not this dialog's: a failure is reported, never thrown.
                host.repo.launchSafe { plugin.unlink()?.let { host.repo.emitNotice(it) } }
            }) { Text("Unlink", color = c.destructive) }
        }) {
            Text("Your Broadcast Box stream will no longer be linked to this account. You can link it again with your bearer token.",
                style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
        }
    }
}

/** A full-panel message under the header; it scrolls, so a short window never squeezes it. */
@Composable
private fun PanelMessage(icon: ImageVector, title: String, body: String, top: Dp, action: (@Composable () -> Unit)? = null) {
    EmptyState(icon, title, body, Modifier.verticalScroll(rememberScrollState()).padding(top = top), action = action)
}

/**
 * The server's VillaNET is older than this app is written for. The two are released as a pair;
 * rather than guess at what an older plugin means, the app says which one it needs.
 */
@Composable
private fun OutOfStep(host: PluginHost, theirs: String, top: Dp) {
    val self by host.repo.me.collectAsState()
    val admin = self?.isSystemAdmin == true
    PanelMessage(
        Icons.Outlined.SystemUpdate, "Matty Plugin needs an update",
        outOfStepWords(theirs) + if (admin) " Install it in System Console → Plugins." else " Ask an admin to update it.",
        top,
    )
}

/**
 * Before the plugin's first answer, or while it refuses this Matty for the app check: a spinner,
 * or (if the answer didn't come, or was a refusal) why, and the way to ask again.
 */
@Composable
private fun SnapshotPending(plugin: VillaNetPlugin, error: String?, icon: ImageVector, top: Dp) {
    val refused by plugin.appRefused.collectAsState()
    val proving = plugin.proving()
    val words = if (error != null || refused != null) pendingWords(plugin.displayName(), refused, proving) else null
    if (words != null) {
        val (title, body) = words
        // Refused for the app check: the person's tap proves this Matty once more.
        PanelMessage(icon, title, body, top, action = { PillButton("Try again", icon = Icons.Outlined.Refresh) { plugin.askAgain() } })
    } else CenteredLoading(Modifier.padding(top = top))
}

/**
 * The title and the sentence of [SnapshotPending] for the plugin called [name]: [refused] is the
 * server's sentence while it refuses this Matty for not having passed its app check, null when it
 * simply didn't answer. Null while a refusal for the app check is in hand and this Matty's proof
 * is still on its way ([proving]): the answer is waited for.
 */
internal fun pendingWords(name: String, refused: String?, proving: Boolean = false): Pair<String, String>? = when {
    refused != null && proving -> null
    refused != null -> "$name is for the official Matty app" to refused
    else -> "Can't reach $name" to "Check your connection, then try again."
}

// ---- live ---------------------------------------------------------------------------------

/**
 * Browsing: a feed of streams (a grid on a wide window). Watching: the stream pinned at the top
 * with what else is on under it, or, on a wide window, the stream on the left and the rest beside
 * it. The picture itself never scrolls, moves or animates.
 */
@Composable
private fun LiveTab(
    plugin: VillaNetPlugin, host: PluginHost, snap: VnSnapshot?, snapError: String?,
    shown: VnStream?, ended: Boolean, side: Boolean, top: Dp, navBottom: Dp, videoElsewhere: Boolean,
    onFullscreen: () -> Unit, onCloseEnded: () -> Unit,
) {
    var refreshing by remember { mutableStateOf(false) }
    val onRefresh: () -> Unit = {
        refreshing = true
        host.repo.launchSafe { try { plugin.resyncAndWait() } finally { refreshing = false } }
    }
    if (snap == null) { SnapshotPending(plugin, snapError, Icons.Outlined.LiveTv, top); return }
    when {
        shown == null -> StreamFeed(
            plugin, host, snap, compact = false, exclude = null,
            padding = PaddingValues(start = 16.dp, top = top, end = 16.dp, bottom = navBottom + 24.dp), gutter = 16.dp,
            refreshing = refreshing, onRefresh = onRefresh,
        )
        !side -> BoxWithConstraints(Modifier.fillMaxSize().padding(top = top)) {
            // Whole, but never so tall that it pushes the controls (64dp) and the start of the list
            // off a short window.
            val colW = min(maxWidth, 720.dp) - 32.dp
            val playerW = min(colW, ((maxHeight - 64.dp - 120.dp) * (16f / 9f)).coerceAtLeast(160.dp))
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = 720.dp).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    InlinePlayer(plugin, host, snap, shown, ended, videoElsewhere, onFullscreen, Modifier.size(playerW, playerW * (9f / 16f)))
                    NowPlaying(plugin, host, shown, ended, avatar = true, onFullscreen = onFullscreen, onCloseEnded = onCloseEnded)
                }
                StreamFeed(
                    plugin, host, snap, compact = true, exclude = shown.handle,
                    padding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = navBottom + 24.dp), gutter = 16.dp,
                    refreshing = refreshing, onRefresh = onRefresh,
                    modifier = Modifier.widthIn(max = 720.dp).weight(1f),
                )
            }
        }
        else -> BoxWithConstraints(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = top + 4.dp)) {
            // As big as fits beside a usable column, always whole, never scrolled away.
            val playerW = min(maxWidth * 0.6f, (maxHeight - navBottom - 8.dp) * (16f / 9f))
            val railW = maxWidth - playerW - 16.dp
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                InlinePlayer(plugin, host, snap, shown, ended, videoElsewhere, onFullscreen, Modifier.size(playerW, playerW * (9f / 16f)))
                Column(Modifier.width(railW).fillMaxHeight()) {
                    NowPlaying(plugin, host, shown, ended, avatar = railW >= 340.dp, onFullscreen = onFullscreen, onCloseEnded = onCloseEnded)
                    StreamFeed(
                        plugin, host, snap, compact = true, exclude = shown.handle,
                        padding = PaddingValues(top = 4.dp, bottom = navBottom + 16.dp), gutter = 0.dp,
                        refreshing = refreshing, onRefresh = onRefresh,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Everything on VillaNET right now: who's live, who's in voice, what's planned, the 24/7 channels. */
@Composable
private fun StreamFeed(
    plugin: VillaNetPlugin, host: PluginHost, snap: VnSnapshot, compact: Boolean, exclude: String?,
    padding: PaddingValues, gutter: Dp, refreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier,
) {
    val api = plugin.api ?: return
    val c = Matty.colors
    val live = snap.streams.filter { it.coveredBy.isEmpty() && !it.alwaysOn && it.handle != exclude }
    val channels = snap.streams.filter { it.alwaysOn && it.handle != exclude }
    val voice = snap.voice?.takeIf { it.connected && it.people.isNotEmpty() }
    val upcoming = snap.schedule.filter { it.startsAt > System.currentTimeMillis() - 3_600_000 }.sortedBy { it.startsAt }
    val previews = snap.livePreviews
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize(), state = state,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state, isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = padding.calculateTopPadding()),
                containerColor = c.pill, color = c.title,
            )
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewport = maxHeight
            val full: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
            LazyVerticalGrid(
                columns = if (compact) GridCells.Fixed(1) else GridCells.Adaptive(240.dp),
                contentPadding = padding,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                val nothing = live.isEmpty() && channels.isEmpty() && voice == null && upcoming.isEmpty()
                if (!compact && nothing) {
                    // Inside the grid, so pulling down still refreshes.
                    item(key = "empty", span = full) {
                        Box(Modifier.fillMaxWidth().heightIn(min = (viewport - padding.calculateTopPadding() - padding.calculateBottomPadding()).coerceAtLeast(0.dp)), contentAlignment = Alignment.Center) {
                            EmptyState(Icons.Outlined.LiveTv, "Nobody's live", "Streams show up here the moment someone goes live.")
                        }
                    }
                    return@LazyVerticalGrid
                }
                if (live.isNotEmpty()) item(key = "hdr-live", span = full) {
                    SectionLabel(if (compact) "Also live · ${live.size}" else "Live now · ${live.size}", Modifier.padding(top = 8.dp).animateItem())
                } else if (!compact) item(key = "nobody", span = full) { NobodyLive(Modifier.animateItem()) }
                else if (channels.isEmpty()) item(key = "none-else", span = full) {
                    Text("Nobody else is live right now.", color = c.textTertiary, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 12.dp).animateItem())
                }
                items(live, key = { "live:" + it.handle }, contentType = { if (compact) 1 else 0 }) { s ->
                    if (compact) StreamRow(plugin, api, host, s, previews, Modifier.animateItem())
                    else StreamHero(plugin, api, host, s, previews, Modifier.animateItem())
                }
                voice?.let { v ->
                    item(key = "hdr-voice", span = full) { SectionLabel("In voice · ${v.people.size}", Modifier.padding(top = 8.dp).animateItem()) }
                    item(key = "voice", span = full) { VoiceRow(host, v.people, gutter, Modifier.bleed(gutter).padding(bottom = 20.dp).animateItem()) }
                }
                if (upcoming.isNotEmpty()) {
                    item(key = "hdr-next", span = full) { SectionLabel("Up next", Modifier.padding(top = 8.dp).animateItem()) }
                    item(key = "next", span = full) {
                        LazyRow(
                            Modifier.bleed(gutter).padding(bottom = 20.dp).animateItem(),
                            contentPadding = PaddingValues(horizontal = gutter), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) { items(upcoming, key = { it.id }) { PlanChip(plugin, it) } }
                    }
                }
                if (channels.isNotEmpty()) {
                    item(key = "hdr-247", span = full) { SectionLabel("24/7 channels", Modifier.padding(top = 8.dp).animateItem()) }
                    item(key = "247", span = full) {
                        LazyRow(
                            Modifier.bleed(gutter).padding(bottom = 20.dp).animateItem(),
                            contentPadding = PaddingValues(horizontal = gutter), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) { items(channels, key = { it.handle }) { s -> ChannelTile(plugin, api, host, s, previews) } }
                    }
                }
            }
        }
    }
}

/** Nobody's live, said once and quietly, with the rest of the feed still under it. */
@Composable
private fun NobodyLive(modifier: Modifier = Modifier) {
    val c = Matty.colors
    Row(modifier.padding(top = 8.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).background(c.pill, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.LiveTv, null, tint = c.icon, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Nobody's live", color = c.title, style = MaterialTheme.typography.titleSmall)
            Text("Streams show up here the moment someone goes live.", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun VoiceRow(host: PluginHost, people: List<VnVoicePerson>, gutter: Dp, modifier: Modifier = Modifier) {
    val c = Matty.colors
    LazyRow(modifier, contentPadding = PaddingValues(horizontal = gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(people) { p ->
            Column(
                Modifier.width(60.dp).semantics(mergeDescendants = true) { contentDescription = if (p.muted) "${p.name}, muted" else p.name },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    Avatar(if (p.userId.isNotEmpty()) host.repo.avatarUrl(p.userId) else null, p.name, 44.dp)
                    // Only the exception gets a mark: being in voice is the point of the row.
                    if (p.muted) Icon(Icons.Outlined.MicOff, null, tint = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).size(16.dp).background(c.dnd, CircleShape).padding(3.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(p.name, style = MaterialTheme.typography.labelSmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---- tiles ---------------------------------------------------------------------------------

@Composable
private fun LiveBadge(small: Boolean = false) {
    Box(
        Modifier.heightIn(min = if (small) 16.dp else 20.dp).background(VillaNetRed, CircleShape)
            .padding(horizontal = if (small) 5.dp else 7.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("LIVE", color = Color.White, fontSize = if (small) 9.sp else 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
    }
}

@Composable
private fun GlassBadge(text: String, icon: ImageVector? = null, small: Boolean = false, modifier: Modifier = Modifier) {
    Row(
        modifier.heightIn(min = if (small) 16.dp else 20.dp).background(Glass, CircleShape)
            .padding(horizontal = if (small) 5.dp else 7.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(if (small) 10.dp else 12.dp))
            Spacer(Modifier.width(3.dp))
        }
        Text(text, color = Color.White, fontSize = if (small) 9.sp else 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp)
    }
}

/** Who's behind a stream: their Matty picture, a 24/7 channel's own picture, or initials. */
@Composable
private fun StreamAvatar(api: VillaNetApi, host: PluginHost, s: VnStream, size: Dp, modifier: Modifier = Modifier) {
    when {
        s.userId.isNotEmpty() -> Avatar(host.repo.avatarUrl(s.userId), s.name, size, modifier)
        s.alwaysOn && s.pictureVersion.isNotEmpty() ->
            AsyncImage(model = api.pictureUrl(s), contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.size(size).clip(CircleShape))
        else -> Avatar(null, s.name, size, modifier)
    }
}

/** The line under a stream's name: watchers, their note, what they're playing. */
private fun streamMeta(s: VnStream, count: Boolean): String = listOfNotNull(
    if (count && s.viewers > 0) "${s.viewers} watching" + if (s.isTwitch) " on Twitch" else "" else null,
    s.motd.ifBlank { null },
    s.nowPlaying?.let { np -> (if (np.category == "game") "Playing " else "Watching ") + np.title },
    s.twitchCategory.ifBlank { null },
).joinToString(" · ")

internal fun streamDescription(s: VnStream): String {
    val what = when {
        s.alwaysOn -> "24/7 channel"
        s.isTwitch -> "live on Twitch"
        s.paused -> "paused"
        else -> "live"
    }
    val count = if (s.viewers > 0) ", ${s.viewers} watching" else ""
    val meta = streamMeta(s, count = false).ifBlank { null }?.let { ". $it" }.orEmpty()
    val w = s.watchers.orEmpty().map { it.who }.filter { it.isNotBlank() }
    val by = when {
        w.isEmpty() -> ""
        s.others > 0 -> ". Watched by ${w.joinToString(", ")} and ${s.others} others"
        w.size == 1 -> ". Watched by ${w[0]}"
        else -> ". Watched by ${w.dropLast(1).joinToString(", ")} and ${w.last()}"
    }
    return "${s.name}, $what$count$meta$by"
}

/** The stream's picture: its cover or latest still, or (failing that) a soft poster of who it is. */
@Composable
private fun StreamThumb(
    api: VillaNetApi, host: PluginHost, s: VnStream, livePreviews: Boolean, modifier: Modifier,
    badges: Boolean = true, small: Boolean = false,
) {
    // In the web panel's order: a 24/7 channel's card picture first (whatever its source), then a
    // Twitch preview, then a live stream's latest still.
    val img = when {
        s.alwaysOn -> s.thumbnailVersion.ifEmpty { null }?.let { api.thumbnailUrl(s) }
        s.isTwitch -> if (livePreviews) s.previewVersion.ifEmpty { null }?.let { api.twitchPreviewUrl(s) } else null
        livePreviews && s.stillVersion.isNotEmpty() -> api.stillUrl(s)
        else -> null
    }
    var failed by remember(img) { mutableStateOf(false) }
    Box(modifier.background(Color.Black)) {
        if (img != null && !failed) AsyncImage(
            model = img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            onError = { e ->
                android.util.Log.w("Matty", "VillaNET card picture for ${s.name} didn't load: ${e.result.throwable}")
                failed = true
            },
        ) else PosterFallback(api, host, s, small)
        if (badges) {
            val inset = if (small) 6.dp else 10.dp
            // One badge, saying the one thing that matters about it.
            Box(Modifier.align(Alignment.TopStart).padding(inset)) {
                when {
                    s.alwaysOn -> GlassBadge("24/7", small = small)
                    s.paused -> GlassBadge("Paused", Icons.Outlined.Pause, small = small)
                    else -> LiveBadge(small)
                }
            }
            if (s.isTwitch) GlassBadge("Twitch", Icons.AutoMirrored.Outlined.OpenInNew, small = small, modifier = Modifier.align(Alignment.BottomEnd).padding(inset))
        }
    }
}

@Composable
private fun PosterFallback(api: VillaNetApi, host: PluginHost, s: VnStream, small: Boolean) {
    val c = Matty.colors
    Box(Modifier.fillMaxSize().background(c.card)) {
        val pic = when {
            s.userId.isNotEmpty() -> host.repo.avatarUrl(s.userId)
            s.alwaysOn && s.pictureVersion.isNotEmpty() -> api.pictureUrl(s)
            else -> null
        }
        if (!small && pic != null) {
            // Blurred to a wash of their colours: no ghost of a face or an initial.
            AsyncImage(model = pic, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().scale(1.8f).blur(64.dp))
            Box(Modifier.fillMaxSize().background(Color(0x73000000)))
        }
        StreamAvatar(api, host, s, if (small) 28.dp else 56.dp, Modifier.align(Alignment.Center))
    }
}

@Composable
private fun WatcherFaces(host: PluginHost, s: VnStream, modifier: Modifier = Modifier) {
    val c = Matty.colors
    val watchers = s.watchers.orEmpty()
    val faces = watchers.take(3)
    val more = watchers.size - faces.size + s.others
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
            faces.forEach { w ->
                Avatar(if (w.userId.isNotEmpty()) host.repo.avatarUrl(w.userId) else null, w.who, 22.dp,
                    modifier = Modifier.border(2.dp, c.background, CircleShape))
            }
        }
        if (more > 0) Text("+$more", color = c.textTertiary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp))
    }
}

/** A stream in the feed while browsing: the picture, then who and what, YouTube-style. */
@Composable
private fun StreamHero(plugin: VillaNetPlugin, api: VillaNetApi, host: PluginHost, s: VnStream, livePreviews: Boolean, modifier: Modifier = Modifier) {
    val c = Matty.colors
    Column(
        modifier.fillMaxWidth().padding(bottom = 20.dp).clip(VideoShape)
            .clickable(onClickLabel = if (s.isTwitch) "Open on Twitch" else "Watch") { openStream(plugin, host, s) }
            .semantics { contentDescription = streamDescription(s) },
    ) {
        StreamThumb(api, host, s, livePreviews, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(VideoShape))
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
            StreamAvatar(api, host, s, 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name, color = c.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val meta = streamMeta(s, count = s.watchers.isNullOrEmpty())
                if (meta.isNotBlank()) Text(meta, color = c.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (!s.watchers.isNullOrEmpty()) {
                Spacer(Modifier.width(8.dp))
                WatcherFaces(host, s, Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** A stream in the list under the one you're watching: tap to switch, the player stays put. */
@Composable
private fun StreamRow(plugin: VillaNetPlugin, api: VillaNetApi, host: PluginHost, s: VnStream, livePreviews: Boolean, modifier: Modifier = Modifier) {
    val c = Matty.colors
    Row(
        modifier.fillMaxWidth().padding(bottom = 4.dp).heightIn(min = 72.dp).clip(Matty.RowShape)
            .clickable(onClickLabel = if (s.isTwitch) "Open on Twitch" else "Switch to this stream") { openStream(plugin, host, s) }
            .semantics { contentDescription = streamDescription(s) }
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StreamThumb(api, host, s, livePreviews, Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RowThumbShape), small = true)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name, color = c.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = streamMeta(s, count = true)
            if (meta.isNotBlank()) Text(meta, color = c.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A 24/7 channel in its sideways rail. */
@Composable
private fun ChannelTile(plugin: VillaNetPlugin, api: VillaNetApi, host: PluginHost, s: VnStream, livePreviews: Boolean) {
    val c = Matty.colors
    Column(
        Modifier.width(200.dp).clip(TileShape)
            .clickable(onClickLabel = "Watch") { openStream(plugin, host, s) }
            .semantics { contentDescription = streamDescription(s) },
    ) {
        StreamThumb(api, host, s, livePreviews, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(TileShape))
        Text(s.name, color = c.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp))
        if (s.motd.isNotBlank()) Text(s.motd, color = c.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(4.dp))
    }
}

// ---- player -------------------------------------------------------------------------------

/**
 * The stream's picture, letterboxed in black, with its cover over it until the first frame
 * arrives (then it fades away), and what's happening when it isn't playing.
 *
 * Minimised, a stream plays on for its sound and its picture stops being received
 * ([WhepPlayer.picture]). Back on screen the surface is new and empty, and stays so until the
 * stream's next key frame: until then what was on screen when Matty was left is shown, as if the
 * picture had only frozen for a moment.
 */
@Composable
private fun StreamVideo(plugin: VillaNetPlugin, s: VnStream, poster: @Composable () -> Unit, modifier: Modifier) {
    val player = plugin.player ?: return
    val state by player.state.collectAsState()
    val error by player.error.collectAsState()
    val session by player.session.collectAsState()
    val picture by player.picture.collectAsState()
    // A renderer per stream and per play (Try again, Resume): its first-frame signal comes once
    // per renderer, and a fresh start must never show the last frame of an old one.
    key(s.handle, session) {
        // A view made while the stream already plays (full screen, rotation) has frames at once.
        var firstFrame by remember { mutableStateOf(player.state.value == WhepState.Playing) }
        // A paused streamer sends no frames: their poster stays up under the Paused badge.
        val showing = firstFrame && picture && state == WhepState.Playing && !s.paused

        val view = remember { arrayOfNulls<SurfaceViewRenderer>(1) }
        // The picture as it was when Matty was last left, and whether that is what to show: from
        // the moment the picture is found not to be arriving until the new one has faded in.
        var left by remember { mutableStateOf<ImageBitmap?>(null) }
        var returning by remember { mutableStateOf(false) }
        LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
            val r = view[0]
            // Only ever the stream's own picture, and only one that is there to copy.
            if (r != null && showing && r.width > 0 && r.height > 0 && r.holder.surface.isValid) runCatching {
                val shot = android.graphics.Bitmap.createBitmap(r.width, r.height, android.graphics.Bitmap.Config.ARGB_8888)
                android.view.PixelCopy.request(
                    r, shot, { result -> if (result == android.view.PixelCopy.SUCCESS) left = shot.asImageBitmap() },
                    android.os.Handler(android.os.Looper.getMainLooper()),
                )
            }
        }
        // Back before the picture was ever switched off: it never stopped, and the copy isn't needed.
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (picture && !returning) left = null }
        LaunchedEffect(picture) {
            if (!picture) returning = true
            else if (returning) {
                kotlinx.coroutines.delay(POSTER_FADE_MS.toLong())
                returning = false
                left = null
            }
        }

        // SurfaceViewRenderer measures to fill whatever it's given, and the renderer then crops
        // to that shape: wrapped (centred, sized to fit), the picture is letterboxed instead.
        Box(modifier.background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    val renderer = SurfaceViewRenderer(ctx).apply {
                        init(Rtc.egl.eglBaseContext, object : RendererCommon.RendererEvents {
                            // On the render thread: hand it to the main one.
                            override fun onFirstFrameRendered() { post { firstFrame = true } }
                            override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {}
                        })
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                        setEnableHardwareScaler(true)
                        player.attachSink(this)
                    }
                    view[0] = renderer
                    android.widget.FrameLayout(ctx).apply {
                        addView(renderer, android.widget.FrameLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
                    }
                },
                // Each view lets go only of itself, whichever order layouts come and go in.
                onRelease = { f -> (f.getChildAt(0) as SurfaceViewRenderer).let { r -> player.detachSink(r); r.release() } },
                modifier = Modifier.fillMaxSize(),
            )
            // Over the surface until there is a picture to see, fading away when there is. Put
            // back at once (no fade in over an empty surface) when the picture has stopped being
            // received, which is only ever found out on coming back.
            val fade by animateFloatAsState(if (showing) 0f else 1f, if (picture) tween(POSTER_FADE_MS) else snap(), label = "poster")
            val coverAlpha = if (picture) fade else 1f
            if (coverAlpha > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = coverAlpha }) {
                val kept = left.takeIf { !picture || returning }
                // The copy is of the picture alone, with none of the black around it.
                if (kept != null) Image(kept, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) else poster()
            }
            when (state) {
                WhepState.Connecting -> Box(
                    Modifier.fillMaxSize().background(Color(0x66000000))
                        .semantics { contentDescription = "Connecting"; liveRegion = LiveRegionMode.Polite },
                    contentAlignment = Alignment.Center,
                ) { LoadingIndicator(Modifier.size(40.dp), color = Color.White) }
                WhepState.Failed -> FailedOverlay(error, onRetry = { plugin.play(s.handle) })
                else -> {}
            }
            if (state == WhepState.Playing && s.paused) GlassBadge("Paused", Icons.Outlined.Pause, modifier = Modifier.align(Alignment.Center))
        }
    }
}

private const val POSTER_FADE_MS = 250

@Composable
private fun FailedOverlay(error: String?, onRetry: () -> Unit) {
    val resume = error == WhepPlayer.PAUSED_FOR_AUDIO
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 280.dp).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                error ?: "Couldn't play this stream.", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Spacer(Modifier.height(12.dp))
            PillButton(
                if (resume) "Resume" else "Try again", icon = if (resume) Icons.Outlined.PlayArrow else Icons.Outlined.Refresh,
                height = 40.dp, modifier = Modifier.minimumInteractiveComponentSize(), onClick = onRetry,
            )
        }
    }
}

/** The stream in the panel: a clean picture (tap it for full screen) with rounded corners. */
@Composable
private fun InlinePlayer(
    plugin: VillaNetPlugin, host: PluginHost, snap: VnSnapshot, s: VnStream, ended: Boolean, videoElsewhere: Boolean,
    onFullscreen: () -> Unit, modifier: Modifier,
) {
    val api = plugin.api ?: return
    val c = Matty.colors
    // Failed: the overlay's button is the thing to tap, and a near miss mustn't go full screen.
    val failed = plugin.player?.state?.collectAsState()?.value == WhepState.Failed
    val poster = @Composable { StreamThumb(api, host, s, snap.livePreviews, Modifier.fillMaxSize(), badges = false) }
    Box(
        modifier.cornerMask(20.dp, c.background).clip(VideoShape).background(Color.Black)
            .then(if (!ended && !failed) Modifier.clickable(interactionSource = null, indication = null, onClickLabel = "Full screen", onClick = onFullscreen) else Modifier)
            .semantics { contentDescription = "${s.name}'s stream" },
    ) {
        when {
            ended -> {
                poster()
                Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
                    Text("${s.name} ended the stream", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
            // Full screen draws the only picture; this is just the stand-in behind it.
            videoElsewhere -> {}
            else -> StreamVideo(plugin, s, poster, Modifier.fillMaxSize())
        }
    }
}

/** Under (or beside) the picture: who it is and how it's going, then sound, full screen, stop. */
@Composable
private fun NowPlaying(
    plugin: VillaNetPlugin, host: PluginHost, s: VnStream, ended: Boolean, avatar: Boolean,
    onFullscreen: () -> Unit, onCloseEnded: () -> Unit,
) {
    val api = plugin.api ?: return
    val player = plugin.player ?: return
    val muted by player.mutedState.collectAsState()
    val c = Matty.colors
    Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (avatar) {
            StreamAvatar(api, host, s, 40.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(s.name, color = c.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(if (!ended && !s.paused) c.accent else c.textTertiary, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (ended) "Offline" else listOfNotNull(
                        if (s.paused) "Paused" else if (s.alwaysOn) "24/7" else "LIVE",
                        "${s.viewers} watching".takeIf { s.viewers > 0 },
                        s.motd.ifBlank { null },
                    ).joinToString(" · "),
                    color = c.textSecondary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Pill {
            if (ended) PillIconButton(Icons.Outlined.Close, "Close", onClick = onCloseEnded)
            else {
                PillIconButton(if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp, if (muted) "Unmute" else "Mute") { player.muted = !muted }
                PillIconButton(Icons.Outlined.Fullscreen, "Full screen", onClick = onFullscreen)
                PillIconButton(Icons.Outlined.Close, "Stop watching") { plugin.stop() }
            }
        }
    }
}

/** On another tab while a stream plays: where it is, and the way back. No picture, just sound. */
@Composable
private fun MiniPlayer(plugin: VillaNetPlugin, host: PluginHost, s: VnStream, onOpen: () -> Unit) {
    MiniPlayerPill(
        plugin, host, s, openLabel = "Back to the stream", onOpen = onOpen,
        modifier = Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 12.dp).widthIn(max = 480.dp).fillMaxWidth(),
    )
}

/** The playing stream in one pill: who, how it's going, sound (or Resume) and stop; a tap opens it. */
@Composable
internal fun MiniPlayerPill(plugin: VillaNetPlugin, host: PluginHost, s: VnStream, openLabel: String, onOpen: () -> Unit, modifier: Modifier) {
    val api = plugin.api ?: return
    val player = plugin.player ?: return
    val muted by player.mutedState.collectAsState()
    val state by player.state.collectAsState()
    val error by player.error.collectAsState()
    val pausedForAudio = state == WhepState.Failed && error == WhepPlayer.PAUSED_FOR_AUDIO
    val c = Matty.colors
    Row(
        modifier.heightIn(min = 56.dp).clip(RoundedCornerShape(28.dp)).background(c.pill)
            .clickable(onClickLabel = openLabel, onClick = onOpen).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StreamAvatar(api, host, s, 32.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name, color = c.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val live = state != WhepState.Connecting && state != WhepState.Failed && !s.paused
                if (live) { Box(Modifier.size(6.dp).background(c.accent, CircleShape)); Spacer(Modifier.width(5.dp)) }
                Text(
                    when {
                        state == WhepState.Connecting -> "Connecting…"
                        pausedForAudio -> "Stopped for other audio"
                        state == WhepState.Failed -> "Couldn't play"
                        else -> (if (s.paused) "Paused" else if (s.alwaysOn) "24/7" else "LIVE") + if (muted) " · Muted" else ""
                    },
                    color = c.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                )
            }
        }
        if (pausedForAudio) PillIconButton(Icons.Outlined.PlayArrow, "Resume") { plugin.play(s.handle) }
        else PillIconButton(if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp, if (muted) "Unmute" else "Mute") { player.muted = !muted }
        PillIconButton(Icons.Outlined.Close, "Stop watching") { plugin.stop() }
    }
}

/** Controls that show on a tap and fade a few seconds later; never while there's nothing to watch, or with TalkBack on. */
@Stable
private class AutoHide {
    var shown by mutableStateOf(true)
    var pokes by mutableIntStateOf(0)
    fun toggle() { shown = !shown; pokes++ }
    fun poke() { pokes++ }
}

/** The controls, whether they're showing, and whether they can hide at all. */
private data class AutoHideState(val auto: AutoHide, val visible: Boolean, val canHide: Boolean)

@Composable
private fun rememberAutoHide(playing: Boolean): AutoHideState {
    val h = remember { AutoHide() }
    val ctx = LocalContext.current
    val am = remember(ctx) { ctx.getSystemService(android.view.accessibility.AccessibilityManager::class.java) }
    var exploring by remember { mutableStateOf(am?.isTouchExplorationEnabled == true) }
    DisposableEffect(am) {
        val l = android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener { exploring = it }
        am?.addTouchExplorationStateChangeListener(l)
        onDispose { am?.removeTouchExplorationStateChangeListener(l) }
    }
    val timeout = LocalAccessibilityManager.current
        ?.calculateRecommendedTimeoutMillis(3_500, containsIcons = true, containsText = true, containsControls = true) ?: 3_500
    val canHide = playing && !exploring
    LaunchedEffect(canHide) { if (!canHide) h.shown = true }
    LaunchedEffect(h.shown, h.pokes, canHide) {
        if (h.shown && canHide) { kotlinx.coroutines.delay(timeout); h.shown = false }
    }
    return AutoHideState(h, h.shown || !canHide, canHide)
}

/**
 * The stream filling the screen, bars hidden. One way out (top left, or Back), sound and stop
 * together bottom right; all of it shows on a tap and fades away again, so the picture is all
 * there is.
 */
@Composable
private fun FullscreenPlayer(plugin: VillaNetPlugin, host: PluginHost, snap: VnSnapshot?, s: VnStream, onExit: () -> Unit) {
    val api = plugin.api ?: return
    val player = plugin.player ?: return
    val muted by player.mutedState.collectAsState()
    val state by player.state.collectAsState()
    val (auto, visible, canHide) = rememberAutoHide(state == WhepState.Playing)
    val activity = LocalContext.current as? android.app.Activity
    DisposableEffect(activity) {
        val ctl = activity?.window?.let { w -> androidx.core.view.WindowCompat.getInsetsController(w, w.decorView) }
        ctl?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        ceo.villa.matty.ui.StatusBarScrim.suppressed++
        onDispose {
            ceo.villa.matty.ui.StatusBarScrim.suppressed--
            ctl?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }
    val poster = @Composable { StreamThumb(api, host, s, snap?.livePreviews != false, Modifier.fillMaxSize(), badges = false) }
    Box(
        // Solid (nothing beneath takes a touch); a tap shows or hides the controls when they can hide.
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {}
            .then(if (canHide) Modifier.clickable(interactionSource = null, indication = null, onClickLabel = if (visible) "Hide controls" else "Show controls") { auto.toggle() } else Modifier),
    ) {
        StreamVideo(plugin, s, poster, Modifier.fillMaxSize())
        AnimatedVisibility(visible, enter = fadeIn(tween(150)), exit = fadeOut(tween(250)), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(112.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent))))
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(112.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000)))))
                Row(
                    Modifier.windowInsetsPadding(WindowInsets.displayCutout).padding(start = 12.dp, end = 16.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OverlayButton(Icons.AutoMirrored.Outlined.ArrowBack, "Exit full screen", onExit)
                    Spacer(Modifier.width(12.dp))
                    StreamAvatar(api, host, s, 32.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(s.name, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!s.paused && !s.alwaysOn) { Box(Modifier.size(6.dp).background(VillaNetRed, CircleShape)); Spacer(Modifier.width(5.dp)) }
                            Text(
                                listOfNotNull(
                                    if (s.paused) "Paused" else if (s.alwaysOn) "24/7" else "LIVE",
                                    "${s.viewers} watching".takeIf { s.viewers > 0 },
                                    s.motd.ifBlank { null },
                                ).joinToString(" · "),
                                color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Row(
                    Modifier.align(Alignment.BottomEnd)
                        .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.navigationBarsIgnoringVisibility))
                        .padding(16.dp)
                        .height(48.dp).clip(RoundedCornerShape(24.dp)).background(ControlGlass).pointerInput(Unit) {}.padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PillIconButton(if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp, if (muted) "Unmute" else "Mute", tint = Color.White) {
                        player.muted = !muted; auto.poke()
                    }
                    PillIconButton(Icons.Outlined.Close, "Stop watching", tint = Color.White) { plugin.stop() }
                }
            }
        }
    }
}

@Composable
private fun OverlayButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(ControlGlass).clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
}

// ---- plans --------------------------------------------------------------------------------

private fun planTime(ms: Long): String {
    val d = Date(ms)
    return planDay(ms, System.currentTimeMillis(), java.time.ZoneId.systemDefault()) + " · " + DateFormat.getTimeInstance(DateFormat.SHORT).format(d)
}

/**
 * The day a plan is on, by the calendar where the phone is: "Today", "Tomorrow", the weekday for
 * the rest of the coming week, a date beyond (and for anything already past). By calendar days,
 * not by hours from now: 23:30 tonight is today, 00:30 is tomorrow, though an hour apart.
 */
internal fun planDay(ms: Long, now: Long, zone: java.time.ZoneId): String {
    val day = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (val ahead = java.time.temporal.ChronoUnit.DAYS.between(today, day)) {
        0L -> "Today"
        1L -> "Tomorrow"
        in 2L..6L -> day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
        else -> ceo.villa.matty.ui.chat.dateLabel(ms)
    }
}

@Composable
private fun PlanChip(plugin: VillaNetPlugin, p: VnPlan) {
    val c = Matty.colors
    Column(Modifier.width(248.dp).clip(Matty.CardShape).background(c.card).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (p.category == "movie") Icons.Outlined.Movie else Icons.Outlined.CalendarMonth, null, tint = c.textSecondary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(planTime(p.startsAt), color = c.textSecondary, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
        Spacer(Modifier.height(6.dp))
        Text(p.title, color = c.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("by ${p.who}", color = c.textTertiary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            JoinButton(plugin, p)
        }
    }
}

/** What a plan offers the reader, beside its name. */
internal enum class JoinPill { Yours, Going, Full, Join, Leave }

/**
 * The owner's plan is theirs. Somebody in it can always leave: leaving is open to anybody signed
 * in, so one who has since unlinked can still take their name off. Joining is for linked readers
 * only, and not when the plan is [full] by the server's own count of the places taken (never the
 * names counted here): the Join it would refuse isn't offered. Anybody else sees who's going.
 */
internal fun joinPillOf(mine: Boolean, linked: Boolean, full: Boolean, joined: Boolean): JoinPill = when {
    mine -> JoinPill.Yours
    joined -> JoinPill.Leave
    !linked -> JoinPill.Going
    full -> JoinPill.Full
    else -> JoinPill.Join
}

@Composable
private fun JoinButton(plugin: VillaNetPlugin, p: VnPlan) {
    val me by plugin.access.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val myId = plugin.repo?.me?.collectAsState()?.value?.id.orEmpty()
    val joined = myId.isNotEmpty() && p.joined.orEmpty().any { it.userId == myId }
    val mine = myId.isNotEmpty() && p.userId == myId
    when (joinPillOf(mine = mine, linked = me?.linked == true, full = p.full, joined = joined)) {
        JoinPill.Yours -> Text("Your plan", color = Matty.colors.textSecondary, style = MaterialTheme.typography.labelLarge)
        JoinPill.Going -> Text("${p.joined.orEmpty().size} going", color = Matty.colors.textSecondary, style = MaterialTheme.typography.labelLarge)
        JoinPill.Full -> Text("Full", color = Matty.colors.textSecondary, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.semantics { contentDescription = "${p.title} is full" })
        JoinPill.Join, JoinPill.Leave -> PillButton(if (joined) "In ✓" else "Join", primary = !joined, height = 36.dp, loading = busy, modifier = Modifier.minimumInteractiveComponentSize()) {
            busy = true
            val repo = plugin.repo
            scope.launch {
                repo?.runAction(if (joined) "Couldn't leave the plan" else "Couldn't join the plan") {
                    plugin.api?.joinPlan(p.id, !joined)
                    // The change is pushed; this only asks whether it has landed (nearly always
                    // "unchanged"), for the day the socket is down.
                    plugin.refreshStreams()
                }
                busy = false
            }
        }
    }
}

@Composable
private fun PlansTab(plugin: VillaNetPlugin, snap: VnSnapshot?, snapError: String?, highlight: String?, top: Dp, bottom: Dp) {
    if (snap == null) { SnapshotPending(plugin, snapError, Icons.Outlined.CalendarMonth, top); return }
    val plans = snap.schedule.sortedBy { it.startsAt }
    if (plans.isEmpty()) { PanelMessage(Icons.Outlined.CalendarMonth, "Nothing planned", "Plans made in ${plugin.displayName()} on the web show up here.", top); return }
    val c = Matty.colors
    val state = rememberLazyStaggeredGridState()
    // Opened from a plan posted in a chat: that one, scrolled to.
    LaunchedEffect(highlight) { plans.indexOfFirst { it.id == highlight }.takeIf { it >= 0 }?.let { state.scrollToItem(it) } }
    // One column upright, two side by side when there's room.
    LazyVerticalStaggeredGrid(
        StaggeredGridCells.Adaptive(320.dp), state = state,
        contentPadding = PaddingValues(start = 16.dp, top = top, end = 16.dp, bottom = bottom),
        verticalItemSpacing = 12.dp, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(plans, key = { it.id }) { p ->
            MattyCard(Modifier.fillMaxWidth().border(if (p.id == highlight) 2.dp else 0.dp, if (p.id == highlight) VillaNetRedOnBlack else Color.Transparent, Matty.CardShape)) {
                Text(planTime(p.startsAt), color = VillaNetRedOnBlack, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Text(p.title, color = c.title, style = MaterialTheme.typography.titleMedium)
                Text("${p.who} · ${p.category.replaceFirstChar { it.uppercase() }}", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                p.media?.takeIf { it.overview.isNotBlank() }?.let { Text(it.overview, color = c.textTertiary, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    p.joined.orEmpty().take(6).forEachIndexed { i, w ->
                        Avatar(if (w.userId.isNotEmpty()) plugin.repo?.avatarUrl(w.userId) else null, w.who, 26.dp,
                            modifier = Modifier.offset(x = (-8 * i).dp).border(2.dp, c.card, CircleShape))
                    }
                    Spacer(Modifier.weight(1f))
                    JoinButton(plugin, p)
                }
            }
        }
    }
}

// ---- boards -------------------------------------------------------------------------------

@Composable
private fun BoardsTab(plugin: VillaNetPlugin, snap: VnSnapshot?, snapError: String?, top: Dp, bottom: Dp) {
    // The boards are not in the snapshot: the plugin reads them as the panel opens, and again
    // each minute while this tab is on screen, since their seconds tick.
    val held by plugin.boards.collectAsState()
    DisposableEffect(plugin) {
        plugin.boardsShown(true)
        onDispose { plugin.boardsShown(false) }
    }
    if (snap == null) { SnapshotPending(plugin, snapError, Icons.Outlined.EmojiEvents, top); return }
    val boards = held.boards
    if (boards == null) {
        val failed = held.error
        if (failed == null) CenteredLoading(Modifier.padding(top = top))
        else PanelMessage(Icons.Outlined.EmojiEvents, "The boards aren't loading", plugin.repo?.failureText("Couldn't load the boards", failed) ?: "Couldn't load the boards", top) {
            PillButton("Try again") { plugin.reloadBoards() }
        }
        return
    }
    var month by rememberSaveable { mutableStateOf(true) }
    val list = if (month) boards.month else boards.allTime
    val c = Matty.colors
    // A readable column, centred when the window is wide.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 640.dp).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, top = top, end = 16.dp, bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                SegmentedPill(
                    listOf(boards.monthLabel.ifBlank { "This month" }, "All time"), selected = if (month) 0 else 1,
                    onSelect = { month = it == 0 }, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp).width(260.dp),
                )
            }
            if (list.isEmpty()) item { EmptyState(Icons.Outlined.EmojiEvents, "No airtime yet", "Stream on Broadcast Box to get on the board.") }
            items(list) { l ->
                Row(Modifier.fillMaxWidth().clip(Matty.RowShape).background(if (l.rank <= 3) c.card else Color.Transparent).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(when (l.rank) { 1 -> "🥇"; 2 -> "🥈"; 3 -> "🥉"; else -> "${l.rank}" }, fontSize = 18.sp, color = c.textSecondary, modifier = Modifier.width(36.dp))
                    Avatar(if (l.userId.isNotEmpty()) plugin.repo?.avatarUrl(l.userId) else null, l.title, 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(l.title, color = c.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1)
                    // The Twitch part is already in the total (twitchSeconds says how much of it).
                    Text(hours(l.seconds), color = c.textSecondary, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

private fun hours(sec: Long): String = if (sec >= 3600) "%.1f h".format(sec / 3600f) else "${sec / 60} min"

// ---- clips --------------------------------------------------------------------------------

@Composable
private fun ClipsTab(plugin: VillaNetPlugin, host: PluginHost, top: Dp, bottom: Dp, onOpen: (String) -> Unit) {
    // The plugin's one copy of the list, which the viewer and the cards in chats share. Coming to
    // this tab reads it only when the clips have changed since it was last read (the snapshot
    // says when), and while the tab is here it's read again whenever they do.
    val held by plugin.clipList.collectAsState()
    var topVoted by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(plugin) {
        plugin.clipsShown(true)
        onDispose { plugin.clipsShown(false) }
    }
    val list = held.clips
    if (list == null) {
        val failed = held.error
        if (failed == null) CenteredLoading(Modifier.padding(top = top))
        else PanelMessage(Icons.Outlined.Movie, "Clips aren't loading", host.repo.failureText("Couldn't load clips", failed), top) {
            PillButton("Try again") { plugin.reloadClips() }
        }
        return
    }
    if (list.isEmpty()) { PanelMessage(Icons.Outlined.Movie, "No clips yet", "Clip the best moments of a stream from ${plugin.displayName()} on the web; they land here.", top); return }
    val sorted = if (topVoted) list.sortedByDescending { it.upvotes } else list
    // Rows of equal-height tiles, as many columns as the window holds (2 upright, 3 on a phone on
    // its side), whatever the number of clips: the name sits right under the title.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gap = 12.dp
        val minTile = when {
            maxWidth < 600.dp -> 150.dp     // upright phone: 2 columns from 344dp
            maxHeight < 480.dp -> 210.dp    // a phone on its side: 3 columns
            else -> 240.dp                  // tablet or large window
        }
        val cols = ((maxWidth - 32.dp + gap) / (minTile + gap)).toInt().coerceIn(1, 6)
        val rows = remember(sorted, cols) { sorted.chunked(cols) }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, top = top, end = 16.dp, bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "sort") {
                SegmentedPill(listOf("New", "Top"), selected = if (topVoted) 1 else 0, onSelect = { topVoted = it == 1 },
                    modifier = Modifier.padding(top = 4.dp).width(176.dp))
            }
            items(rows, key = { r -> r.first().id }) { row ->
                Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { clip -> ClipTile(plugin, clip, Modifier.weight(1f).fillMaxHeight()) { onOpen(clip.id) } }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ClipTile(plugin: VillaNetPlugin, clip: VnClip, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Matty.colors
    // Picture and words on the canvas, like the stream tiles; the words inset a little.
    Column(modifier.clip(TileShape).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(TileShape).background(Color.Black)) {
            AsyncImage(model = plugin.api?.clipThumbUrl(clip.id, clip.thumb), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            GlassBadge(duration(clip.durationMs), small = true, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
        }
        Text(clip.title.ifBlank { "Untitled clip" }, color = c.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 4.dp)) {
            Text(clip.streamer.name.ifBlank { clip.streamer.displayName }, color = c.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(1f))
            Icon(if (clip.voted) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, null, tint = if (clip.voted) VillaNetRedOnBlack else c.textTertiary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(3.dp))
            Text(clip.upvotes.toString(), color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun duration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/**
 * Where the video would be, when there is none to play: why, and what can be done about it. Small
 * enough for the 16:9 box on an upright phone, button and all.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ClipClosed(icon: ImageVector, title: String, body: String?, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.align(Alignment.Center).padding(horizontal = 20.dp, vertical = 8.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(8.dp))
        Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(2.dp))
            Text(body, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(10.dp))
            PillButton(action, height = 36.dp, modifier = Modifier.minimumInteractiveComponentSize(), onClick = onAction)
        }
    }
}

/**
 * A clip, popped out over whatever it was opened from (the Clips tab, or the chat it was shared
 * in). The thumbs up is the big button under the video, so it's one tap while it plays.
 */
@Composable
internal fun ClipViewer(plugin: VillaNetPlugin, host: PluginHost, clipId: String, onUnlock: () -> Unit, onClose: () -> Unit) {
    val api = plugin.api ?: return
    val c = Matty.colors
    var clip by remember { mutableStateOf<VnClip?>(null) }
    // Why there is no clip to play, when there isn't: gone, locked, or not answered. Three
    // different things to be told, and only the first is about the clip.
    var closed by remember { mutableStateOf<ClipOpen?>(null) }
    var tries by remember { mutableIntStateOf(0) }
    // Its video wouldn't play: the clip is asked about once more, to tell why (see
    // clipOpenAfterPlay). Counted, and each failure is used by the one asking it starts, so an
    // asking started by anything else (Try again, the reader's access changing) never takes an
    // old failure for its own.
    var playErrors by remember { mutableIntStateOf(0) }
    val playErrorsSeen = remember { intArrayOf(0) }
    var voting by remember { mutableStateOf(false) }
    val access by plugin.access.collectAsState()
    val mayWatch = access?.canWatch == true
    // Asked for by its id: one clip, not everybody's. Again on Try again, and when the reader's
    // access changes, so that a clip that was locked opens by itself once they unlock.
    LaunchedEffect(clipId, tries, mayWatch, playErrors) {
        val afterFailure = playErrors != playErrorsSeen[0]
        playErrorsSeen[0] = playErrors
        closed = null
        when (val opened = clipOpenAfterPlay(plugin.openClip(clipId), afterFailure)) {
            is ClipOpen.Found -> clip = opened.clip
            else -> { clip = null; closed = opened }
        }
    }
    fun vote(cl: VnClip) {
        if (voting) return
        val before = cl
        val up = !cl.voted
        // Counted straight away; the server's answer then settles the real total.
        clip = cl.copy(voted = up, upvotes = (cl.upvotes + if (up) 1 else -1).coerceAtLeast(0))
        voting = true
        host.repo.launchAction("Couldn't vote") {
            try {
                val v = plugin.vote(cl.id, up)
                clip = clip?.copy(voted = v.voted, upvotes = v.upvotes)
            } catch (e: Exception) {
                clip = before
                throw e
            } finally {
                voting = false
            }
        }
    }
    // Movable, so turning the phone (side by side <-> stacked) carries the same player across
    // instead of starting the clip over.
    val video = remember(api) {
        androidx.compose.runtime.movableContentOf { m: Modifier ->
            Box(m.aspectRatio(16f / 9f).background(Color.Black)) {
                val cl = clip
                when (val why = closed) {
                    null -> if (cl != null) api.clipVideoUrl(cl.id).let { url ->
                        VideoPlayer(url, api.mediaHeaders(url), Modifier.fillMaxSize(), startMs = cl.leadMs, onError = {
                            // The player that failed leaves at once: the wait for why is a
                            // spinner, not a black box.
                            clip = null
                            playErrors++
                        })
                    } else CenteredLoading(Modifier.align(Alignment.Center))
                    is ClipOpen.Found -> {}
                    // The server's own sentence when it sent one; the app's words only for none.
                    is ClipOpen.Gone -> ClipClosed(Icons.Outlined.Movie, why.words ?: "This clip is gone", if (why.words == null) "It expired or was deleted." else null)
                    is ClipOpen.Locked -> ClipClosed(Icons.Outlined.Lock, why.words ?: clipLockedWords(nameOf(access)), null, "Unlock", onUnlock)
                    is ClipOpen.Failed -> ClipClosed(Icons.Outlined.Movie, "This clip didn't load", why.words, "Try again") { tries++ }
                }
            }
        }
    }
    val header: @Composable () -> Unit = {
        val cl = clip
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(cl?.title?.ifBlank { "Untitled clip" } ?: "Clip", color = c.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (cl != null) Text("${cl.streamer.name} · clipped by ${cl.clipper.name}", color = c.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val channelId = host.repo.activeChannelId.value
            if (cl != null && cl.canShare && channelId != null) {
                PillIconButton(Icons.Outlined.Share, "Share to this channel") {
                    val repo = host.repo
                    // On the session's scope, so closing the viewer can't cut the share short.
                    repo.launchAction("Couldn't share the clip") {
                        val shared = api.shareClip(cl.id, channelId)
                        repo.emitNotice(when {
                            !shared.repeat -> "Shared to the channel"
                            shared.postId.isNotEmpty() && repo.postStillThere(shared.postId) -> "Already shared here a moment ago"
                            // VillaNET answers a repeat within a minute with the earlier post, even a deleted one.
                            else -> "You shared this clip here less than a minute ago. You can share it again in a minute."
                        })
                    }
                }
            }
            PillIconButton(Icons.Outlined.Close, "Close", onClick = onClose)
        }
    }
    val thumbs: @Composable () -> Unit = {
        clip?.let { cl -> ThumbsUpButton(cl.voted, cl.upvotes, enabled = cl.canVote, busy = voting) { vote(cl) } }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        // Sized to the window, so the thumbs up is always on screen: beside the video when the
        // window is wide (a phone on its side), under it otherwise, the video shrinking to fit.
        // Wraps its card (not the whole window), so a tap beside the card still closes it.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.padding(12.dp)) {
            val panel = 300.dp
            if (maxWidth > maxHeight && maxWidth >= 600.dp) {
                val videoWidth = minOf(maxWidth - panel, maxHeight * (16f / 9f))
                Row(Modifier.clip(Matty.DialogShape).background(c.pill)) {
                    video(Modifier.width(videoWidth))
                    Column(
                        Modifier.width(panel).height(videoWidth * (9f / 16f)).padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        header()
                        Box(Modifier.padding(end = 8.dp)) { thumbs() }
                    }
                }
            } else {
                // Room under the video for the title row and the button (about 170dp).
                val width = minOf(maxWidth, 720.dp, (maxHeight - 170.dp).coerceAtLeast(120.dp) * (16f / 9f))
                Column(Modifier.width(width).clip(Matty.DialogShape).background(c.pill)) {
                    video(Modifier.fillMaxWidth())
                    Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 16.dp)) {
                        header()
                        if (clip != null) {
                            Spacer(Modifier.height(14.dp))
                            Box(Modifier.padding(end = 8.dp)) { thumbs() }
                        }
                    }
                }
            }
        }
    }
}

/** The clip viewer's one big button: VillaNET red once you've given it a thumbs up. */
@Composable
private fun ThumbsUpButton(voted: Boolean, count: Int, enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    val c = Matty.colors
    val settings by LocalContext.current.container.settings.state.collectAsState()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val bg by androidx.compose.animation.animateColorAsState(if (voted) VillaNetRed else c.selected, label = "thumbBg")
    val fg = if (voted) Color.White else c.title
    val pop = remember { androidx.compose.animation.core.Animatable(1f) }
    val scope = rememberCoroutineScope()
    Row(
        Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(32.dp)).background(bg)
            .clickable(enabled = enabled, onClickLabel = if (voted) "Take back your thumbs up" else "Give it a thumbs up") {
                // The last tap is still on its way to the server: this one would be dropped.
                if (busy) return@clickable
                if (settings.haptics) haptics.performHapticFeedback(
                    if (voted) androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOff else androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOn)
                if (!voted) scope.launch {
                    pop.snapTo(0.7f)
                    pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.35f, stiffness = 500f))
                }
                onClick()
            }
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(if (voted) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, null, tint = fg,
            modifier = Modifier.size(30.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value })
        Spacer(Modifier.width(12.dp))
        Text("Thumbs up", color = fg, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        androidx.compose.animation.AnimatedContent(count, label = "thumbCount", transitionSpec = {
            val dir = if (targetState > initialState) 1 else -1
            (androidx.compose.animation.slideInVertically { -it * dir } + fadeIn()) togetherWith (androidx.compose.animation.slideOutVertically { it * dir } + fadeOut())
        }) { n ->
            Text(n.toString(), color = fg.copy(alpha = 0.85f), style = MaterialTheme.typography.titleMedium)
        }
    }
}

// ---- post cards ---------------------------------------------------------------------------

@Composable
fun ClipPostCard(plugin: VillaNetPlugin, post: Post, host: PluginHost) {
    val c = Matty.colors
    val id = post.prop("villanet_clip_id").orEmpty()
    val me by plugin.access.collectAsState()
    val cards by plugin.cards.collectAsState()
    val mayWatch = me?.canWatch == true
    // While it's on screen its clip is asked about, with the other cards' in one request, and
    // again when the clips change (a vote, a new title). Only for a reader who may watch.
    // A card whose id can't be a clip's (made while VillaNET wasn't there to check it) is drawn
    // from the post alone, and asks and opens nothing.
    val real = isClipId(id)
    DisposableEffect(plugin, id, mayWatch) {
        val asks = real && mayWatch
        if (asks) plugin.cardShown(id, true)
        onDispose { if (asks) plugin.cardShown(id, false) }
    }
    val card = cards[id]
    // The card in hand says when its clip goes: it turns at that moment, without asking again.
    val expired by androidx.compose.runtime.produceState(cardExpired(card, System.currentTimeMillis()), card?.expiresAt) {
        value = cardExpired(card, System.currentTimeMillis())
        val at = card?.expiresAt ?: 0
        if (at > 0 && !value) {
            kotlinx.coroutines.delay((at - System.currentTimeMillis()).coerceAtLeast(0))
            value = true
        }
    }
    // Gone: the server's mark on the post, or, for a reader who is given cards, its answer that
    // the clip has gone or the card's own word that it has expired.
    val gone = post.prop("villanet_clip_gone") != null || (mayWatch && ((cards.containsKey(id) && card == null) || expired))
    val title = card?.title ?: post.prop("villanet_clip_title").orEmpty()
    val durMs = card?.durationMs ?: post.prop("villanet_clip_duration_ms")?.toLongOrNull() ?: 0
    val streamer = card?.streamer?.name ?: post.prop("villanet_clip_streamer").orEmpty()
    Column(
        Modifier.widthIn(max = 340.dp).fillMaxWidth().clip(Matty.CardShape).background(c.card)
            // Pops the clip out right here over the chat, rather than leaving for the Clips tab.
            .clickable(enabled = !gone && real) { if (me?.canWatch == true) plugin.poppedClip.value = id else host.openPanel(VILLANET_ID, mapOf("clip" to id)) }
            .padding(bottom = 10.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            if (card != null && me?.canWatch == true) {
                AsyncImage(model = plugin.api?.clipThumbUrl(id, card.thumb), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else Box(Modifier.fillMaxSize().background(VillaNetRed.copy(alpha = 0.2f)))
            if (!gone) Box(Modifier.align(Alignment.Center).size(52.dp).background(Color(0x99000000), CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (me?.canWatch == true) Icons.Outlined.PlayArrow else Icons.Outlined.Lock, null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
            Text("🎬 ${nameOf(me)} clip · ${duration(durMs)}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(8.dp).background(Color(0xAA000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        Row(Modifier.padding(start = 12.dp, end = 6.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (gone) "This clip is gone" else title.ifBlank { "Untitled clip" }, color = if (gone) c.textTertiary else c.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                if (streamer.isNotBlank()) Text(streamer, color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
            if (card != null && !gone) {
                Row(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(if (card.voted) VillaNetRed.copy(alpha = 0.2f) else c.pill)
                        .clickable(enabled = card.canVote) { host.repo.launchAction("Couldn't vote") { plugin.vote(id, !card.voted) } }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (card.voted) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, "Upvote", tint = if (card.voted) VillaNetRedOnBlack else c.textSecondary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(card.upvotes.toString(), color = c.textSecondary, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun PlanPostCard(plugin: VillaNetPlugin, post: Post, host: PluginHost) {
    val c = Matty.colors
    val id = post.prop("villanet_plan_id").orEmpty()
    val snap by plugin.snapshot.collectAsState()
    val plan = snap?.schedule?.firstOrNull { it.id == id }
    val startsAt = plan?.startsAt ?: post.prop("villanet_plan_starts_at")?.toLongOrNull() ?: 0
    // Live by the server's own account of who is on the air (never their 24/7 channel).
    val liveNow = plan != null && !snap?.people?.get(plan.userId)?.live.isNullOrEmpty() && startsAt <= System.currentTimeMillis()
    Column(
        Modifier.widthIn(max = 340.dp).fillMaxWidth().clip(Matty.CardShape).background(c.card)
            .clickable { host.openPanel(VILLANET_ID, mapOf("plan" to id)) }.padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📅 ${plugin.displayName()} plan", color = c.textSecondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            if (liveNow) LiveBadge()
        }
        Spacer(Modifier.height(6.dp))
        if (plan == null) {
            Text(if (snap == null) "Loading…" else "This plan has ended or was cancelled", color = c.textTertiary, style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(plan.title, color = c.title, style = MaterialTheme.typography.titleMedium)
            Text("${planTime(plan.startsAt)} · ${plan.who}", color = VillaNetRedOnBlack, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${plan.joined.orEmpty().size} going", color = c.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                JoinButton(plugin, plan)
            }
        }
    }
}

/**
 * Discord's "Playing a game" on a profile: the game they're in on Steam, and their Steam profile.
 * Only while they're in one, and never for a reader VillaNET hasn't let in. No game art: that lives
 * on Steam's servers, which VillaNET doesn't relay.
 */
@Composable
private fun SteamPlayingCard(plugin: VillaNetPlugin, userId: String, host: PluginHost) {
    val me by plugin.access.collectAsState()
    val accounts by plugin.accounts.collectAsState()
    if (me?.canWatch != true) return
    val game = steamGameOf(accounts, userId) ?: return
    val steam = accounts?.people?.get(userId)?.steam ?: return
    val c = Matty.colors
    // Built by VillaNET from the SteamID; anything else isn't followed.
    val profile = steam.url.takeIf { it.startsWith("https://steamcommunity.com/profiles/") }
    Column(Modifier.fillMaxWidth().clip(Matty.CardShape).background(c.pill).padding(14.dp)) {
        Text("PLAYING A GAME", color = c.textSecondary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(c.selected), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.SportsEsports, null, tint = c.title, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(game.ifEmpty { "In a game" }, color = c.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (steam.name.isNotBlank()) "on Steam · ${steam.name}" else "on Steam", color = c.textSecondary,
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (profile != null) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(c.selected).clickable { host.openUrl(profile) }
                        .semantics { contentDescription = if (steam.name.isBlank()) "Their Steam profile" else "${steam.name}'s Steam profile"; role = Role.Button },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, tint = c.title, modifier = Modifier.size(18.dp)) }
            }
        }
    }
}

@Composable
fun VillaNetProfileExtras(plugin: VillaNetPlugin, userId: String, host: PluginHost) {
    val snap by plugin.snapshot.collectAsState()
    // Their record is with the boards, which the plugin asks about as a profile opens (if its
    // copy is a minute old) and keeps: most profiles open with it already in hand.
    val boards by plugin.boards.collectAsState()
    LaunchedEffect(plugin, userId) { plugin.boardsProfileOpen() }
    val c = Matty.colors
    // The card that stands for them while they're on the air: the server says which.
    val live = snap?.people?.get(userId)?.live.orEmpty()
    val s = if (live.isEmpty()) null else snap?.streams?.firstOrNull { it.handle == live }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BannerTrouble(plugin, userId)
        if (s != null) Row(
            Modifier.fillMaxWidth().clip(Matty.CardShape).background(LiveRed.copy(alpha = 0.14f))
                .clickable {
                    // Start the stream, then open the panel; the profile sheet slides away by itself.
                    // Twitch is watched on Twitch.
                    if (s.isTwitch) host.openUrl("https://www.twitch.tv/${s.twitchLogin}")
                    else { plugin.play(s.handle); host.openPanel(VILLANET_ID) }
                }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveBadge()
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Live on ${plugin.displayName()}", color = c.title, style = MaterialTheme.typography.titleSmall)
                Text(s.motd.ifBlank { s.title }, color = c.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            Text("Watch", color = VillaNetRedOnBlack, style = MaterialTheme.typography.labelLarge)
        }
        SteamPlayingCard(plugin, userId, host)
        // Trophies show whether or not the person is live.
        VillaNetAwardList(boards.boards, userId, plugin.displayName()) { id -> host.repo.avatarUrl(id) }
    }
}
