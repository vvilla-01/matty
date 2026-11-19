package ceo.villa.matty.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import ceo.villa.matty.ui.LocalRepo
import ceo.villa.matty.ui.MediaItem
import ceo.villa.matty.ui.Navigator
import coil3.compose.AsyncImage

/**
 * Full-screen photos and videos. A post's media opens together at the one tapped, and a swipe
 * left or right moves through the rest. Photos pinch or double-tap to zoom (a zoomed photo pans
 * instead of swiping on); a single tap hides everything but the picture.
 */
@Composable
fun MediaViewer(nav: Navigator, items: List<MediaItem>, start: Int) {
    if (items.isEmpty()) return
    val repo = LocalRepo.current
    val pager = rememberPagerState(initialPage = start.coerceIn(0, items.lastIndex)) { items.size }
    // One tap hides everything but the picture (top bar and system bars); another brings it back.
    var chrome by remember { mutableStateOf(true) }
    // The photo on screen is zoomed in: one-finger drags pan it rather than turning the page.
    var zoomed by remember { mutableStateOf(false) }
    val activity = LocalContext.current as? android.app.Activity
    DisposableEffect(activity) {
        // Always on black: light icons here, even in the light theme; put back on the way out.
        val ctl = activity?.window?.let { w -> WindowCompat.getInsetsController(w, w.decorView) }
        val lightStatus = ctl?.isAppearanceLightStatusBars
        val lightNav = ctl?.isAppearanceLightNavigationBars
        ctl?.isAppearanceLightStatusBars = false
        ctl?.isAppearanceLightNavigationBars = false
        ceo.villa.matty.ui.StatusBarScrim.suppressed++
        onDispose {
            ceo.villa.matty.ui.StatusBarScrim.suppressed--
            ctl?.show(WindowInsetsCompat.Type.systemBars())
            lightStatus?.let { ctl.isAppearanceLightStatusBars = it }
            lightNav?.let { ctl.isAppearanceLightNavigationBars = it }
        }
    }
    LaunchedEffect(chrome) {
        activity?.window?.let { w ->
            val ctl = WindowCompat.getInsetsController(w, w.decorView)
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (chrome) ctl.show(WindowInsetsCompat.Type.systemBars()) else ctl.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            pager, Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            userScrollEnabled = !zoomed,
            key = { "$it:" + items[it].url },
        ) { page ->
            val item = items[page]
            // Only the page that has come to rest plays or keeps a zoom.
            val onScreen = pager.settledPage == page
            if (item.video) {
                VideoPage(item, playing = onScreen, onControlsVisible = { if (onScreen) chrome = it })
            } else {
                ZoomablePhoto(
                    item, onScreen,
                    pagerMoving = { pager.isScrollInProgress },
                    onZoomed = { z -> if (pager.settledPage == page) zoomed = z },
                    onTap = { chrome = !chrome },
                )
            }
        }
        val current = items[pager.currentPage.coerceIn(0, items.lastIndex)]
        val onServer = repo.client.isSameOrigin(current.url)
        AnimatedVisibility(chrome, modifier = Modifier.align(Alignment.TopStart),
            enter = fadeIn() + slideInVertically { -it / 2 }, exit = fadeOut() + slideOutVertically { -it / 2 }) {
            FloatingTopBar(
                current.title.ifBlank { if (current.video) "Video" else "Image" },
                subtitle = if (items.size > 1) "${pager.currentPage + 1} of ${items.size}" else null,
                onBack = { nav.pop() },
            ) {
                Pill {
                    // Only real Mattermost attachments can be saved with your session; anything else opens in the browser.
                    val fileId = current.fileId
                    if (fileId != null) PillIconButton(Icons.Outlined.Download, "Download") {
                        nav.downloadAttachment(fileId, current.title.ifBlank { "matty-file" }, current.mime)
                    } else if (!onServer) PillIconButton(Icons.Outlined.OpenInBrowser, "Open in browser") { nav.openUrl(current.url) }
                }
            }
        }
        if (items.size > 1) {
            // Not over a video, whose own controls (and seek bar) live down there.
            AnimatedVisibility(chrome && !current.video, modifier = Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
                PageDots(items.size, pager.currentPage)
            }
        }
    }
}

/** Where you are in the post's photos, MarkOut-style: the current one stretched into a pill. */
@Composable
private fun PageDots(count: Int, current: Int) {
    Row(
        Modifier.navigationBarsPadding().padding(bottom = 18.dp).background(Color(0x99000000), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics { contentDescription = "${current + 1} of $count" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val width by animateDpAsState(if (i == current) 18.dp else 6.dp, label = "dot")
            val color by animateColorAsState(if (i == current) Color.White else Color.White.copy(alpha = 0.45f), label = "dotColor")
            Box(Modifier.height(6.dp).width(width).background(color, CircleShape))
        }
    }
}

@Composable
private fun VideoPage(item: MediaItem, playing: Boolean, onControlsVisible: (Boolean) -> Unit) {
    val repo = LocalRepo.current
    if (playing) {
        val headers = ceo.villa.matty.data.net.TransportPolicy.mediaHeaders(item.url, repo.client.origin, repo.client.token)
        VideoPlayer(item.url, headers, Modifier.fillMaxSize(), onControlsVisible = onControlsVisible)
    } else {
        // Not the page on screen: a still, so a video beside the current photo neither plays nor
        // holds a player.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (item.poster != null) AsyncImage(model = item.poster, contentDescription = item.title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Box(Modifier.size(64.dp).background(Color(0x99000000), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.PlayArrow, null, tint = Color.White, modifier = Modifier.size(36.dp))
            }
        }
    }
}

@Composable
private fun ZoomablePhoto(
    item: MediaItem,
    onScreen: Boolean,
    pagerMoving: () -> Boolean,
    onZoomed: (Boolean) -> Unit,
    onTap: () -> Unit,
) {
    val repo = LocalRepo.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // Swiped away: back to fitting the screen for when it comes round again.
    LaunchedEffect(onScreen) { if (!onScreen) { scale = 1f; offset = Offset.Zero } }
    val isZoomed = scale > 1f
    LaunchedEffect(isZoomed, onScreen) { if (onScreen) onZoomed(isZoomed) }

    fun clamp(o: Offset, s: Float, size: IntSize): Offset {
        val maxX = (s - 1f) * size.width / 2f
        val maxY = (s - 1f) * size.height / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }
    AsyncImage(
        model = repo.client.proxiedImage(item.url, repo.imageProxyEnabled), contentDescription = item.title, contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
            .pointerInput(Unit) {
                // Pinch to zoom, and pan once zoomed. A one-finger drag on a photo at its normal
                // size is left alone, so it turns the page.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if ((fingers >= 2 && !pagerMoving()) || scale > 1f) {
                            val s = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            offset = if (s == 1f) Offset.Zero else clamp(offset + event.calculatePan(), s, size)
                            scale = s
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    // A pinch let go just short of fitting the screen looks unzoomed: settle it there,
                    // or the page would stay locked against swiping at an invisible 1.0-something.
                    if (scale < 1.1f) { scale = 1f; offset = Offset.Zero }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { at ->
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero }
                        else {
                            // Zoom in on the spot tapped, not the middle.
                            val s = 2.5f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            offset = clamp((center - at) * (s - 1f), s, size)
                            scale = s
                        }
                    },
                )
            }
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
    )
}
