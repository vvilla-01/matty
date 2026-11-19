package ceo.villa.matty.ui.theme

import android.content.Context
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ceo.villa.matty.data.session.AccentMode
import ceo.villa.matty.data.session.ThemeMode

/**
 * Matty's chrome palette. The OLED theme is MarkOut's, value for value: a black canvas, flat
 * #0E0E10 cards, #1C1C20 floating pills, #3C3C44 for whatever is selected, and Material You
 * used as an accent rather than as a tint over everything.
 */
@Immutable
data class MattyColors(
    val isDark: Boolean,
    val background: Color,
    val card: Color,
    val pill: Color,
    val selected: Color,
    val icon: Color,
    val title: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val well: Color,
    val rim: Color,
    val divider: Color,
    val scrim: Color,
    val error: Color,
    val destructive: Color,
    val link: Color,
    val accent: Color,
    val onAccent: Color,
    val mention: Color,
    val mentionBar: Color,
    val codeBg: Color,
    val online: Color = Color(0xFF3DB887),
    val away: Color = Color(0xFFFFBC1F),
    val dnd: Color = Color(0xFFD24B4E),
    val offline: Color = Color(0xFF80808A),
    /** Joining a voice channel: a green dark enough for [onStatus] words on it. */
    val join: Color = Color(0xFF248046),
    /** Words and icons drawn on [online], [dnd] or [join]. */
    val onStatus: Color = Color.White,
)

private fun oled(accent: Color) = MattyColors(
    isDark = true,
    background = Color.Black,
    card = Color(0xFF0E0E10),
    pill = Color(0xFF1C1C20),
    selected = Color(0xFF3C3C44),
    icon = Color(0xFFECECEC),
    title = Color(0xFFF2F2F2),
    text = Color(0xFFECECEC),
    textSecondary = Color(0xFF9A9AA2),
    textTertiary = Color(0xFF6E6E76),
    well = Color(0x0DFFFFFF),
    rim = Color(0x2EFFFFFF),
    divider = Color(0x14FFFFFF),
    scrim = Color(0xB3000000),
    error = Color(0xFFFF6E62),
    destructive = Color(0xFFFF6B6B),
    link = Color(0xFF64B5F6),
    accent = accent,
    onAccent = if (accent.luminance() > 0.4f) Color(0xFF111111) else Color.White,
    mention = accent.copy(alpha = 0.10f),
    mentionBar = accent,
    codeBg = Color(0xFF16161A),
)

private fun dim(accent: Color) = oled(accent).copy(
    background = Color(0xFF131316),
    card = Color(0xFF1B1B1F),
    pill = Color(0xFF26262B),
    selected = Color(0xFF3E3E46),
    codeBg = Color(0xFF1F1F24),
)

private fun light(accent: Color) = MattyColors(
    isDark = false,
    background = Color.White,
    card = Color(0xFFF3F3F6),
    pill = Color(0xFFEAEAEF),
    selected = Color(0xFFD9D9E1),
    icon = Color(0xFF1C1C20),
    title = Color(0xFF111114),
    text = Color(0xFF1C1C20),
    textSecondary = Color(0xFF5E5E66),
    textTertiary = Color(0xFF8A8A92),
    well = Color(0x0A000000),
    rim = Color(0x22000000),
    divider = Color(0x14000000),
    scrim = Color(0x66000000),
    error = Color(0xFFC62828),
    destructive = Color(0xFFD32F2F),
    link = Color(0xFF1A73E8),
    accent = accent,
    onAccent = if (accent.luminance() > 0.4f) Color(0xFF111111) else Color.White,
    mention = accent.copy(alpha = 0.10f),
    mentionBar = accent,
    codeBg = Color(0xFFEDEDF2),
)

val MattyBlueDark = Color(0xFF8AB4FF)
val MattyBlueLight = Color(0xFF3A63D8)
/** VillaNET's red: #c8102e on light, the plugin's on-black text red #ff5a6a on dark. */
val VillaNetRed = Color(0xFFC8102E)
val VillaNetRedOnBlack = Color(0xFFFF5A6A)

fun accentFor(context: Context, mode: AccentMode, dark: Boolean): Color = when (mode) {
    AccentMode.MaterialYou ->
        Color(context.getColor(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600))
    AccentMode.Matty -> if (dark) MattyBlueDark else MattyBlueLight
    AccentMode.VillaNet -> if (dark) VillaNetRedOnBlack else VillaNetRed
}

val LocalMattyColors = staticCompositionLocalOf { oled(MattyBlueDark) }

object Matty {
    val colors: MattyColors
        @Composable get() = LocalMattyColors.current

    /** MarkOut's signature spring for things that grow out of a button. */
    val PillSpring = spring<IntSize>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntSize.VisibilityThreshold)
    val PopSpring = spring<Float>(dampingRatio = 0.45f, stiffness = 700f)
    val SettleSpring = spring<Float>(dampingRatio = 0.85f, stiffness = 500f)

    val PillShape = RoundedCornerShape(28.dp)
    val CardShape = RoundedCornerShape(24.dp)
    val DialogShape = RoundedCornerShape(28.dp)
    val FieldShape = RoundedCornerShape(16.dp)
    val RowShape = RoundedCornerShape(12.dp)
}

