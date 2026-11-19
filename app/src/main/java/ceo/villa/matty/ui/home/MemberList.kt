package ceo.villa.matty.ui.home

import androidx.compose.foundation.background
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ceo.villa.matty.container
import ceo.villa.matty.data.api.User
import ceo.villa.matty.data.emoji.EmojiStore
import ceo.villa.matty.ui.LocalActions
import ceo.villa.matty.ui.LocalRepo
import ceo.villa.matty.ui.components.Avatar
import ceo.villa.matty.ui.components.CenteredLoading
import ceo.villa.matty.ui.components.nameColor
import ceo.villa.matty.ui.theme.Matty

/** Discord's member list: grouped by presence, with custom statuses and LIVE rings. */
@Composable
fun MemberList(channelId: String?, modifier: Modifier = Modifier) {
    val repo = LocalRepo.current
    val c = Matty.colors
    // The list as last read shows at once; opening the panel brings it up to date behind it.
    val members by repo.channelMemberIds.collectAsState()
    LaunchedEffect(channelId) {
        if (channelId != null) repo.refreshChannelMembers(channelId, maxAgeMs = 15_000)
    }
    val statuses by repo.statuses.collectAsState()
    val users by repo.users.collectAsState()
    Box(modifier.fillMaxSize().padding(start = 8.dp).statusBarsPadding().padding(top = 8.dp)
        .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(c.card)) {
        val l = channelId?.let { members[it] }
        if (l == null) { CenteredLoading(); return@Box }
        val fresh = l.mapNotNull { users[it] }.filter { it.deleteAt == 0L }
        val groups = listOf(
            "Online" to fresh.filter { statuses[it.id] == "online" },
            "Away" to fresh.filter { statuses[it.id] == "away" },
            "Do not disturb" to fresh.filter { statuses[it.id] == "dnd" },
            "Offline" to fresh.filter { (statuses[it.id] ?: "offline") == "offline" },
        ).filter { it.second.isNotEmpty() }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 12.dp, bottom = 110.dp + ceo.villa.matty.ui.BottomChrome.barSpace)) {
            item {
                Text("Members — ${fresh.size}", style = MaterialTheme.typography.titleMedium, color = c.title, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
            groups.forEach { (label, people) ->
                item(key = "h:$label") {
                    Text("${label.uppercase()} — ${people.size}", style = MaterialTheme.typography.labelMedium, color = c.textSecondary,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
                }
                items(people.sortedBy { repo.nameOf(it).lowercase() }, key = { "$label:${it.id}" }) { u -> MemberRow(u, statuses[u.id] ?: "offline") }
            }
        }
    }
}

@Composable
fun MemberRow(u: User, status: String) {
    val repo = LocalRepo.current
    val actions = LocalActions.current
    val c = Matty.colors
    val settings by LocalContext.current.container.settings.state.collectAsState()
    // On the air, in voice, in a game: one lookup, which only changes when this person's facts do.
    val facts = ceo.villa.matty.plugins.personOf(u.id)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(Matty.RowShape).clickable { actions.openUser(u.id) }
            .padding(horizontal = 8.dp, vertical = 6.dp).alpha(if (status == "offline") 0.55f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(repo.avatarUrl(u.id), repo.nameOf(u), 40.dp, status = status, ringColor = c.card, live = facts.live, voice = facts.voice)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(repo.nameOf(u), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (settings.colorfulNames) nameColor(u.id, c.isDark) else c.title)
                if (u.isBot) BotTag()
            }
            StatusLine(ownStatus(u, facts), facts.live, facts.activity, c.textSecondary)
        }
    }
}

/** What [StatusLine] says, worked out apart from drawing it. */
sealed interface StatusLineContent {
    /** Their custom status, with a controller after it while [activity] is going on. */
    data class Custom(val text: String, val activity: ceo.villa.matty.plugins.UserActivity?) : StatusLineContent
    /** "Playing **ELDEN RING**" or "Streaming **ELDEN RING**", after [lead] (an emoji-only status). */
    data class Activity(val lead: String, val activity: ceo.villa.matty.plugins.UserActivity) : StatusLineContent
    /** In a game nobody has named. */
    data class InGame(val lead: String) : StatusLineContent
    data class Live(val lead: String) : StatusLineContent
    data object None : StatusLineContent
}

