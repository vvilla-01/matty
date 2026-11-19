package ceo.villa.matty.ui.components

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import ceo.villa.matty.container
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * ExoPlayer in Compose, fetching through Matty's hardened OkHttp client (TLS-only, no
 * http<->https redirects, credentials dropped on cross-origin redirects).
 *
 * [headers] carry the Mattermost session for server-hosted files and plugin media; they are
 * applied only when [url] is on the signed-in server's exact origin, whoever the caller is.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    url: String,
    headers: Map<String, String>,
    modifier: Modifier = Modifier,
    startMs: Long = 0,
    autoPlay: Boolean = true,
    loop: Boolean = false,
    /** Follows the player's own controls, so a viewer can show and hide its chrome with them. */
    onControlsVisible: ((Boolean) -> Unit)? = null,
    /** The player gave up (the file refused or missing, the connection gone): the caller can say why. */
    onError: ((androidx.media3.common.PlaybackException) -> Unit)? = null,
) {
    val context = LocalContext.current
    val container = context.container
    val player = remember(url) {
        val sameOrigin = container.sessions.current.value?.client?.isSameOrigin(url) == true
        val http = OkHttpDataSource.Factory(container.baseHttp)
            .setDefaultRequestProperties(if (sameOrigin) headers else emptyMap())
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(http))
            // Media, not a call: speaker/headphones, with audio focus handled like any video app.
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                setMediaItem(MediaItem.fromUri(url))
                repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                prepare()
                if (startMs > 0) seekTo(startMs)
                playWhenReady = autoPlay
            }
    }
    val errorTo = androidx.compose.runtime.rememberUpdatedState(onError)
    DisposableEffect(player) {
        val heard = object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { errorTo.value?.invoke(error) }
        }
        player.addListener(heard)
        onDispose { player.removeListener(heard); player.release() }
    }
    Box(modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    if (onControlsVisible != null) setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { v -> onControlsVisible(v == android.view.View.VISIBLE) }
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
