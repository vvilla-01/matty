package ceo.villa.matty.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import ceo.villa.matty.ui.theme.Matty
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

enum class PanelValue { Start, Center, End }

@Stable
class PanelsState(initial: PanelValue) {
    internal val offset = Animatable(0f)
    internal var startPx by mutableFloatStateOf(1f)
    internal var endPx by mutableFloatStateOf(1f)
    var target by mutableStateOf(initial)
        private set

    private fun positionOf(value: PanelValue) = when (value) { PanelValue.Start -> startPx; PanelValue.Center -> 0f; PanelValue.End -> -endPx }

    val progressStart: Float get() = (offset.value / startPx).coerceIn(0f, 1f)
    val progressEnd: Float get() = (-offset.value / endPx).coerceIn(0f, 1f)

    /** Slides to [value]. [target] changes before the first suspension, so a caller that starts
     *  this undispatched has the new target in the same frame. */
    suspend fun open(value: PanelValue) {
        target = value
        offset.animateTo(positionOf(value), spring(dampingRatio = 0.86f, stiffness = 520f))
    }

    /** Jumps straight to [value], for when the panels weren't on screen to watch them move. */
    suspend fun snap(value: PanelValue) {
        target = value
        offset.snapTo(positionOf(value))
    }

    /** Lines the offset up with [target] (the panels just appeared, or their widths changed). */
    internal suspend fun place() = offset.snapTo(positionOf(target))

    internal suspend fun settle(velocity: Float, endEnabled: Boolean) {
        val x = offset.value
        val next = when {
            velocity > 900f -> if (x < -8f) PanelValue.Center else PanelValue.Start
            velocity < -900f -> if (x > 8f) PanelValue.Center else if (endEnabled) PanelValue.End else PanelValue.Center
            x > startPx / 2 -> PanelValue.Start
            x < -endPx / 2 && endEnabled -> PanelValue.End
            else -> PanelValue.Center
        }
        open(next)
    }
}

@Composable
fun rememberPanelsState(initial: PanelValue = PanelValue.Center) = remember { PanelsState(initial) }

/**
 * Discord's mobile layout: the chat sits on top and slides right to reveal the team rail and
 * channel list, or left to reveal the member list. A strip of chat stays peeking so a tap
 * brings it back.
 */
@Composable
fun OverlappingPanels(
    state: PanelsState,
    endEnabled: Boolean,
    start: @Composable () -> Unit,
    center: @Composable () -> Unit,
    end: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier.fillMaxSize().background(Matty.colors.background)) {
        val density = LocalDensity.current
        val peek = 64.dp
        val startW = maxWidth - peek
        val endW = (maxWidth - peek).coerceAtMost(360.dp)
        with(density) {
            state.startPx = startW.toPx()
            state.endPx = endW.toPx()
        }
        // Each time the panels appear (first launch, or the window narrowing again after the
        // side-by-side layout), and whenever their widths change, they go where they're meant
        // to be now: the target may have changed while they weren't on screen.
        androidx.compose.runtime.LaunchedEffect(state.startPx, state.endPx) { state.place() }
        val drag = rememberDraggableState { delta ->
            val min = if (endEnabled) -state.endPx else 0f
            scope.launch { state.offset.snapTo((state.offset.value + delta).coerceIn(min, state.startPx)) }
        }
        val x = state.offset.value
        Box(Modifier.fillMaxHeight().width(startW).zIndex(if (x >= 0) 1f else 0f)) { if (x > 0.5f || state.target == PanelValue.Start) start() }
        Box(Modifier.fillMaxHeight().width(endW).align(Alignment.TopEnd).zIndex(if (x < 0) 1f else 0f)) { if (x < -0.5f || state.target == PanelValue.End) end() }
        val moved = abs(x) > 0.5f
        val corner = if (moved) 24.dp else 0.dp
        Box(
            Modifier.fillMaxSize().zIndex(2f)
                .offset { IntOffset(x.roundToInt(), 0) }
                .graphicsLayer {
                    val p = maxOf(state.progressStart, state.progressEnd)
                    val s = 1f - 0.04f * p
                    scaleX = s; scaleY = s
                }
                .clip(RoundedCornerShape(corner))
                .background(Matty.colors.background)
                .draggable(drag, Orientation.Horizontal, onDragStopped = { v -> scope.launch { state.settle(v, endEnabled) } }),
        ) {
            center()
            if (moved && (state.target != PanelValue.Center || abs(x) > 40f)) {
                // The peeking strip: tap to close.
                Box(
                    Modifier.fillMaxSize().background(Matty.colors.background.copy(alpha = 0.25f * maxOf(state.progressStart, state.progressEnd)))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            scope.launch { state.open(PanelValue.Center) }
                        }
                        .draggable(drag, Orientation.Horizontal, onDragStopped = { v -> scope.launch { state.settle(v, endEnabled) } })
                )
            }
        }
    }
}

/** The channel column's width in the wide (tablet, foldable, landscape) layout. */
val WideStartWidth = 360.dp

/** Tablets and foldables: everything side by side, no sliding. */
@Composable
fun SideBySidePanels(
    start: @Composable () -> Unit,
    center: @Composable () -> Unit,
    end: (@Composable () -> Unit)?,
    showEnd: Boolean,
) {
    Row(Modifier.fillMaxSize().background(Matty.colors.background)) {
        Box(Modifier.width(WideStartWidth).fillMaxHeight()) { start() }
        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)).background(Matty.colors.background)) { center() }
        if (end != null && showEnd) Box(Modifier.width(300.dp).fillMaxHeight()) { end() }
    }
}
