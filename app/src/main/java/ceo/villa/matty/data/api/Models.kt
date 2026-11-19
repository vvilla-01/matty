package ceo.villa.matty.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/*
 * Mattermost REST v4 models. Only the fields Matty uses are declared; the shared Json
 * instance ignores the rest, so new server fields never break parsing.
 */

@Serializable
data class User(
    val id: String,
    val username: String = "",
    @SerialName("first_name") val firstName: String = "",
    @SerialName("last_name") val lastName: String = "",
    val nickname: String = "",
    val email: String = "",
    val position: String = "",
    val roles: String = "",
    val locale: String = "",
    val timezone: Map<String, String> = emptyMap(),
    val props: Map<String, String> = emptyMap(),
    @SerialName("last_picture_update") val lastPictureUpdate: Long = 0,
    @SerialName("is_bot") val isBot: Boolean = false,
    @SerialName("bot_description") val botDescription: String = "",
    /** When the account was made: the order name colours are handed out in. */
    @SerialName("create_at") val createAt: Long = 0,
    @SerialName("update_at") val updateAt: Long = 0,
    @SerialName("delete_at") val deleteAt: Long = 0,
) {
    val fullName: String get() = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")

    /** Discord-style display name: nickname, then full name, then username. */
    val displayName: String
        get() = nickname.ifBlank { fullName.ifBlank { username } }

    val isSystemAdmin: Boolean get() = roles.split(' ').contains("system_admin")

    val customStatus: CustomStatus?
        get() = props["customStatus"]?.takeIf { it.isNotBlank() }?.let {
            runCatching { MattermostJson.decodeFromString(CustomStatus.serializer(), it) }.getOrNull()
        }?.takeIf { it.isActive() }

    /** IANA zone the user shows to others, honoring automatic vs manual timezone. */
    val zoneId: String?
        get() = if (timezone["useAutomaticTimezone"] == "true") timezone["automaticTimezone"]?.ifBlank { null }
        else timezone["manualTimezone"]?.ifBlank { null }
}

@Serializable
data class CustomStatus(
    val emoji: String = "",
    val text: String = "",
    val duration: String = "",
    @SerialName("expires_at") val expiresAt: String = "",
) {
    fun isActive(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (emoji.isBlank() && text.isBlank()) return false
        if (expiresAt.isBlank() || duration == "dont_clear") return true
        val t = runCatching { java.time.Instant.parse(expiresAt).toEpochMilli() }.getOrNull() ?: return true
        // Go's zero time means "never".
        return t <= 0 || t > nowMs || expiresAt.startsWith("0001")
    }
}

@Serializable
data class UserStatus(
    @SerialName("user_id") val userId: String,
    val status: String = "offline",
    val manual: Boolean = false,
    @SerialName("last_activity_at") val lastActivityAt: Long = 0,
)

@Serializable
data class Team(
    val id: String,
    @SerialName("display_name") val displayName: String = "",
    val name: String = "",
    val description: String = "",
    val type: String = "O",
    @SerialName("last_team_icon_update") val lastTeamIconUpdate: Long = 0,
    @SerialName("allow_open_invite") val allowOpenInvite: Boolean = false,
    @SerialName("delete_at") val deleteAt: Long = 0,
)

@Serializable
data class TeamUnread(
    @SerialName("team_id") val teamId: String,
    @SerialName("msg_count") val msgCount: Long = 0,
    @SerialName("mention_count") val mentionCount: Long = 0,
    @SerialName("msg_count_root") val msgCountRoot: Long = 0,
    @SerialName("mention_count_root") val mentionCountRoot: Long = 0,
    @SerialName("thread_count") val threadCount: Long = 0,
    @SerialName("thread_mention_count") val threadMentionCount: Long = 0,
)

@Serializable
data class Channel(
    val id: String,
    @SerialName("team_id") val teamId: String = "",
    val type: String = "O",
    @SerialName("display_name") val displayName: String = "",
    val name: String = "",
    val header: String = "",
    val purpose: String = "",
    @SerialName("last_post_at") val lastPostAt: Long = 0,
    @SerialName("last_root_post_at") val lastRootPostAt: Long = 0,
    @SerialName("total_msg_count") val totalMsgCount: Long = 0,
    @SerialName("total_msg_count_root") val totalMsgCountRoot: Long = 0,
    @SerialName("creator_id") val creatorId: String = "",
    @SerialName("delete_at") val deleteAt: Long = 0,
) {
    val isDirect get() = type == "D"
    val isGroup get() = type == "G"
    val isPrivate get() = type == "P"
    val isDmOrGm get() = type == "D" || type == "G"

    /** For a DM channel named "<a>__<b>", the other participant's id. */
    fun otherUserId(myId: String): String? =
        if (!isDirect) null else name.split("__").firstOrNull { it != myId } ?: myId
}

