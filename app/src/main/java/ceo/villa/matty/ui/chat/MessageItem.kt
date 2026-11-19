package ceo.villa.matty.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ceo.villa.matty.container
import ceo.villa.matty.data.api.Post
import ceo.villa.matty.data.api.User
import ceo.villa.matty.data.repo.SendState
import ceo.villa.matty.data.session.Settings
import ceo.villa.matty.ui.LocalActions
import ceo.villa.matty.ui.LocalRepo
import ceo.villa.matty.ui.components.Avatar
import ceo.villa.matty.ui.components.nameColor
import ceo.villa.matty.ui.components.leftBar
import ceo.villa.matty.ui.home.BotTag
import ceo.villa.matty.ui.theme.Matty
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** Everything one row needs, precomputed by the list so rows stay cheap. */
@Immutable
data class MessageRowInfo(
    val post: Post,
    val grouped: Boolean,
    val dayHeader: String?,
    val newDivider: Boolean,
    val sendState: SendState?,
    val inThread: Boolean,
)

fun dayLabel(ms: Long): String {
    val cal = Calendar.getInstance()
    val today = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
    cal.timeInMillis = ms
    val that = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
    cal.timeInMillis = System.currentTimeMillis() - 86_400_000
    val yesterday = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
    return when (that) {
        today -> "Today"
        yesterday -> "Yesterday"
        else -> dateLabel(ms, long = true)
    }
}

/**
 * A date in the reader's own format, with the year only when it isn't this year:
 * "November 3" (or "Nov 3") this year, "November 3, 2025" for an older one.
 */
fun dateLabel(ms: Long, long: Boolean = false): String {
    val date = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
    val thisYear = date.year == java.time.LocalDate.now().year
    // Gregorian, so the year shown agrees with the test above; the locale's own digits are kept,
    // matching the times beside it (which java.text formats with them too).
    val locale = java.util.Locale.Builder().setLocale(java.util.Locale.getDefault()).setUnicodeLocaleKeyword("ca", "gregory").build()
    val skeleton = (if (long) "MMMMd" else "MMMd") + if (thisYear) "" else "y"
    runCatching { android.icu.text.DateFormat.getInstanceForSkeleton(skeleton, locale).format(java.util.Date(ms)) }
        .getOrNull()?.let { return it }
    // Off Android (unit tests) or if ICU fails: a fixed pattern, still in the locale's digits.
    val fallback = if (thisYear) (if (long) "MMMM d" else "MMM d") else (if (long) "MMMM d, y" else "MMM d, y")
    return java.time.format.DateTimeFormatter.ofPattern(fallback, locale)
        .withDecimalStyle(java.time.format.DecimalStyle.of(locale)).format(date)
}

