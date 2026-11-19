package ceo.villa.matty.plugins.villanet

import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import ceo.villa.matty.ui.components.PillButton
import ceo.villa.matty.ui.theme.Matty
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A picture on its way to being somebody's banner, on the crop screen: a still picked ([uri],
 * shown from [preview], cut on the phone when it is used) or a GIF ([gif], picked, or the banner
 * one has when [adjusting], sent as it is with only the part that shows). [width] by [height] are
 * its own pixels upright, as VillaNET measures them. Held by the plugin, not the screen, so
 * nothing the window does to the screen (turning, resizing) loses what was chosen.
 */
internal class BannerCropSession(
    val userId: String,
    val uri: Uri?,
    val preview: BannerPreview?,
    val gif: ByteArray?,
    val width: Int,
    val height: Int,
    val adjusting: Boolean = false,
    /** Where it opens: the banner's present crop, or null for the middle (what was sent before there was a choice). */
    val start: CropRect? = null,
    /** The `v` of the banner Adjust opened, sent with the part so it lands on no other; null for a picture just picked. */
    val v: String? = null,
) {
    /** The part chosen, as fractions of the picture; null while it is untouched. */
    var rect by mutableStateOf<CropRect?>(null)

    /** Why the last Use failed, in a sentence; the picture stays to try again or cancel. */
    var trouble by mutableStateOf<String?>(null)

    /** The part showing now. */
    val shown: CropRect get() = rect ?: start ?: middleCrop(width, height)

    /** The smallest share of the picture the window may show each way: VillaNET's for a GIF's crop, none for a still. */
    val minPart: Double get() = if (gif != null) GIF_CROP_MIN_PART else 0.0

    /**
     * Makes the part chosen the part [limits] show of it. A window that changed size since the
     * last touch (folding, split screen, a desktop window) may have moved how far it can be
     * sized up, and the screen draws the part within the new limits, so Use sends that one.
     * An untouched Adjust shows the banner's crop within these limits too: when they could not
     * size it up that far (it was chosen in a window with a smaller shorter side), the part that
     * showed is the one chosen. One shown as the profile draws it stays untouched, so nothing is sent.
     */
    fun settle(limits: CropLimits) {
        rect?.let { rect = limits.rectOf(limits.viewOf(it)); return }
        val from = start?.takeIf { adjusting } ?: return
        val view = limits.viewOf(from)
        // Compared with the scale the profile draws it at, not with the crop's own numbers: a
        // crop kept to four places is a hair off three by one, and that alone is no change.
        val drawn = cropScale(width.toDouble(), height.toDouble(), limits.frame.width, limits.frame.height, from)
        val shown = limits.rectOf(view)
        if (view.scale < drawn && wireCrop(shown) != wireCrop(from)) rect = shown
    }

    /**
     * Whether this is a session that a pick left after the profile [userId] had closed (the
     * picture was still being read), found on opening it again: not a Use still on its way
     * ([busy]), and not a failed Use, which stays with its sentence to be tried again.
     */
    fun leftByPick(userId: String, busy: Boolean): Boolean = !busy && this.userId == userId && trouble == null

    /**
     * Whether the profile [userId] draws this crop screen: one's own ([mine]), and not the one
     * [left] by a pick that was found on opening it, which goes then and is kept out of the first
     * frame too (the effect that cancels it runs only after that frame is composed).
     */
    fun showsOn(userId: String, mine: Boolean, left: BannerCropSession?): Boolean = mine && this.userId == userId && this !== left
}

/**
 * The crop screen: the whole picture on black, a three-by-one window over it with everything
 * outside it dimmed. Dragged to move, pinched (or double tapped) to size, and by the arrow keys,
 * plus and minus, or a mouse's wheel. Cancel and Back leave everything as it was; while Use is
 * on its way ([busy]) both wait.
 */
