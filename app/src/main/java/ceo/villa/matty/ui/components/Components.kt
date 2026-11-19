package ceo.villa.matty.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.launch
import ceo.villa.matty.ui.theme.Matty
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage

/** Draws a vertical accent bar on the start edge, without intrinsic measurement. */
fun Modifier.leftBar(color: Color, width: Dp = 4.dp): Modifier = this.drawBehind {
    if (color.alpha > 0f) drawRect(color, size = androidx.compose.ui.geometry.Size(width.toPx(), size.height))
}

// ---- avatars & presence -------------------------------------------------------------------

fun statusLabel(s: String) = when (s) { "online" -> "Online"; "away" -> "Away"; "dnd" -> "Do not disturb"; else -> "Offline" }

@Composable
fun statusColor(status: String?): Color = when (status) {
    "online" -> Matty.colors.online
    "away" -> Matty.colors.away
    "dnd" -> Matty.colors.dnd
    else -> Matty.colors.offline
}

/**
 * Round avatar with one Discord-style circle cut into the corner. The circle's colour is their
 * presence (green online, yellow away, red do not disturb, grey offline); while they're in voice
 * chat the same circle grows to hold a mic, crossed out when they're muted, or crossed
 * headphones when they're deafened. One circle, so nobody has to work out what a second one
 * means. While they speak in a voice channel, a green ring goes round the picture, as Discord's.
 */