fun timeLabel(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

fun sameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

/** Builds row info for a newest-first list of posts (reverse layout). */
fun buildRows(posts: List<Post>, lastViewedAt: Long, myId: String, sendStates: Map<String, SendState>, inThread: Boolean): List<MessageRowInfo> {
    val out = ArrayList<MessageRowInfo>(posts.size)
    // Find the oldest unread post from someone else, for the "NEW" divider.
    val firstNew = if (lastViewedAt > 0) posts.lastOrNull { it.createAt > lastViewedAt && it.userId != myId && !it.isSystem }?.id else null
    for (i in posts.indices) {
        val p = posts[i]
        val older = posts.getOrNull(i + 1)
        val newDay = older == null || !sameDay(older.createAt, p.createAt)
        val grouped = older != null && !newDay && !p.isSystem && !older.isSystem &&
            older.userId == p.userId && p.createAt - older.createAt < 7 * 60_000 &&
            // The name asked for, shown or not: only whether the two posts are alike.
            p.prop("override_username") == older.prop("override_username") && (inThread || p.rootId == older.rootId || p.rootId.isEmpty()) &&
            p.id != firstNew && !(p.rootId.isNotEmpty() && !inThread && p.rootId != older.rootId)
        out += MessageRowInfo(p, grouped, if (newDay) dayLabel(p.createAt) else null, p.id == firstNew, sendStates[p.id], inThread)
    }
    return out
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageRow(
    info: MessageRowInfo,
    users: Map<String, User>,
    settings: Settings,
    onLongPress: (Post) -> Unit,
    onReact: (Post) -> Unit,
    onShowReactions: (Post, String) -> Unit,
) {
    val repo = LocalRepo.current
    val actions = LocalActions.current
    val c = Matty.colors
    val haptics = LocalHapticFeedback.current
    val post = info.post
    val user = users[post.userId]
    val me = users[repo.myId]
    val mentionsMe = remember(post.message, me?.username) {
        val m = post.message
        me != null && (Regex("(^|[^\\w])@${Regex.escape(me.username)}(\\b|$)", RegexOption.IGNORE_CASE).containsMatchIn(m) ||
            Regex("(^|\\s)@(channel|all|here)\\b").containsMatchIn(m)) && post.userId != repo.myId
    }
    var heartPop by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        if (info.dayHeader != null) DayDivider(info.dayHeader)
        if (info.newDivider) NewDivider()
        if (post.isSystem && post.type != "system_ephemeral") {
            SystemRow(post)
            return@Column
        }
        Row(
            Modifier.fillMaxWidth()
                .background(if (mentionsMe) c.mention else Color.Transparent)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress(post)
                    },
                    onDoubleClick = {
                        if (!post.id.contains(':')) {
                            repo.toggleReaction(post.id, settings.doubleTapReaction)
                            heartPop = true
                        }
                    },
                )
                .alpha(if (info.sendState == SendState.Sending) 0.55f else 1f)
                .leftBar(if (mentionsMe) c.mentionBar else Color.Transparent, 3.dp),
        ) {
            Row(Modifier.padding(start = 12.dp, end = 12.dp, top = if (info.grouped) 1.dp else 10.dp, bottom = 1.dp)) {
                Box(Modifier.width(44.dp)) {
                    if (!info.grouped) {
                        // Presence, the LIVE ring, and the mic while they're in voice (VillaNET's Mumble).
                        // None on a webhook or slash-command post, nor a bot posting under another name: the
                        // picture is theirs, the rest would be whoever set them up (as Mattermost's web app).
                        val own = post.byAuthor(repo.postUsernameOverride)
                        val facts = ceo.villa.matty.plugins.personOf(if (own) post.userId else null)
                        val live = facts.live
                        val voice = facts.voice
                        val all = repo.statuses.collectAsState()
                        // Only this author's status: a change to anyone else's doesn't touch this row.
                        val status by remember(post.userId, own) { derivedStateOf { if (own) all.value[post.userId] else null } }
                        val iconUrl = post.overrideIconUrl(repo.postIconOverride)?.let { repo.client.proxiedImage(it, repo.imageProxyEnabled) }
                        // A round ripple without clipping the avatar, so the badges on its edge aren't cut off.
                        Avatar(iconUrl ?: repo.avatarUrl(post.userId), post.overrideUsername(repo.postUsernameOverride) ?: repo.nameOf(user), 40.dp,
                            modifier = Modifier.clickable(
                                interactionSource = null,
                                indication = androidx.compose.material3.ripple(bounded = false, radius = 20.dp),
                            ) { actions.openUser(post.userId) },
                            // Cut out of the row behind it, the tint of a mention included.
                            status = status, ringColor = if (mentionsMe) c.mention.compositeOver(c.background) else c.background,
                            live = live, voice = voice)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    if (!info.grouped) {
                        if (post.rootId.isNotEmpty() && !info.inThread) ReplyQuote(post.rootId)
                        Header(post, user, settings)
                    }
                    androidx.compose.runtime.CompositionLocalProvider(ceo.villa.matty.ui.LocalMessageLongPress provides {
                        if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress(post)
                    }) { Body(post) }
                    if (info.sendState == SendState.Failed) FailedRow(post)
                    ReactionsRow(post, onAdd = { onReact(post) }, onShowWho = { onShowReactions(post, it) })
                    if (!info.inThread && post.rootId.isEmpty() && post.replyCount > 0 && repo.collapsedThreads) ThreadSummary(post)
                }
            }
        }
        if (heartPop) {
            val pop = remember { androidx.compose.animation.core.Animatable(0.2f) }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                pop.animateTo(1f, Matty.PopSpring)
                delay(350)
                heartPop = false
            }
            Box(Modifier.fillMaxWidth().height(0.dp)) {
                EmojiGlyph(settings.doubleTapReaction, 40.dp, Modifier.align(Alignment.Center).offset(y = (-36).dp).scale(pop.value))
            }
        }
    }
}