@Serializable
data class ChannelMember(
    @SerialName("channel_id") val channelId: String,
    @SerialName("user_id") val userId: String = "",
    val roles: String = "",
    @SerialName("last_viewed_at") val lastViewedAt: Long = 0,
    @SerialName("msg_count") val msgCount: Long = 0,
    @SerialName("msg_count_root") val msgCountRoot: Long = 0,
    @SerialName("mention_count") val mentionCount: Long = 0,
    @SerialName("mention_count_root") val mentionCountRoot: Long = 0,
    @SerialName("notify_props") val notifyProps: Map<String, String> = emptyMap(),
) {
    val isMuted: Boolean get() = notifyProps["mark_unread"] == "mention"
}

@Serializable
data class SidebarCategory(
    val id: String,
    @SerialName("team_id") val teamId: String = "",
    val type: String = "custom",
    @SerialName("display_name") val displayName: String = "",
    val sorting: String = "",
    @SerialName("channel_ids") val channelIds: List<String> = emptyList(),
    val collapsed: Boolean = false,
    val muted: Boolean = false,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class SidebarCategories(
    val categories: List<SidebarCategory> = emptyList(),
    val order: List<String> = emptyList(),
)

@Serializable
data class Post(
    val id: String,
    @SerialName("create_at") val createAt: Long = 0,
    @SerialName("update_at") val updateAt: Long = 0,
    @SerialName("edit_at") val editAt: Long = 0,
    @SerialName("delete_at") val deleteAt: Long = 0,
    @SerialName("is_pinned") val isPinned: Boolean = false,
    @SerialName("user_id") val userId: String = "",
    @SerialName("channel_id") val channelId: String = "",
    @SerialName("root_id") val rootId: String = "",
    val message: String = "",
    val type: String = "",
    val props: JsonObject = JsonObject(emptyMap()),
    @SerialName("file_ids") val fileIds: List<String> = emptyList(),
    @SerialName("pending_post_id") val pendingPostId: String = "",
    @SerialName("reply_count") val replyCount: Int = 0,
    @SerialName("last_reply_at") val lastReplyAt: Long = 0,
    val participants: List<User>? = null,
    @SerialName("is_following") val isFollowing: Boolean? = null,
    val metadata: PostMetadata = PostMetadata(),
) {
    val isSystem: Boolean get() = type.startsWith("system_")
    val isReply: Boolean get() = rootId.isNotEmpty()

    fun prop(key: String): String? = (props[key] as? JsonPrimitive)?.contentOrNull

    /**
     * The name a webhook's or a bot's post is shown under instead of its author's, as Mattermost's
     * web app does it: only when the server [allowed] it (EnablePostUsernameOverride). The marks
     * themselves (from_webhook, from_bot) are props any member can set on their own post on a
     * server in its default mode, so without the setting the post shows its real author.
     */
    fun overrideUsername(allowed: Boolean): String? =
        if (!allowed) null
        else prop("override_username")?.takeIf { it.isNotBlank() && (prop("from_webhook") == "true" || prop("from_bot") == "true") }

    /** The picture a webhook's post is shown with instead of its author's: only when the server [allowed] it (EnablePostIconOverride). */
    fun overrideIconUrl(allowed: Boolean): String? =
        if (!allowed) null else prop("override_icon_url")?.takeIf { it.isNotBlank() && prop("from_webhook") == "true" }
    val fromBot: Boolean get() = prop("from_bot") == "true" || prop("from_webhook") == "true"

    val attachments: List<MessageAttachment>
        get() = props["attachments"]?.let {
            runCatching { MattermostJson.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(MessageAttachment.serializer()), it) }.getOrNull()
        } ?: emptyList()
}

@Serializable
data class PostMetadata(
    val embeds: List<PostEmbed>? = null,
    val emojis: List<CustomEmoji>? = null,
    val files: List<FileInfo>? = null,
    val images: Map<String, PostImage>? = null,
    val reactions: List<Reaction>? = null,
)

@Serializable
data class PostEmbed(
    val type: String = "",
    val url: String = "",
    val data: JsonElement? = null,
)