/** The three looks Matty draws in. */
enum class Palette { Oled, Dim, Light }

/**
 * The look for [theme], given the phone's own light/dark setting and, for the System theme, the
 * dark style chosen for night ([systemDim]: Dim rather than OLED black).
 */
fun paletteFor(theme: ThemeMode, systemDark: Boolean, systemDim: Boolean): Palette = when {
    !theme.isDark(systemDark) -> Palette.Light
    theme == ThemeMode.Dim || (theme == ThemeMode.System && systemDim) -> Palette.Dim
    else -> Palette.Oled
}

/** Matty's colours for [palette] around [accent]. */
fun mattyColors(palette: Palette, accent: Color): MattyColors = when (palette) {
    Palette.Oled -> oled(accent)
    Palette.Dim -> dim(accent)
    Palette.Light -> light(accent)
}

@Composable
fun MattyTheme(themeMode: ThemeMode, accentMode: AccentMode, systemDim: Boolean = false, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = themeMode.isDark(systemDark)
    // The clock, battery and navigation icons follow Matty's theme, not the phone's: a phone in
    // light mode would otherwise draw them black on the OLED canvas.
    val activity = context as? androidx.activity.ComponentActivity
    androidx.compose.runtime.LaunchedEffect(activity, dark) { activity?.styleSystemBars(dark) }
    val accent = remember(accentMode, dark) { accentFor(context, accentMode, dark) }
    val palette = paletteFor(themeMode, systemDark, systemDim)
    val colors = remember(palette, accent) { mattyColors(palette, accent) }
    val scheme = if (colors.isDark) darkColorScheme(
        background = colors.background,
        surface = colors.background,
        surfaceContainerLowest = colors.background,
        surfaceContainerLow = colors.card,
        surfaceContainer = colors.card,
        surfaceContainerHigh = colors.pill,
        surfaceContainerHighest = colors.selected,
        surfaceVariant = colors.pill,
        onBackground = colors.text,
        onSurface = colors.text,
        onSurfaceVariant = colors.textSecondary,
        primary = colors.accent,
        onPrimary = colors.onAccent,
        primaryContainer = colors.selected,
        onPrimaryContainer = colors.title,
        secondary = colors.accent,
        onSecondary = colors.onAccent,
        secondaryContainer = Color(0xFF2E2E33),
        onSecondaryContainer = Color.White,
        tertiary = colors.accent,
        error = colors.error,
        outline = colors.rim,
        outlineVariant = colors.divider,
        inverseSurface = colors.title,
        inverseOnSurface = colors.background,
        scrim = Color.Black,
    ) else lightColorScheme(
        background = colors.background,
        surface = colors.background,
        surfaceContainerLowest = colors.background,
        surfaceContainerLow = colors.card,
        surfaceContainer = colors.card,
        surfaceContainerHigh = colors.pill,
        surfaceContainerHighest = colors.selected,
        surfaceVariant = colors.pill,
        onBackground = colors.text,
        onSurface = colors.text,
        onSurfaceVariant = colors.textSecondary,
        primary = colors.accent,
        onPrimary = colors.onAccent,
        primaryContainer = colors.selected,
        onPrimaryContainer = colors.title,
        secondary = colors.accent,
        onSecondary = colors.onAccent,
        secondaryContainer = colors.selected,
        onSecondaryContainer = colors.title,
        tertiary = colors.accent,
        error = colors.error,
        outline = colors.rim,
        outlineVariant = colors.divider,
        inverseSurface = colors.title,
        inverseOnSurface = colors.background,
    )
    val base = Typography()
    val typography = base.copy(
        // Messages read at 16sp like Discord; names a touch bolder than M3's default.
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    )
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )
    CompositionLocalProvider(LocalMattyColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
    }
}

val MonoStyle = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp)

/** Whether [this] theme draws dark, given the phone's own light/dark setting. */
fun ceo.villa.matty.data.session.ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ceo.villa.matty.data.session.ThemeMode.Light -> false
    ceo.villa.matty.data.session.ThemeMode.System -> systemDark
    else -> true
}

/**
 * Edge to edge, with light status and navigation bar icons on a dark theme and dark ones on the
 * light theme. (enableEdgeToEdge's default picks them from the phone's setting instead.) The
 * navigation bar keeps Android's usual scrim behind three-button navigation.
 */
fun androidx.activity.ComponentActivity.styleSystemBars(dark: Boolean) {
    val clear = android.graphics.Color.TRANSPARENT
    val lightScrim = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
    val darkScrim = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    enableEdgeToEdge(
        statusBarStyle = if (dark) androidx.activity.SystemBarStyle.dark(clear) else androidx.activity.SystemBarStyle.light(clear, clear),
        navigationBarStyle = if (dark) androidx.activity.SystemBarStyle.dark(darkScrim) else androidx.activity.SystemBarStyle.light(lightScrim, darkScrim),
    )
}
