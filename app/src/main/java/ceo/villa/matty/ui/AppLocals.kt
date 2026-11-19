package ceo.villa.matty.ui

import androidx.compose.runtime.staticCompositionLocalOf
import ceo.villa.matty.data.api.FileInfo
import ceo.villa.matty.data.repo.ChatRepository

/** Everything a message, profile or plugin card can ask the app to do. */
interface ChatActions {
    fun openUser(userId: String)
    fun openUsername(username: String)
    fun openChannel(channelId: String)
    fun openChannelByName(name: String)
    fun openThread(rootId: String)
    fun openUrl(url: String)
    /** A link whose words name another site: it goes to [host], and opens once the person says so. */
    fun confirmUrl(url: String, host: String) = openUrl(url)
    /** A long press on a link: where it goes, to copy or open. */
    fun showLink(url: String) {}
    fun openMedia(url: String, isVideo: Boolean = false, title: String = "")
    fun openFile(file: FileInfo)
    /** The post's photos and videos, full screen from [index], to swipe through. */
    fun openGallery(files: List<FileInfo>, index: Int) = openFile(files[index])
    fun search(terms: String)
    fun openPlugin(pluginId: String, args: Map<String, String> = emptyMap())
    fun messageUser(userId: String)
}

val LocalRepo = staticCompositionLocalOf<ChatRepository> { error("No active server") }
val LocalActions = staticCompositionLocalOf<ChatActions> { error("No actions") }

/** The surrounding message's long-press action, so media and cards inside it can forward theirs. */
val LocalMessageLongPress = androidx.compose.runtime.compositionLocalOf<(() -> Unit)?> { null }