@Serializable
data class OpenGraph(
    val type: String = "",
    val url: String = "",
    val title: String = "",
    val description: String = "",
    @SerialName("site_name") val siteName: String = "",
    val images: List<OgImage>? = null,
)

@Serializable
data class OgImage(
    val url: String = "",
    @SerialName("secure_url") val secureUrl: String = "",
    val width: Int = 0,
    val height: Int = 0,
)

@Serializable
data class PostImage(
    val width: Int = 0,
    val height: Int = 0,
    val format: String = "",
    @SerialName("frame_count") val frameCount: Int = 0,
)

@Serializable
data class PostList(
    val order: List<String> = emptyList(),
    val posts: Map<String, Post> = emptyMap(),
    @SerialName("next_post_id") val nextPostId: String = "",
    @SerialName("prev_post_id") val prevPostId: String = "",
    @SerialName("has_next") val hasNext: Boolean = false,
) {
    fun ordered(): List<Post> = order.mapNotNull { posts[it] }
}

@Serializable
data class Reaction(
    @SerialName("user_id") val userId: String,
    @SerialName("post_id") val postId: String,
    @SerialName("emoji_name") val emojiName: String,
    @SerialName("create_at") val createAt: Long = 0,
)

@Serializable
data class FileInfo(
    val id: String,
    @SerialName("user_id") val userId: String = "",
    @SerialName("post_id") val postId: String = "",
    val name: String = "",
    val extension: String = "",
    val size: Long = 0,
    @SerialName("mime_type") val mimeType: String = "",
    val width: Int = 0,
    val height: Int = 0,
    @SerialName("has_preview_image") val hasPreviewImage: Boolean = false,
    @SerialName("mini_preview") val miniPreview: String? = null,
) {
    val isImage get() = mimeType.startsWith("image/")
    /** Mattermost files a .webm under audio/webm whatever is in it; like its web app, treat it as video. */
    private val webm get() = extension.equals("webm", ignoreCase = true) && mimeType == "audio/webm"
    val isVideo get() = mimeType.startsWith("video/") || webm
    val isAudio get() = mimeType.startsWith("audio/") && !webm
    val isGif get() = mimeType == "image/gif" || extension.equals("gif", true)
}

@Serializable
data class FileUploadResponse(
    @SerialName("file_infos") val fileInfos: List<FileInfo> = emptyList(),
    @SerialName("client_ids") val clientIds: List<String> = emptyList(),
)

@Serializable
data class CustomEmoji(
    val id: String,
    @SerialName("creator_id") val creatorId: String = "",
    val name: String = "",
    @SerialName("create_at") val createAt: Long = 0,
    @SerialName("delete_at") val deleteAt: Long = 0,
)

@Serializable
data class Preference(
    @SerialName("user_id") val userId: String,
    val category: String,
    val name: String,
    val value: String,
)

@Serializable
data class MessageAttachment(
    val fallback: String = "",
    val color: String = "",
    val pretext: String = "",
    @SerialName("author_name") val authorName: String = "",
    @SerialName("author_link") val authorLink: String = "",
    @SerialName("author_icon") val authorIcon: String = "",
    val title: String = "",
    @SerialName("title_link") val titleLink: String = "",
    val text: String = "",
    val fields: List<AttachmentField>? = null,
    @SerialName("image_url") val imageUrl: String = "",
    @SerialName("thumb_url") val thumbUrl: String = "",
    val footer: String = "",
    @SerialName("footer_icon") val footerIcon: String = "",
    val actions: List<AttachmentAction>? = null,
)

@Serializable
data class AttachmentField(
    val title: String = "",
    val value: JsonElement? = null,
    val short: Boolean = false,
) {
    val valueText: String
        get() = when (val v = value) {
            null -> ""
            is JsonPrimitive -> v.contentOrNull ?: ""
            else -> v.toString()
        }
}

@Serializable
data class AttachmentAction(
    val id: String = "",
    val name: String = "",
    val type: String = "button",
    val style: String = "",
    @SerialName("data_source") val dataSource: String = "",
    val options: List<ActionOption>? = null,
    @SerialName("default_option") val defaultOption: String = "",
    val disabled: Boolean = false,
)

@Serializable
data class ActionOption(val text: String = "", val value: String = "")

@Serializable
data class PostActionResponse(
    val update: Post? = null,
    @SerialName("ephemeral_text") val ephemeralText: String = "",
)

@Serializable
data class AutocompleteSuggestion(
    @SerialName("Complete") val complete: String = "",
    @SerialName("Suggestion") val suggestion: String = "",
    @SerialName("Hint") val hint: String = "",
    @SerialName("Description") val description: String = "",
    @SerialName("IconData") val iconData: String = "",
)

