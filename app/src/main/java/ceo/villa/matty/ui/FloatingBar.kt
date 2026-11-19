package ceo.villa.matty.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import ceo.villa.matty.container
import ceo.villa.matty.plugins.MattyPlugin

/**
 * The pills floating along the bottom of a screen (the message pill, the tab pill), reported by
 * the screens showing them, so a plugin's bar (VillaNET's stream, playing while you're elsewhere)
 * floats just above them instead of over them.
 */
object BottomChrome {
    /**
     * [startWidth]: the pill sits in a column of that width at the start (the tab pill beside a
     * wide chat), [startInset] from the window's start edge (past the rail). [owner]: which
     * screen reported it, so one leaving can only take back its own.
     */
    data class Piece(val height: Dp, val startWidth: Dp?, val owner: Any, val startInset: Dp = 0.dp)

    internal val pieces = mutableStateMapOf<String, Piece>()

    internal fun set(key: String, height: Dp?, startWidth: Dp?, owner: Any, startInset: Dp = 0.dp) {
        val piece = height?.let { Piece(it, startWidth, owner, startInset) }
        if (pieces[key] == piece) return
        if (piece == null) clear(key, owner) else pieces[key] = piece
    }

    internal fun clear(key: String, owner: Any) {
        // Only its own: a screen going away mustn't remove what the one replacing it reported.
        if (pieces[key]?.owner === owner) pieces.remove(key)
    }

    /** Room the bar takes while it shows: lists under it add this to their bottom padding. */
    var barSpace by mutableStateOf(0.dp)
        internal set

    /** The bar sits in the channel column (beside a wide chat), so the chat itself needs no room for it. */
    var barInStartColumn by mutableStateOf(false)
        internal set

    /** How high the bar's top edge is above the window's bottom (0 while hidden), for the snackbar to clear. */
    var barTop by mutableStateOf(0.dp)
        internal set

    /** Composers whose custom-emoji popup stands in for the keyboard: the bar keeps out of its way too. */
    internal val popups = androidx.compose.runtime.mutableStateListOf<Any>()
}

/**
 * Reports this screen's bottom pill ([height] includes the navigation bar; null while it's slid
 * away) for as long as the screen is composed. Keys: `home:…` for the home screen's pills,
 * `thread:<rootId>` for a thread's.
 */
@Composable
fun ReportBottomChrome(key: String, height: Dp?, startWidth: Dp? = null, startInset: Dp = 0.dp) {
    val owner = remember { Any() }
    SideEffect { BottomChrome.set(key, height, startWidth, owner, startInset) }
    DisposableEffect(key) { onDispose { BottomChrome.clear(key, owner) } }
}

/** Whose pills count right now: the home screen's, an open thread's, or (elsewhere) nobody's. */
internal fun chromePrefix(nav: Navigator): String? = when (val top = nav.top) {
    null -> "home:"
    is Route.Thread -> "thread:${top.rootId}"
    else -> null
}

/** The tallest bottom pill of the screen on top (the message box, while typing), or zero. */
internal fun pillHeight(nav: Navigator): Dp =
    chromePrefix(nav)?.let { p -> BottomChrome.pieces.filterKeys { it.startsWith(p) }.values.maxOfOrNull { it.height } } ?: 0.dp

private val BarHeight = 56.dp
private val BarGap = 8.dp

/**
 * A plugin's bar for what it keeps going while you're elsewhere (VillaNET's stream): just above
 * the bottom pills of the home screen and of a thread, sliding with them. Not on other full-screen
 * views (their lists have no room kept for it), nor while typing (the keyboard has the bottom).
 */
@Composable
fun BoxScope.FloatingPluginBar(nav: Navigator) {
    val plugins by LocalContext.current.container.plugins.active.collectAsState()
    var bar: MattyPlugin? = null
    plugins.forEach { p -> key(p.id) { if (p.hasMiniBar() && bar == null) bar = p } }
    // The one on its way out still draws while it slides away.
    val last = remember { arrayOfNulls<MattyPlugin>(1) }
    if (bar != null) last[0] = bar

    val prefix = chromePrefix(nav)
    // The keyboard, or the custom-emoji popup standing in for it, has the bottom while typing.
    val typing = WindowInsets.isImeVisible || BottomChrome.popups.isNotEmpty()
    val show = bar != null && prefix != null && !typing
    val pieces = BottomChrome.pieces.filterKeys { prefix != null && it.startsWith(prefix) }.values
    val anchor = pieces.firstOrNull { it.startWidth != null }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val above = anchor?.height ?: pieces.maxOfOrNull { it.height } ?: navBottom
    // Off home and threads (or while typing) there are no pills to read: stay where they last were,
    // so the bar fades out, and back in, in place rather than sliding across the screen.
    val held = remember { arrayOfNulls<Pair<Dp, BottomChrome.Piece?>>(1) }
    val live = prefix != null && !typing
    if (live) held[0] = above to anchor
    val (at, anchorAt) = if (live) above to anchor else held[0] ?: (above to anchor)
    val lift by animateDpAsState(at + BarGap, spring(stiffness = Spring.StiffnessMediumLow), label = "barLift")
    // The room lists keep for it eases in and out with the bar, rather than jumping 64dp at once.
    val space by animateDpAsState(if (show) BarHeight + BarGap else 0.dp, spring(stiffness = Spring.StiffnessMediumLow), label = "barSpace")
    // Read here, not inside the SideEffect: a read there isn't tracked, so the eased value would
    // stop reaching the lists after the first frame.
    val spaceNow = space
    SideEffect {
        BottomChrome.barSpace = spaceNow
        BottomChrome.barInStartColumn = anchorAt?.startWidth != null
        BottomChrome.barTop = if (show) at + BarGap + BarHeight else 0.dp
    }

    // Over the very column the pill is centred in, so the two line up.
    val placement = if (anchorAt?.startWidth != null) Modifier.align(Alignment.BottomStart).padding(start = anchorAt.startInset).width(anchorAt.startWidth)
    else Modifier.align(Alignment.BottomCenter).fillMaxWidth()
    AnimatedVisibility(
        show, modifier = placement.offset { IntOffset(0, -lift.roundToPx()) },
        enter = slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it / 2 } + fadeIn(),
        exit = slideOutVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it / 2 } + fadeOut(),
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.BottomCenter) {
            last[0]?.MiniBar(nav, Modifier.widthIn(max = 480.dp).fillMaxWidth())
        }
    }
}