@Composable
fun Avatar(
    url: String?,
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
    status: String? = null,
    ringColor: Color = Matty.colors.background,
    live: Boolean = false,
    /** In voice chat: the presence circle holds a mic (crossed out when muted, crossed headphones when deafened). */
    voice: ceo.villa.matty.plugins.VoiceState? = null,
    /**
     * Speaking in a voice channel right now: a green ring round the picture, cut from it by
     * [ringColor]. Null where nobody can be heard (most avatars), which keeps no animation.
     */
    speaking: Boolean? = null,
) {
    Box(modifier.size(size)) {
        val shape = CircleShape
        // Initials sit underneath; the picture covers them once it loads.
        Box(Modifier.fillMaxSize().clip(shape)) {
            Initials(name, size)
            if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (live) {
            Box(Modifier.matchParentSize().border(2.dp, Color(0xFFFF3B47), shape))
        }
        if (speaking != null) SpeakingRing(speaking, size, ringColor)
        if (status != null || voice != null) {
            val inVoice = voice != null
            val deafened = voice == ceo.villa.matty.plugins.VoiceState.Deafened
            val muted = voice == ceo.villa.matty.plugins.VoiceState.Muted
            // Big enough for the mic to read while they're in voice, and sat a little further out
            // on the edge so it covers no more of the picture than the plain dot does.
            val dot = if (inVoice) (size * 0.42f).coerceAtLeast(16.dp) else (size * 0.32f).coerceAtLeast(10.dp)
            val out = if (inVoice) dot * 0.18f else 0.dp
            // In voice with their presence not known yet: they're evidently around.
            val presence = status ?: "online"
            val label = listOfNotNull(
                status?.let { statusLabel(it) },
                if (inVoice) (if (deafened) "in voice, deafened" else if (muted) "in voice, muted" else "in voice") else null,
            ).joinToString(", ").replaceFirstChar { it.uppercase() }
            Box(
                // Heard with the picture wherever the avatar's row reads out.
                Modifier.align(Alignment.BottomEnd).offset(x = out, y = out)
                    .semantics { contentDescription = label }
                    .size(dot)
                    .background(ringColor, CircleShape)
                    .padding(dot * (if (inVoice) 0.12f else 0.18f))
                    .background(statusColor(presence), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    // Dark on the yellow of "away", where white would wash out.
                    inVoice -> Icon(
                        if (deafened) Icons.Filled.HeadsetOff else if (muted) Icons.Filled.MicOff else Icons.Filled.Mic, null,
                        tint = if (presence == "away") Color(0xE6101014) else Color.White, modifier = Modifier.size(dot * 0.52f),
                    )
                    status == "dnd" -> Box(Modifier.width(dot * 0.4f).height(dot * 0.12f).background(ringColor, RoundedCornerShape(50)))
                    status == "offline" -> Box(Modifier.size(dot * 0.28f).background(ringColor, CircleShape))
                }
            }
        }
    }
}

/**
 * [Avatar]'s speaking ring: over the picture's edge rather than round it, so the picture never
 * moves as somebody starts and stops speaking; it fades rather than blinks between words.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.SpeakingRing(speaking: Boolean, size: Dp, ringColor: Color) {
    val ring by animateFloatAsState(if (speaking) 1f else 0f, androidx.compose.animation.core.tween(if (speaking) 80 else 240), label = "speaking")
    if (ring <= 0f) return
    val width = if (size >= 40.dp) 3.dp else 2.dp
    Box(
        Modifier.matchParentSize().alpha(ring)
            .border(width, Matty.colors.online, CircleShape)
            .padding(width).border(1.5.dp, ringColor, CircleShape),
    )
}

@Composable
private fun Initials(name: String, size: Dp) {
    val hue = (name.hashCode() and 0x7fffffff) % 360
    Box(
        Modifier.fillMaxSize().background(Color.hsl(hue.toFloat(), 0.45f, 0.42f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase().ifEmpty { "?" },
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.42f).sp,
        )
    }
}

/**
 * Somebody's name colour for "colorful names": the one handed out to them on this server (see
 * [ceo.villa.matty.data.repo.handOutNameColors]), read from snapshot state so names recolour by
 * themselves; a hash into the same palette until they have one.
 */
fun nameColor(userId: String, dark: Boolean): Color {
    val palette = if (dark) ceo.villa.matty.data.repo.NamePalette.dark else ceo.villa.matty.data.repo.NamePalette.light
    val i = ceo.villa.matty.data.repo.NameColors.assigned[userId] ?: ceo.villa.matty.data.repo.fallbackNameColor(userId, palette.size)
    return Color(palette[i])
}

// ---- pills & buttons (MarkOut) ------------------------------------------------------------

/**
 * A floating pill container, MarkOut's signature chrome. It's solid, so it takes every touch
 * inside its outline: one just off a round button lands nowhere, never on a message or row
 * hidden behind the pill (touches go to the topmost thing with a tap handler under the finger).
 */
@Composable
fun Pill(modifier: Modifier = Modifier, height: Dp = 48.dp, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.height(height).clip(RoundedCornerShape(height / 2)).background(Matty.colors.pill)
            .pointerInput(Unit) {}.padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun PillIconButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = Matty.colors.icon,
    enabled: Boolean = true,
    badge: Int = 0,
    onClick: () -> Unit,
) {
    Box(
        modifier.size(width = 48.dp, height = 48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .alpha(if (enabled) 1f else 0.35f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        if (badge > 0) CountBadge(badge, Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 4.dp))
    }
}

/** MarkOut's ToolButton: 48dp circle, no ripple, the selected fill animates in. */
@Composable
fun ToolButton(icon: ImageVector, desc: String, selected: Boolean = false, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Matty.colors.selected else Color.Transparent, label = "toolBg")
    Box(
        Modifier.size(48.dp).background(bg, CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(icon, transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.7f)) togetherWith fadeOut() }, label = "icon") {
            Icon(it, null, tint = Matty.colors.icon)
        }
    }
}