/**
 * The line under someone's name, Discord's way: their custom status when it has words (with a
 * controller after it while they're in a game), otherwise the game they're in ("Playing
 * **ELDEN RING**", "Streaming …" when their stream is that game, "In a game" when nobody says
 * which), otherwise "Live now"; a status that's only an emoji ([emoji] as text) leads whichever of
 * those it is. Online, away and the rest are the avatar's dot, never words here.
 *
 * The status here is the person's own. While somebody broadcasts, VillaNET borrows their custom
 * status for its live badge ("🔴 Live now", the emoji beside their name), which only repeats the
 * LIVE ring on their picture: callers leave that one out (see [ownStatus]), so a streamer's line
 * says the game they're in, or "Live now" once, rather than the badge's words.
 */
fun statusLineOf(emoji: String?, words: String?, live: Boolean, activity: ceo.villa.matty.plugins.UserActivity?): StatusLineContent {
    val lead = emoji?.let { "$it " }.orEmpty()
    return when {
        words != null || (emoji != null && activity == null && !live) -> StatusLineContent.Custom(listOfNotNull(emoji, words).joinToString(" "), activity)
        activity != null && activity.title.isNotEmpty() -> StatusLineContent.Activity(lead, activity)
        activity != null -> StatusLineContent.InGame(lead)
        live -> StatusLineContent.Live(lead)
        else -> StatusLineContent.None
    }
}

/**
 * [user]'s custom status when it's their own: not while a plugin is holding it for a badge
 * (VillaNET's live badge), which every line about that person would only repeat.
 */
fun ownStatus(user: User?, facts: ceo.villa.matty.plugins.PersonFacts): ceo.villa.matty.data.api.CustomStatus? =
    if (user == null || facts.holdsStatus) null else user.customStatus

private fun lineOf(custom: ceo.villa.matty.data.api.CustomStatus?, live: Boolean, activity: ceo.villa.matty.plugins.UserActivity?) =
    statusLineOf(custom?.let { EmojiStore.unicode(it.emoji) }, custom?.text?.ifBlank { null }, live, activity)

/** [statusLineOf], drawn. Draws nothing when there's nothing to say. */
@Composable
fun StatusLine(
    custom: ceo.villa.matty.data.api.CustomStatus?,
    live: Boolean,
    activity: ceo.villa.matty.plugins.UserActivity?,
    color: androidx.compose.ui.graphics.Color,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodySmall,
) {
    val c = Matty.colors
    when (val line = lineOf(custom, live, activity)) {
        is StatusLineContent.Custom -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(line.text, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (line.activity != null) {
                Spacer(Modifier.width(4.dp))
                // A controller for a game being played; what is being streamed may be a movie.
                val streamed = line.activity.kind == ceo.villa.matty.plugins.UserActivity.Kind.Streaming
                Icon(
                    if (streamed) androidx.compose.material.icons.Icons.Outlined.LiveTv else androidx.compose.material.icons.Icons.Outlined.SportsEsports,
                    activityLabel(line.activity), tint = color, modifier = Modifier.size(14.dp),
                )
            }
        }
        is StatusLineContent.Activity -> {
            val streaming = line.activity.kind == ceo.villa.matty.plugins.UserActivity.Kind.Streaming
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    append(line.lead)
                    append(if (streaming) "Streaming " else "Playing ")
                    withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold)) { append(line.activity.title) }
                },
                style = style, color = if (streaming) c.dnd else color, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        is StatusLineContent.InGame -> Text(line.lead + "In a game", style = style, color = color, maxLines = 1)
        is StatusLineContent.Live -> Text(line.lead + "Live now", style = style, color = c.dnd, maxLines = 1)
        StatusLineContent.None -> {}
    }
}

/** Whether [StatusLine] has anything to draw for these (a status that's only a server's own emoji doesn't). */
fun hasStatusLine(custom: ceo.villa.matty.data.api.CustomStatus?, live: Boolean, activity: ceo.villa.matty.plugins.UserActivity?): Boolean =
    lineOf(custom, live, activity) != StatusLineContent.None

/** "Playing ELDEN RING on Steam", for a screen reader. */
fun activityLabel(a: ceo.villa.matty.plugins.UserActivity): String = when {
    a.title.isEmpty() -> "In a game"
    // Streamed through the Matty plugin (or Twitch), whichever it came from: not "on Steam".
    a.kind == ceo.villa.matty.plugins.UserActivity.Kind.Streaming -> "Streaming ${a.title}"
    else -> "Playing ${a.title}" + (if (a.source.isNotEmpty()) " on ${a.source}" else "")
}

@Composable
fun BotTag() {
    Text("BOT", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Matty.colors.onAccent,
        modifier = Modifier.padding(start = 6.dp).background(Matty.colors.accent, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
}