@Composable
internal fun BannerCropScreen(session: BannerCropSession, busy: Boolean, onCancel: () -> Unit, onUse: () -> Unit) {
    val c = Matty.colors
    // Outside the dialog, whose window (and all in it) a density change builds afresh: the hint stays gone.
    var touched by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = { if (!busy) onCancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) {
        // On black whatever the theme: light system bar icons, as on the full-screen photo viewer.
        val view = LocalView.current
        DisposableEffect(view) {
            (view.parent as? DialogWindowProvider)?.window?.let { w ->
                WindowCompat.getInsetsController(w, view).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
            onDispose {}
        }
        val painter = cropPainter(session)
        val hint by animateFloatAsState(if (touched) 0f else 1f, tween(400), label = "cropHint")
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val density = LocalDensity.current
            val insets = WindowInsets.safeDrawing
            val margin = with(density) { 16.dp.toPx() }
            // Room under the window for the hint, and at the bottom for the pills.
            val below = with(density) { 40.dp.toPx() }
            val controls = with(density) { 96.dp.toPx() }
            val left = insets.getLeft(density, LayoutDirection.Ltr) + margin
            val top = insets.getTop(density) + margin
            val frame = cropFrameIn(
                left.toDouble(), top.toDouble(),
                (constraints.maxWidth - left - insets.getRight(density, LayoutDirection.Ltr) - margin).toDouble(),
                (constraints.maxHeight - top - insets.getBottom(density) - controls - below).toDouble(),
            )
            // Past three times just filling the window, sized up no further than a banner's worth
            // of the picture's own pixels across the screen's shorter side: one figure however the
            // phone is held, so turning it keeps the part.
            val least = minOf(BANNER_MAX_WIDTH, with(density) { LocalConfiguration.current.smallestScreenWidthDp.dp.roundToPx() }).toDouble()
            val limits = remember(frame, session, least) { CropLimits(session.width, session.height, frame, session.minPart, least) }
            val shown = limits.viewOf(session.shown)
            // Read afresh by every gesture: a gesture's handler outlives the composition it began in.
            val now = { limits.viewOf(session.shown) }
            val change = { next: CropView ->
                touched = true
                session.rect = limits.rectOf(next)
            }
            val focus = remember { FocusRequester() }
            LaunchedEffect(focus) { runCatching { focus.requestFocus() } }
            Canvas(
                Modifier.fillMaxSize()
                    .semantics {
                        contentDescription = "The part of the picture that shows"
                        customActions = listOf(
                            CustomAccessibilityAction("Make larger") { change(limits.keyed(now(), CropKey.In)); true },
                            CustomAccessibilityAction("Make smaller") { change(limits.keyed(now(), CropKey.Out)); true },
                            CustomAccessibilityAction("Move left") { change(limits.keyed(now(), CropKey.Left)); true },
                            CustomAccessibilityAction("Move right") { change(limits.keyed(now(), CropKey.Right)); true },
                            CustomAccessibilityAction("Move up") { change(limits.keyed(now(), CropKey.Up)); true },
                            CustomAccessibilityAction("Move down") { change(limits.keyed(now(), CropKey.Down)); true },
                        )
                    }
                    .focusRequester(focus)
                    .focusable()
                    .onKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                        val k = when (e.key) {
                            Key.DirectionLeft -> CropKey.Left
                            Key.DirectionRight -> CropKey.Right
                            Key.DirectionUp -> CropKey.Up
                            Key.DirectionDown -> CropKey.Down
                            Key.Plus, Key.Equals, Key.NumPadAdd -> CropKey.In
                            Key.Minus, Key.NumPadSubtract -> CropKey.Out
                            else -> return@onKeyEvent false
                        }
                        change(limits.keyed(now(), k))
                        true
                    }
                    // The hint goes with the first touch, whatever it does.
                    .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); touched = true } }
                    .pointerInput(limits) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            change(limits.pinched(now(), centroid.x.toDouble(), centroid.y.toDouble(), zoom.toDouble(), pan.x.toDouble(), pan.y.toDouble()))
                        }
                    }
                    .pointerInput(limits) {
                        detectTapGestures(onDoubleTap = { p -> change(limits.doubleTapped(now(), p.x.toDouble(), p.y.toDouble())) })
                    }
                    .pointerInput(limits) {
                        // A mouse's wheel sizes it about the pointer, a notch a key's step.
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent()
                                if (e.type != PointerEventType.Scroll) continue
                                val ch = e.changes.firstOrNull() ?: continue
                                val notches = ch.scrollDelta.y.toDouble()
                                if (notches == 0.0) continue
                                change(limits.zoomed(now(), CROP_KEY_ZOOM.pow(-notches), ch.position.x.toDouble(), ch.position.y.toDouble()))
                                ch.consume()
                            }
                        }
                    },
            ) {
                translate(shown.left.toFloat(), shown.top.toFloat()) {
                    with(painter) { draw(Size((session.width * shown.scale).toFloat(), (session.height * shown.scale).toFloat())) }
                }
                val topLeft = Offset(frame.left.toFloat(), frame.top.toFloat())
                val windowSize = Size(frame.width.toFloat(), frame.height.toFloat())
                clipRect(frame.left.toFloat(), frame.top.toFloat(), frame.right.toFloat(), frame.bottom.toFloat(), clipOp = ClipOp.Difference) {
                    drawRect(Color.Black.copy(alpha = 0.62f))
                }
                drawRect(Color.White.copy(alpha = 0.55f), topLeft, windowSize, style = Stroke(with(density) { 1.dp.toPx() }))
            }
            Text(
                // A picture too small to size up can only be moved: the hint says no more than that.
                if (limits.sizable) "Drag and pinch" else "Drag",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.TopCenter)
                    .offset { IntOffset(0, (frame.bottom + 14.dp.toPx()).roundToInt()) }
                    .alpha(hint),
            )
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(insets).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // A failure in the notice's own look: the notice itself shows under this screen.
                session.trouble?.let { text ->
                    Text(
                        text, color = c.title, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.widthIn(max = 560.dp).clip(RoundedCornerShape(20.dp)).background(c.selected).padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton("Cancel", enabled = !busy, onClick = onCancel)
                    PillButton("Use", primary = true, loading = busy, onClick = { session.settle(limits); onUse() })
                }
            }
        }
    }
}

/** The picture drawn on the crop screen: the still as decoded for it, or the GIF playing. */
@Composable
private fun cropPainter(session: BannerCropSession): Painter {
    val gif = session.gif
    if (gif == null) {
        val bitmap = session.preview!!.bitmap
        return remember(bitmap) { BitmapPainter(bitmap.asImageBitmap(), filterQuality = FilterQuality.High) }
    }
    val ctx = LocalPlatformContext.current
    // At its own size: a GIF is at most 2400 pixels a side, and is drawn larger than the screen when sized up.
    val request = remember(gif) { ImageRequest.Builder(ctx).data(gif).size(coil3.size.Size.ORIGINAL).build() }
    return rememberAsyncImagePainter(request, filterQuality = FilterQuality.High)
}