/** A labelled pill button (primary CTA when [primary]). */
@Composable
fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    loading: Boolean = false,
    height: Dp = 48.dp,
    onClick: () -> Unit,
) {
    val c = Matty.colors
    val bg = if (primary) c.accent else c.selected
    val fg = if (primary) c.onAccent else c.title
    Row(
        modifier.alpha(if (enabled) 1f else 0.35f).heightIn(min = height).clip(RoundedCornerShape(height / 2)).background(bg)
            .clickable(enabled = enabled && !loading, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            LoadingIndicator(Modifier.size(28.dp), color = fg)
        } else {
            if (icon != null) {
                Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier, color: Color = Matty.colors.dnd) {
    Box(
        modifier.heightIn(min = 18.dp).background(color, RoundedCornerShape(9.dp)).padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (count > 99) "99+" else count.toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

// ---- screen chrome ------------------------------------------------------------------------

/**
 * MarkOut has no TopAppBar: controls float in pills over the canvas. This is the same band —
 * status bar padding, 16dp sides, 8dp top, 48dp controls.
 */
@Composable
fun FloatingTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = Matty.colors
    Row(
        modifier.fillMaxWidth().statusBarsPadding()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Pill { PillIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onClick = onBack) }
            Spacer(Modifier.width(10.dp))
        }
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = c.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = Matty.colors.textSecondary,
        modifier = modifier.padding(start = 8.dp, bottom = 6.dp, top = 4.dp),
    )
}

/** MarkOut's LabCard: a flat 24dp card on the canvas. */
@Composable
fun MattyCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    color: Color = Matty.colors.card,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(shape = Matty.CardShape, color = color, modifier = modifier) {
        Column(Modifier.padding(padding), content = content)
    }
}

/** MarkOut's PassRow: label, one-line hint, and a switch. */
@Composable
fun SwitchRow(label: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Matty.colors
    Row(
        Modifier.fillMaxWidth().clip(Matty.RowShape).clickable { onChange(!checked) }.padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = c.text)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun ActionRow(
    icon: ImageVector, label: String, hint: String? = null, tint: Color = Matty.colors.icon, trailing: (@Composable () -> Unit)? = null,
    /** Off: the row is there to say why it can't be done ([hint]), and doesn't respond. */
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = Matty.colors
    Row(
        Modifier.fillMaxWidth().clip(Matty.RowShape).clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = if (tint == c.icon) c.text else tint)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
        trailing?.invoke()
    }
}

/** MarkOut's empty state: one 112dp pill-colored circle with an icon, and a calm line. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String? = null, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val c = Matty.colors
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(112.dp).background(c.pill, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.icon, modifier = Modifier.size(52.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = c.title, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
fun CenteredLoading(modifier: Modifier = Modifier, size: Dp = 48.dp) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        LoadingIndicator(Modifier.size(size))
    }
}

/** MarkOut's house dialog: 28dp surface, title row with icon, icon-only actions. */
@Composable
fun MattyDialog(
    title: String,
    onDismiss: () -> Unit,
    icon: ImageVector? = null,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(shape = Matty.DialogShape, color = Matty.colors.pill, tonalElevation = AlertDialogDefaults.TonalElevation) {
            Column(Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        Icon(icon, null, tint = Matty.colors.icon)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(title, style = MaterialTheme.typography.titleMedium, color = Matty.colors.title)
                }
                Spacer(Modifier.height(14.dp))
                Column(Modifier.weight(1f, fill = false), content = content)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, content = actions)
            }
        }
    }
}

