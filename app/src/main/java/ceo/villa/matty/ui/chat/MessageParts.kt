package ceo.villa.matty.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddReaction
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ceo.villa.matty.container
import ceo.villa.matty.data.api.AttachmentAction
import ceo.villa.matty.data.api.FileInfo
import ceo.villa.matty.data.api.MattermostJson
import ceo.villa.matty.data.api.MessageAttachment
import ceo.villa.matty.data.api.OpenGraph
import ceo.villa.matty.data.api.Post
import ceo.villa.matty.data.api.PostEmbed
import ceo.villa.matty.data.api.PostImage
import ceo.villa.matty.data.emoji.EmojiStore
import ceo.villa.matty.data.net.Downloader
import ceo.villa.matty.ui.LocalActions
import ceo.villa.matty.ui.LocalRepo
import ceo.villa.matty.ui.components.PillButton
import ceo.villa.matty.ui.components.leftBar
import ceo.villa.matty.ui.theme.Matty
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import coil3.decode.BitmapFactoryDecoder
import coil3.request.ImageRequest
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.launch

// ---- emoji --------------------------------------------------------------------------------

@Composable
fun EmojiGlyph(name: String, size: Dp, modifier: Modifier = Modifier) {
    val repo = LocalRepo.current
    val uni = EmojiStore.unicode(name)
    if (uni != null) {
        Text(uni, fontSize = (size.value * 0.86f).sp, lineHeight = (size.value).sp, modifier = modifier)
    } else {
        val customs by repo.customEmojis.collectAsState()
        val em = customs[name]
        if (em != null) {
            AsyncImage(model = repo.client.customEmojiUrl(em.id), contentDescription = ":$name:", modifier = modifier.size(size))
        } else {
            Text(":$name:", fontSize = (size.value * 0.5f).sp, color = Matty.colors.textSecondary, modifier = modifier)
        }
    }
}

// ---- images & GIFs ------------------------------------------------------------------------