@Composable
private fun Header(post: Post, user: User?, settings: Settings) {
    val repo = LocalRepo.current
    val actions = LocalActions.current
    val c = Matty.colors
    val renamed = post.overrideUsername(repo.postUsernameOverride)
    val name = renamed ?: repo.nameOf(user)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            name,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
            color = if (settings.colorfulNames && renamed == null) nameColor(post.userId, c.isDark) else c.title,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).clickable { actions.openUser(post.userId) },
        )
        if (user?.isBot == true || post.fromBot) BotTag()
        // The same author as the avatar's LIVE ring: nobody's on a webhook's or a renamed bot's post.
        val marks = headerMarks(user, ceo.villa.matty.plugins.personOf(if (post.byAuthor(repo.postUsernameOverride)) post.userId else null))
        marks.emoji?.let { emoji ->
            Spacer(Modifier.width(4.dp))
            EmojiGlyph(emoji, 16.dp)
        }
        if (marks.live) LiveChip()
        Spacer(Modifier.width(8.dp))
        Text(
            (if (sameDay(post.createAt, System.currentTimeMillis())) "" else dayLabel(post.createAt) + " · ") + timeLabel(post.createAt),
            style = MaterialTheme.typography.labelSmall, color = c.textTertiary, maxLines = 1,
        )
        if (post.isPinned) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Outlined.PushPin, "Pinned", tint = c.away, modifier = Modifier.size(13.dp))
        }
    }
}

/** Posted by its author as themselves: not by a webhook or slash command, nor a bot under another name. */
private fun Post.byAuthor(nameOverride: Boolean): Boolean = overrideUsername(nameOverride) == null && prop("from_webhook") != "true"

/** What sits beside an author's name: their status emoji, and whether they're live. */
internal data class HeaderMarks(val emoji: String?, val live: Boolean)

/**
 * While VillaNET holds their status for its live badge, its emoji only repeats the Live chip, so it
 * goes; a status of their own (or the birthday cake) keeps its place, with the chip after it.
 */
internal fun headerMarks(user: User?, facts: ceo.villa.matty.plugins.PersonFacts) = HeaderMarks(
    emoji = ceo.villa.matty.ui.home.ownStatus(user, facts)?.emoji?.takeIf { it.isNotBlank() },
    live = facts.live,
)

/**
 * "Live" in a small red chip after a name: quiet, on a faint wash of the LIVE red, and no taller
 * than the name beside it. On black it's the lighter red VillaNET writes on black with.
 */
@Composable
private fun LiveChip() {
    val dark = Matty.colors.isDark
    val red = if (dark) ceo.villa.matty.ui.theme.VillaNetRedOnBlack else ceo.villa.matty.ui.theme.VillaNetRed
    Text(
        "Live",
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium),
        color = red, maxLines = 1, softWrap = false,
        modifier = Modifier.padding(start = 6.dp)
            // A little fainter on white, where the full 16% would take the text below 4.5:1.
            .background(red.copy(alpha = if (dark) 0.16f else 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
fun Body(post: Post) {
    val repo = LocalRepo.current
    val host = LocalActions.current as? ceo.villa.matty.plugins.PluginHost
    val c = Matty.colors
    val context = LocalContext.current
    val settings = context.container.settings.state.value
    val plugin = context.container.plugins.forPostType(post.type)
    val images = post.metadata.images.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (post.type == "system_ephemeral") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Visibility, null, tint = c.textTertiary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Only visible to you", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
            }
        }
        if (plugin != null && host != null) {
            plugin.PostContent(post, host)
            return@Column
        }
        if (post.message.isNotBlank()) MarkdownText(post.message, images = images, edited = post.editAt > 0)
        val files = post.metadata.files.orEmpty()
        if (files.isNotEmpty()) FileAttachments(files)
        MessageAttachments(post)
        if (settings.linkPreviews) {
            post.metadata.embeds.orEmpty().forEach { e ->
                // Inline markdown images already render; only embed what the text didn't.
                if (e.type == "image" && post.message.contains("](${e.url}")) return@forEach
                EmbedView(e, images)
            }
        }
        if (post.type.startsWith("custom_") && plugin == null) {
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Extension, null, tint = c.textTertiary, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text("Sent by a plugin Matty doesn't draw natively yet", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
            }
        }
    }
    @Suppress("UNUSED_EXPRESSION") repo
}