/** A tappable chip in a pill, e.g. a filter or quick reply. */
@Composable
fun ChipPill(text: String, selected: Boolean = false, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val c = Matty.colors
    val bg by animateColorAsState(if (selected) c.selected else c.pill, label = "chip")
    val scale by animateFloatAsState(if (selected) 1.04f else 1f, Matty.PopSpring, label = "chipScale")
    Row(
        Modifier.scale(scale).height(36.dp).clip(RoundedCornerShape(18.dp)).background(bg)
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Spacer(Modifier.width(6.dp)) }
        Text(text, color = if (selected) c.title else c.textSecondary, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun NetImage(url: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    AsyncImage(model = url, contentDescription = null, contentScale = contentScale, modifier = modifier)
}

/**
 * Scrolling inside a bottom sheet. Material's sheet takes whatever speed a fling has left when
 * the content reaches an end and plays out a fling of its own, though it's already fully open,
 * and the content only bounces once that's over: a flick to the bottom (or back to the top) of
 * a long profile or the GIF grid stopped dead, waited about half a second, then bounced. Here
 * that leftover goes straight into the content's own bounce, at either end. The one exception is
 * a gesture that pulled the sheet itself down (the content was already at its top): its
 * downward speed is still the sheet's, so a flick down closes it as before.
 */
class SheetScrolling internal constructor(
    val overscroll: androidx.compose.foundation.OverscrollEffect?,
    val connection: androidx.compose.ui.input.nestedscroll.NestedScrollConnection,
)

/**
 * [canScroll]: whether the content is longer than its space (content that fits never bounces).
 * [horizontal]: a row that scrolls sideways (category chips, quick reactions); the sheet holds a
 * sideways fling's leftover just the same, so its bounce gets the same treatment.
 */
@Composable
fun rememberSheetScrolling(horizontal: Boolean = false, canScroll: () -> Boolean): SheetScrolling {
    val overscroll = androidx.compose.foundation.rememberOverscrollEffect()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val scrollable by androidx.compose.runtime.rememberUpdatedState(canScroll)
    return remember(overscroll, horizontal) {
        val connection = object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            /** This gesture dragged the sheet down: the content had nothing left to scroll back. */
            var pulledSheet = false

            override fun onPostScroll(
                consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset {
                // Whether the sheet is being pulled *now*: once the content scrolls again (the sheet
                // back at its anchor), a later fling is the content's.
                if (!horizontal && source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) {
                    if (available.y > 0f) pulledSheet = true else if (consumed.y != 0f) pulledSheet = false
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }

            override suspend fun onPostFling(consumed: androidx.compose.ui.unit.Velocity, available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                val pulled = pulledSheet
                pulledSheet = false
                val v = if (horizontal) available.x else available.y
                if (overscroll == null || v == 0f || !scrollable()) return androidx.compose.ui.unit.Velocity.Zero
                if (!horizontal && v > 0f && pulled) return androidx.compose.ui.unit.Velocity.Zero
                val fling = if (horizontal) androidx.compose.ui.unit.Velocity(v, 0f) else androidx.compose.ui.unit.Velocity(0f, v)
                scope.launch { overscroll.applyToFling(fling) { androidx.compose.ui.unit.Velocity.Zero } }
                return fling
            }
        }
        // Sideways, Material's sheet reports the row's own fling speed back as used, which left
        // the row's effect a phantom leftover: a late stretch at the edge it was flicked away
        // from. So the row's effect takes no fling of its own; the connection's bounce, with the
        // true leftover and at the right edge, is the only one.
        SheetScrolling(if (horizontal) overscroll?.let { NoFlingAbsorb(it) } else overscroll, connection)
    }
}

/** An overscroll effect that still stretches under a drag and lets go, but never absorbs a fling. */
private class NoFlingAbsorb(private val effect: androidx.compose.foundation.OverscrollEffect) : androidx.compose.foundation.OverscrollEffect by effect {
    override suspend fun applyToFling(
        velocity: androidx.compose.ui.unit.Velocity,
        performFling: suspend (androidx.compose.ui.unit.Velocity) -> androidx.compose.ui.unit.Velocity,
    ) = effect.applyToFling(velocity) { v -> performFling(v); v }
}

/** [rememberSheetScrolling] for a plain scrolling column. */
@Composable
fun Modifier.sheetVerticalScroll(state: androidx.compose.foundation.ScrollState): Modifier {
    val sheet = rememberSheetScrolling { state.maxValue > 0 }
    return this.then(Modifier.nestedScroll(sheet.connection)).verticalScroll(state, sheet.overscroll)
}