@Composable
fun PostMediaImage(url: String, meta: PostImage?, alt: String = "", maxWidth: Dp = 300.dp, maxHeight: Dp = 320.dp) {
    val repo = LocalRepo.current
    val actions = LocalActions.current
    val settings by LocalContext.current.container.settings.state.collectAsState()
    val src = remember(url) { repo.client.proxiedImage(url, repo.imageProxyEnabled) }
    val ratio = if (meta != null && meta.width > 0 && meta.height > 0) meta.width.toFloat() / meta.height else 16f / 9f
    val isGif = meta?.format == "gif" || url.contains(".gif", true) || url.contains("giphy.com", true)
    val context = LocalContext.current
    // No request at all for an address that isn't loaded (src is null): only the placeholder.
    val model = remember(src, settings.autoplayGifs) {
        src?.let { if (isGif) gifRequest(context, it, settings.autoplayGifs) else ImageRequest.Builder(context).data(it).build() }
    }
    Box(
        Modifier.widthIn(max = maxWidth).heightIn(max = maxHeight).aspectRatio(ratio.coerceIn(0.5f, 3f), matchHeightConstraintsFirst = ratio < 1f)
            .clip(RoundedCornerShape(12.dp)).background(Matty.colors.pill)
            .combinedClickable(onClick = { actions.openMedia(url, title = alt) }, onLongClick = ceo.villa.matty.ui.LocalMessageLongPress.current),
    ) {
        Icon(Icons.Outlined.Image, null, tint = Matty.colors.textTertiary, modifier = Modifier.align(Alignment.Center))
        if (model != null) AsyncImage(model = model, contentDescription = alt.ifBlank { null }, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (isGif) {
            Text(
                "GIF", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

/**
 * A GIF, animated or (with autoplay off) as its first frame. An animation can't be kept in memory
 * the way a picture is, so scrolling back to one would show a blank tile while it's read again:
 * once it has played, its first frame is kept instead and shown at once in its place meanwhile.
 */
internal fun gifRequest(context: android.content.Context, data: Any, animate: Boolean): ImageRequest {
    if (!animate) return ImageRequest.Builder(context).data(data).decoderFactory(BitmapFactoryDecoder.Factory()).build()
    val poster = "gif-poster:$data"
    return ImageRequest.Builder(context).data(data)
        .placeholderMemoryCacheKey(poster)
        .listener(onSuccess = { _, _ ->
            val loader = coil3.SingletonImageLoader.get(context)
            // From the copy now on disk: no second download.
            if (loader.memoryCache?.get(coil3.memory.MemoryCache.Key(poster)) == null) loader.enqueue(
                ImageRequest.Builder(context).data(data).decoderFactory(BitmapFactoryDecoder.Factory()).memoryCacheKey(poster).size(640).build()
            )
        })
        .build()
}

/** The blurred thumbnail Mattermost sends along with a picture (a few hundred bytes), to show until the picture itself is in. */
@Composable
private fun rememberMiniPreview(f: FileInfo): androidx.compose.ui.graphics.ImageBitmap? = remember(f.id) {
    f.miniPreview?.takeIf { it.isNotBlank() }?.let { b64 ->
        runCatching {
            val raw = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size)?.asImageBitmap()
        }.getOrNull()
    }
}

// ---- file attachments ---------------------------------------------------------------------

@Composable
fun FileAttachments(files: List<FileInfo>) {
    val repo = LocalRepo.current
    val actions = LocalActions.current
    val media = files.filter { it.isImage || it.isVideo }
    val others = files - media.toSet()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // A tile opens the post's photos and videos together, at that one, to swipe through.
        if (media.size == 1) {
            val f = media.first()
            // The server doesn't measure videos: the shape their still had, once one has been
            // taken (and from then on the same, so a tile on screen never changes size).
            val stills = LocalContext.current.container.videoStills
            val shape = remember(f.id) {
                when {
                    f.width > 0 && f.height > 0 -> f.width.toFloat() / f.height
                    f.isVideo -> stills.aspect(f.id) ?: (16f / 9f)
                    else -> 4f / 3f
                }.coerceIn(0.5f, 2.5f)
            }
            MediaTile(f, Modifier.widthIn(max = 300.dp).heightIn(max = 340.dp).aspectRatio(shape, matchHeightConstraintsFirst = shape < 1f)) { actions.openGallery(media, 0) }
        } else if (media.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), maxItemsInEachRow = 2) {
                media.forEachIndexed { i, f -> MediaTile(f, Modifier.width(148.dp).aspectRatio(1f)) { actions.openGallery(media, i) } }
            }
        }
        others.forEach { f -> FileCard(f) { actions.openFile(f) } }
    }
    @Suppress("UNUSED_EXPRESSION") repo
}

@Composable
private fun MediaTile(f: FileInfo, modifier: Modifier, onOpen: () -> Unit) {
    val repo = LocalRepo.current
    val context = LocalContext.current
    val autoplay = context.container.settings.state.collectAsState().value.autoplayGifs
    val model: Any = when {
        f.isGif -> remember(f.id, autoplay) { gifRequest(context, repo.client.fileUrl(f.id), autoplay) }
        // Mattermost makes no preview for a video: a frame of it, taken by the app.
        f.isVideo && !f.hasPreviewImage -> ceo.villa.matty.data.net.VideoStill(f.id, repo.client.fileUrl(f.id))
        else -> repo.client.filePreviewUrl(f.id)
    }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Matty.colors.pill).combinedClickable(
        onLongClick = ceo.villa.matty.ui.LocalMessageLongPress.current,
        onClick = onOpen,
    )) {
        // Under the picture until it's in: the server's own blurred miniature of it, where it sent
        // one. Only until then: a picture with see-through parts (a PNG, a GIF) would otherwise
        // sit on a dark smear of itself. It goes once the picture has faded in over it (at once
        // when the picture was already in memory, which doesn't fade).
        val mini = rememberMiniPreview(f)
        var arrived by remember(f.id) { mutableStateOf(false) }
        var covered by remember(f.id) { mutableStateOf(false) }
        if (arrived && !covered) androidx.compose.runtime.LaunchedEffect(f.id) { kotlinx.coroutines.delay(250); covered = true }
        if (!covered) {
            if (mini != null) androidx.compose.foundation.Image(mini, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else if (!f.isVideo) Icon(Icons.Outlined.Image, null, tint = Matty.colors.textTertiary, modifier = Modifier.align(Alignment.Center))
        }
        AsyncImage(
            model = model, contentDescription = Downloader.safeName(f.name), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            onSuccess = { if (it.result.dataSource == coil3.decode.DataSource.MEMORY_CACHE) covered = true else arrived = true },
        )
        if (f.isVideo) {
            Box(Modifier.align(Alignment.Center).size(52.dp).background(Color(0x99000000), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
    }
}

@Composable
fun FileCard(f: FileInfo, onClick: () -> Unit) {
    val c = Matty.colors
    val icon = when {
        f.isAudio -> Icons.Outlined.Audiotrack
        f.extension.equals("pdf", true) -> Icons.Outlined.PictureAsPdf
        f.extension.lowercase() in setOf("zip", "gz", "tar", "7z", "rar") -> Icons.Outlined.FolderZip
        f.extension.lowercase() in setOf("txt", "md", "doc", "docx", "odt", "rtf") -> Icons.Outlined.Description
        else -> Icons.Outlined.InsertDriveFile
    }
    Row(
        Modifier.widthIn(max = 320.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card)
            .border(1.dp, c.divider, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).background(c.pill, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.accent)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            // As it is saved: a name can't read one way here and another in Downloads.
            Text(Downloader.safeName(f.name), color = c.link, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(humanSize(f.size) + " · " + f.extension.uppercase(), color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024f)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024f * 1024))
    else -> "%.1f GB".format(bytes / (1024f * 1024 * 1024))
}

// ---- link previews ------------------------------------------------------------------------

@Composable
fun EmbedView(embed: PostEmbed, images: Map<String, PostImage>) {
    when (embed.type) {
        "image" -> PostMediaImage(embed.url, images[embed.url])
        "opengraph" -> {
            val og = remember(embed) {
                embed.data?.let { runCatching { MattermostJson.decodeFromJsonElement(OpenGraph.serializer(), it) }.getOrNull() }
            } ?: return
            if (og.title.isBlank() && og.description.isBlank()) return
            OpenGraphCard(og, embed.url, images)
        }
    }
}

@Composable
private fun OpenGraphCard(og: OpenGraph, url: String, images: Map<String, PostImage>) {
    val c = Matty.colors
    val actions = LocalActions.current
    val img = og.images?.firstOrNull()?.let { it.secureUrl.ifBlank { it.url } }
    Row(
        Modifier.widthIn(max = 360.dp).clip(RoundedCornerShape(14.dp)).background(c.card).leftBar(c.accent)
            .clickable { actions.openUrl(og.url.ifBlank { url }) },
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (og.siteName.isNotBlank()) Text(og.siteName, style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
            if (og.title.isNotBlank()) Text(og.title, style = MaterialTheme.typography.titleSmall, color = c.link, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (og.description.isNotBlank()) Text(og.description, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (img != null) {
                Spacer(Modifier.height(4.dp))
                PostMediaImage(img, images[img], maxWidth = 320.dp, maxHeight = 200.dp)
            }
        }
    }
}

// ---- Slack-style message attachments (integrations & plugins) -----------------------------

@Composable
fun MessageAttachments(post: Post) {
    post.attachments.forEach { AttachmentCard(post, it) }
}

private fun parseColor(s: String, fallback: Color): Color = when (s.lowercase()) {
    "good" -> Color(0xFF3DB887)
    "warning" -> Color(0xFFFFBC1F)
    "danger" -> Color(0xFFD24B4E)
    "" -> fallback
    else -> runCatching { Color(android.graphics.Color.parseColor(if (s.startsWith("#")) s else "#$s")) }.getOrDefault(fallback)
}

@Composable
private fun AttachmentCard(post: Post, a: MessageAttachment) {
    val c = Matty.colors
    val actions = LocalActions.current
    val repo = LocalRepo.current
    val images = post.metadata.images.orEmpty()
    Column(Modifier.padding(top = 4.dp)) {
        if (a.pretext.isNotBlank()) MarkdownText(a.pretext, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.widthIn(max = 420.dp).clip(RoundedCornerShape(14.dp)).background(c.card).leftBar(parseColor(a.color, c.selected))) {
            Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (a.authorName.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                    if (a.authorIcon.isNotBlank()) {
                        AsyncImage(model = repo.client.proxiedImage(a.authorIcon, repo.imageProxyEnabled), contentDescription = null, modifier = Modifier.size(18.dp).clip(CircleShape))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(a.authorName, style = MaterialTheme.typography.labelLarge, color = c.title,
                        modifier = if (a.authorLink.isNotBlank()) Modifier.clickable { actions.openShown(a.authorName, a.authorLink) } else Modifier)
                }
                if (a.title.isNotBlank()) Text(
                    a.title, style = MaterialTheme.typography.titleSmall, color = if (a.titleLink.isNotBlank()) c.link else c.title,
                    modifier = if (a.titleLink.isNotBlank()) Modifier.clickable { actions.openShown(a.title, a.titleLink) } else Modifier,
                )
                if (a.text.isNotBlank()) MarkdownText(a.text, style = MaterialTheme.typography.bodyMedium, images = images)
                a.fields?.takeIf { it.isNotEmpty() }?.let { fields ->
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        fields.forEach { f ->
                            Column(if (f.short) Modifier.widthIn(min = 120.dp, max = 180.dp) else Modifier.fillMaxWidth()) {
                                if (f.title.isNotBlank()) Text(f.title, style = MaterialTheme.typography.labelMedium, color = c.textSecondary, fontWeight = FontWeight.SemiBold)
                                MarkdownText(f.valueText, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                if (a.imageUrl.isNotBlank()) PostMediaImage(a.imageUrl, images[a.imageUrl], maxWidth = 360.dp)
                a.actions?.takeIf { it.isNotEmpty() }?.let { acts ->
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        acts.forEach { act -> AttachmentActionView(post, act) }
                    }
                }
                if (a.footer.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                    if (a.footerIcon.isNotBlank()) {
                        AsyncImage(model = repo.client.proxiedImage(a.footerIcon, repo.imageProxyEnabled), contentDescription = null, modifier = Modifier.size(14.dp).clip(CircleShape))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(a.footer, style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                }
            }
            if (a.thumbUrl.isNotBlank()) {
                AsyncImage(model = repo.client.proxiedImage(a.thumbUrl, repo.imageProxyEnabled), contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.padding(12.dp).size(56.dp).clip(RoundedCornerShape(10.dp)))
            }
        }
    }
}

@Composable
private fun AttachmentActionView(post: Post, act: AttachmentAction) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    fun run(option: String?) {
        if (busy || post.id.contains(':')) return
        busy = true
        // The integration often answers by editing this very message, which removes the button
        // while the request is still out: the action must finish, and not be reported as failed.
        scope.launch {
            repo.runAction("That didn't go through") { repo.doPostAction(post.id, act.id, option) }
            busy = false
        }
    }
    if (act.type == "select") {
        var open by remember { mutableStateOf(false) }
        var chosen by remember { mutableStateOf(act.options?.firstOrNull { it.value == act.defaultOption }?.text) }
        Box {
            PillButton(chosen ?: act.name.ifBlank { "Choose…" }, icon = Icons.Outlined.ArrowDropDown, height = 40.dp, loading = busy, enabled = !act.disabled) { open = true }
            DropdownMenu(open, onDismissRequest = { open = false }, shape = RoundedCornerShape(20.dp), containerColor = Matty.colors.pill) {
                val opts = act.options.orEmpty()
                if (opts.isEmpty()) DropdownMenuItem(text = { Text(if (act.dataSource.isNotBlank()) "Pick from ${act.dataSource} on desktop" else "No options") }, onClick = { open = false })
                opts.forEach { o ->
                    DropdownMenuItem(text = { Text(o.text) }, onClick = { open = false; chosen = o.text; run(o.value) })
                }
            }
        }
    } else {
        PillButton(act.name, primary = act.style == "primary" || act.style == "good", height = 40.dp, loading = busy, enabled = !act.disabled) { run(null) }
    }
}

// ---- reactions ----------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReactionsRow(post: Post, onAdd: () -> Unit, onShowWho: (String) -> Unit) {
    val repo = LocalRepo.current
    val c = Matty.colors
    val reactions = post.metadata.reactions.orEmpty()
    if (reactions.isEmpty()) return
    val grouped = remember(reactions) {
        reactions.groupBy { it.emojiName }.entries.sortedBy { e -> e.value.minOf { it.createAt } }
    }
    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        grouped.forEach { (emoji, list) ->
            val mine = list.any { it.userId == repo.myId }
            Row(
                Modifier.height(30.dp).clip(RoundedCornerShape(15.dp))
                    .background(if (mine) c.accent.copy(alpha = 0.18f) else c.pill)
                    .border(1.dp, if (mine) c.accent else Color.Transparent, RoundedCornerShape(15.dp))
                    .combinedClickable(onClick = { repo.toggleReaction(post.id, emoji) }, onLongClick = { onShowWho(emoji) })
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EmojiGlyph(emoji, 18.dp)
                Spacer(Modifier.width(5.dp))
                Text(list.size.toString(), style = MaterialTheme.typography.labelMedium, color = if (mine) c.accent else c.textSecondary, fontWeight = FontWeight.SemiBold)
            }
        }
        Box(
            Modifier.height(30.dp).clip(RoundedCornerShape(15.dp)).background(c.pill).clickable(onClick = onAdd).padding(horizontal = 9.dp),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.AddReaction, "Add reaction", tint = c.textSecondary, modifier = Modifier.size(18.dp)) }
    }
}