@Serializable
data class CommandResponse(
    @SerialName("response_type") val responseType: String = "",
    val text: String = "",
    @SerialName("goto_location") val gotoLocation: String = "",
    @SerialName("trigger_id") val triggerId: String = "",
)

@Serializable
data class SlashCommand(
    val id: String = "",
    val trigger: String = "",
    @SerialName("display_name") val displayName: String = "",
    val description: String = "",
    @SerialName("auto_complete") val autoComplete: Boolean = false,
    @SerialName("auto_complete_desc") val autoCompleteDesc: String = "",
    @SerialName("auto_complete_hint") val autoCompleteHint: String = "",
    @SerialName("plugin_id") val pluginId: String = "",
)

@Serializable
data class UserThread(
    val id: String,
    @SerialName("reply_count") val replyCount: Int = 0,
    @SerialName("last_reply_at") val lastReplyAt: Long = 0,
    @SerialName("last_viewed_at") val lastViewedAt: Long = 0,
    val participants: List<User> = emptyList(),
    val post: Post,
    @SerialName("unread_replies") val unreadReplies: Int = 0,
    @SerialName("unread_mentions") val unreadMentions: Int = 0,
    @SerialName("is_following") val isFollowing: Boolean = true,
)

@Serializable
data class UserThreads(
    val total: Int = 0,
    @SerialName("total_unread_threads") val totalUnreadThreads: Int = 0,
    @SerialName("total_unread_mentions") val totalUnreadMentions: Int = 0,
    val threads: List<UserThread> = emptyList(),
)

@Serializable
data class WebappPlugin(
    val id: String,
    val version: String = "",
)

/** Interactive dialog opened by a plugin or integration (websocket "open_dialog"). */
@Serializable
data class OpenDialogRequest(
    @SerialName("trigger_id") val triggerId: String = "",
    val url: String = "",
    val dialog: InteractiveDialog = InteractiveDialog(),
)

@Serializable
data class InteractiveDialog(
    @SerialName("callback_id") val callbackId: String = "",
    val title: String = "",
    @SerialName("introduction_text") val introductionText: String = "",
    @SerialName("icon_url") val iconUrl: String = "",
    val elements: List<DialogElement>? = null,
    @SerialName("submit_label") val submitLabel: String = "",
    @SerialName("notify_on_cancel") val notifyOnCancel: Boolean = false,
    val state: String = "",
)

@Serializable
data class DialogElement(
    @SerialName("display_name") val displayName: String = "",
    val name: String = "",
    val type: String = "text",
    @SerialName("subtype") val subType: String = "",
    val default: String = "",
    val placeholder: String = "",
    @SerialName("help_text") val helpText: String = "",
    val optional: Boolean = false,
    @SerialName("min_length") val minLength: Int = 0,
    @SerialName("max_length") val maxLength: Int = 0,
    @SerialName("data_source") val dataSource: String = "",
    val options: List<DialogOption>? = null,
)

@Serializable
data class DialogOption(val text: String = "", val value: String = "")

@Serializable
data class DialogSubmitResponse(
    val error: String = "",
    val errors: Map<String, String> = emptyMap(),
)

@Serializable
data class ApiError(
    val id: String = "",
    val message: String = "",
    @SerialName("status_code") val statusCode: Int = 0,
)

/** Giphy v1 search/trending payloads (only what the picker needs). */
@Serializable
data class GiphyResponse(val data: List<GiphyGif> = emptyList(), val pagination: GiphyPagination? = null)

@Serializable
data class GiphyPagination(@SerialName("total_count") val totalCount: Int = 0, val count: Int = 0, val offset: Int = 0)

@Serializable
data class GiphyGif(
    val id: String = "",
    val title: String = "",
    val images: GiphyImages = GiphyImages(),
)

@Serializable
data class GiphyImages(
    @SerialName("fixed_height") val fixedHeight: GiphyImage = GiphyImage(),
    @SerialName("fixed_width_small") val fixedWidthSmall: GiphyImage = GiphyImage(),
    @SerialName("fixed_height_small") val fixedHeightSmall: GiphyImage = GiphyImage(),
    @SerialName("preview_gif") val previewGif: GiphyImage = GiphyImage(),
)

@Serializable
data class GiphyImage(
    val url: String = "",
    val width: String = "0",
    val height: String = "0",
    val webp: String = "",
)

fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

fun JsonObject.str(key: String): String? = this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
