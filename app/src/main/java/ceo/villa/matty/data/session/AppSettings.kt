package ceo.villa.matty.data.session

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { Oled, Dim, Light, System }
enum class AccentMode { MaterialYou, Matty, VillaNet }

data class Settings(
    val theme: ThemeMode = ThemeMode.Oled,
    /** With the System theme, what the phone's night mode looks like: Dim rather than OLED black. */
    val systemDim: Boolean = false,
    val accent: AccentMode = AccentMode.MaterialYou,
    val compactMessages: Boolean = false,
    val colorfulNames: Boolean = true,
    val autoplayGifs: Boolean = true,
    val doubleTapReaction: String = "heart",
    val sendGifsImmediately: Boolean = true,
    val backgroundConnection: Boolean = true,
    val notifyAllDms: Boolean = true,
    val showTyping: Boolean = true,
    val sendTyping: Boolean = true,
    val enterToSend: Boolean = false,
    val linkPreviews: Boolean = true,
    /** Recent messages kept (encrypted) on the device, so conversations open at once. */
    val keepMessages: Boolean = true,
    val haptics: Boolean = true,
    val giphyKeyOverride: String = "",
    val recentEmojis: List<String> = listOf("+1", "heart", "joy", "fire", "eyes", "tada"),
)

/** Device-local preferences (never synced to the server). */
class AppSettings(context: Context) {
    private val p = context.getSharedPreferences("matty_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state
    val value: Settings get() = _state.value

    private fun read() = Settings(
        theme = runCatching { ThemeMode.valueOf(p.getString("theme", ThemeMode.Oled.name)!!) }.getOrDefault(ThemeMode.Oled),
        systemDim = p.getBoolean("system_dim", false),
        accent = runCatching { AccentMode.valueOf(p.getString("accent", AccentMode.MaterialYou.name)!!) }.getOrDefault(AccentMode.MaterialYou),
        compactMessages = p.getBoolean("compact", false),
        colorfulNames = p.getBoolean("colorful_names", true),
        autoplayGifs = p.getBoolean("autoplay_gifs", true),
        doubleTapReaction = p.getString("double_tap", "heart") ?: "heart",
        sendGifsImmediately = p.getBoolean("gif_immediate", true),
        backgroundConnection = p.getBoolean("bg_connection", true),
        notifyAllDms = p.getBoolean("notify_dms", true),
        showTyping = p.getBoolean("show_typing", true),
        sendTyping = p.getBoolean("send_typing", true),
        enterToSend = p.getBoolean("enter_send", false),
        linkPreviews = p.getBoolean("link_previews", true),
        keepMessages = p.getBoolean("keep_messages", true),
        haptics = p.getBoolean("haptics", true),
        giphyKeyOverride = p.getString("giphy_key", "") ?: "",
        recentEmojis = p.getString("recent_emojis", null)?.split(',')?.filter { it.isNotBlank() }
            ?: Settings().recentEmojis,
    )

    fun update(block: (Settings) -> Settings) {
        val s = block(_state.value)
        _state.value = s
        p.edit()
            .putString("theme", s.theme.name)
            .putBoolean("system_dim", s.systemDim)
            .putString("accent", s.accent.name)
            .putBoolean("compact", s.compactMessages)
            .putBoolean("colorful_names", s.colorfulNames)
            .putBoolean("autoplay_gifs", s.autoplayGifs)
            .putString("double_tap", s.doubleTapReaction)
            .putBoolean("gif_immediate", s.sendGifsImmediately)
            .putBoolean("bg_connection", s.backgroundConnection)
            .putBoolean("notify_dms", s.notifyAllDms)
            .putBoolean("show_typing", s.showTyping)
            .putBoolean("send_typing", s.sendTyping)
            .putBoolean("enter_send", s.enterToSend)
            .putBoolean("link_previews", s.linkPreviews)
            .putBoolean("keep_messages", s.keepMessages)
            .putBoolean("haptics", s.haptics)
            .putString("giphy_key", s.giphyKeyOverride)
            .putString("recent_emojis", s.recentEmojis.joinToString(","))
            .apply()
    }

    fun noteEmojiUsed(name: String) = update { s ->
        s.copy(recentEmojis = (listOf(name) + s.recentEmojis.filterNot { it == name }).take(24))
    }
}