@Composable
private fun ReplyQuote(rootId: String) {
    val repo = LocalRepo.current
    val actions = LocalActions.current
    val c = Matty.colors
    val posts by repo.posts.collectAsState()
    val root = posts[rootId]
    androidx.compose.runtime.LaunchedEffect(rootId) {
        if (root == null) runCatching { repo.client.post(rootId) }.onSuccess { p ->
            repo.posts.value = repo.posts.value + (p.id to p); repo.ensureUsers(listOf(p.userId))
        }
    }
    Row(
        Modifier.padding(bottom = 2.dp).clip(RoundedCornerShape(8.dp)).clickable { actions.openThread(rootId) }.padding(vertical = 2.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Outlined.Reply, null, tint = c.textTertiary, modifier = Modifier.size(14.dp).scale(scaleX = -1f, scaleY = 1f))
        Spacer(Modifier.width(4.dp))
        if (root != null) {
            Avatar(repo.avatarUrl(root.userId), repo.nameOf(root.userId), 16.dp)
            Spacer(Modifier.width(4.dp))
            Text(repo.nameOf(root.userId), style = MaterialTheme.typography.labelMedium, color = c.textSecondary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Text(plainPreview(root, repo), style = MaterialTheme.typography.labelMedium, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Text("Replying in a thread", style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
        }
    }
}

@Composable
private fun ThreadSummary(post: Post) {
    val repo = LocalRepo.current
    val actions = LocalActions.current
    val c = Matty.colors
    Row(
        Modifier.padding(top = 6.dp).clip(RoundedCornerShape(14.dp)).background(c.card).clickable { actions.openThread(post.id) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        post.participants.orEmpty().take(3).forEachIndexed { i, u ->
            Avatar(repo.avatarUrl(u.id), repo.nameOf(u), 20.dp, modifier = Modifier.offset(x = (-6 * i).dp))
        }
        Spacer(Modifier.width(4.dp))
        Text("${post.replyCount} ${if (post.replyCount == 1) "reply" else "replies"}", color = c.accent, style = MaterialTheme.typography.labelLarge)
        if (post.lastReplyAt > 0) {
            Spacer(Modifier.width(8.dp))
            Text("Last reply " + (if (sameDay(post.lastReplyAt, System.currentTimeMillis())) timeLabel(post.lastReplyAt) else dayLabel(post.lastReplyAt)),
                color = c.textTertiary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun FailedRow(post: Post) {
    val repo = LocalRepo.current
    val c = Matty.colors
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.ErrorOutline, null, tint = c.error, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Not sent.", color = c.error, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(8.dp))
        Text("Retry", color = c.link, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable { repo.retry(post.id) })
        Spacer(Modifier.width(12.dp))
        Text("Delete", color = c.textSecondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable { repo.removeLocal(post.id) })
    }
}

@Composable
private fun SystemRow(post: Post) {
    val c = Matty.colors
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Info, null, tint = c.textTertiary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(12.dp))
        MarkdownText(post.message, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.weight(1f))
        Text(timeLabel(post.createAt), style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
    }
}

@Composable
private fun DayDivider(label: String) {
    val c = Matty.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = c.divider)
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.textSecondary, modifier = Modifier.padding(horizontal = 10.dp))
        HorizontalDivider(Modifier.weight(1f), color = c.divider)
    }
}

@Composable
private fun NewDivider() {
    val c = Matty.colors
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = c.dnd)
        Text("NEW", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.background(c.dnd, RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
    }
}
