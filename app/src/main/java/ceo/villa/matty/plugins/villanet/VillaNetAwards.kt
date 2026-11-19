package ceo.villa.matty.plugins.villanet

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ceo.villa.matty.R
import ceo.villa.matty.ui.components.Avatar
import ceo.villa.matty.ui.theme.Matty
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * VillaNET awards on a profile. Which rows somebody's record has, in which order and under what
 * title is decided by the plugin (its awards_decide.go) and read from GET /boards, so a profile
 * reads the same here as in the browser without the app knowing a single rule about who holds
 * what. (Until VillaNET 3.6.0 the rules were ported here by hand from the web panel, and both
 * worked the record out of the snapshot.) What is left here is the app's own: each row's artwork,
 * and its figures written the reader's way, a duration and a month in their locale.
 */

/** The badge art from the plugin's artwork/badges, bundled (the plugin serves no images). */
sealed interface AwardMark {
    data class Art(@DrawableRes val res: Int, val studs: Int = 0) : AwardMark
    data object Medal : AwardMark
}

data class AchChip(val mark: AwardMark.Art, val text: String)
data class Tally(val rank: Int, val count: Int, val a11y: String)

data class AwardRow(
    val mark: AwardMark,
    val title: String,
    val meta: String?,
    /** 1 gold, 2 silver, 3 bronze; 0 the neutral tint. */
    val tint: Int,
    val a11y: String,
    val pill: String? = null,
    val chips: List<AchChip> = emptyList(),
    val tallies: List<Tally> = emptyList(),
)

data class FanOf(val fanId: String, val fan: String, val hours: Long)

object VillaNetAwardLogic {
    private val TITLES = mapOf(1 to "Champion", 2 to "Runner-up", 3 to "Third place")
    private val ALLTIME = mapOf(1 to R.drawable.vn_stream_alltime_1, 2 to R.drawable.vn_stream_alltime_2, 3 to R.drawable.vn_stream_alltime_3)
    private val MONTHLY = mapOf(1 to R.drawable.vn_stream_monthly_1, 2 to R.drawable.vn_stream_monthly_2, 3 to R.drawable.vn_stream_monthly_3)
    private val COINS = mapOf(
        "streamed" to listOf(R.drawable.vn_ach_streamed_1, R.drawable.vn_ach_streamed_2, R.drawable.vn_ach_streamed_3, R.drawable.vn_ach_streamed_4, R.drawable.vn_ach_streamed_5, R.drawable.vn_ach_streamed_6),
        "snooped" to listOf(R.drawable.vn_ach_snooped_1, R.drawable.vn_ach_snooped_2, R.drawable.vn_ach_snooped_3, R.drawable.vn_ach_snooped_4, R.drawable.vn_ach_snooped_5, R.drawable.vn_ach_snooped_6),
        "podium" to listOf(R.drawable.vn_ach_podium_1, R.drawable.vn_ach_podium_2, R.drawable.vn_ach_podium_3, R.drawable.vn_ach_podium_4, R.drawable.vn_ach_podium_5, R.drawable.vn_ach_podium_6),
        "thumbs" to listOf(R.drawable.vn_ach_thumbs_1, R.drawable.vn_ach_thumbs_2, R.drawable.vn_ach_thumbs_3, R.drawable.vn_ach_thumbs_4, R.drawable.vn_ach_thumbs_5, R.drawable.vn_ach_thumbs_6),
        "channel" to listOf(R.drawable.vn_ach_channel_1, R.drawable.vn_ach_channel_2, R.drawable.vn_ach_channel_3, R.drawable.vn_ach_channel_4, R.drawable.vn_ach_channel_5, R.drawable.vn_ach_channel_6),
    )

    private fun plural(n: Long, word: String) = "$n $word" + if (n == 1L) "" else "s"

    /** format.ts formatDuration. */
    fun duration(seconds: Long): String {
        val m = seconds / 60
        val h = m / 60
        return when {
            h == 0L -> when { seconds == 0L -> "0m"; m == 0L -> "${seconds}s"; else -> "${m}m" }
            h < 24 && m % 60 != 0L -> "${h}h ${m % 60}m"
            else -> "${h}h"
        }
    }

    /** badges.tsx glyphOf + the coin for a tier (7 and 8 are the tier-5 coin with studs). */
    fun coin(id: String, tier: Int): AwardMark.Art {
        val glyph = if (id in COINS) id else "channel"
        val list = COINS.getValue(glyph)
        return if (tier >= 7) AwardMark.Art(list[4], studs = (tier - 5).coerceAtMost(3))
        else AwardMark.Art(list[tier.coerceIn(1, 6) - 1])
    }

    /** Intl {month:'short', year:'numeric'} equivalent: the locale decides the order ("Aug 2026", "2026年8月"). */
    private fun month(ym: String): String = runCatching {
        val loc = Locale.getDefault()
        val pattern = runCatching { android.text.format.DateFormat.getBestDateTimePattern(loc, "yMMM") }.getOrDefault("MMM yyyy")
        YearMonth.parse(ym).format(DateTimeFormatter.ofPattern(pattern, loc))
    }.getOrDefault(ym).replace(' ', '\u00A0')

    /** The podium's artwork for a place; first place for a place the server could not mean. */
    private fun podium(art: Map<Int, Int>, rank: Int) = AwardMark.Art(art[rank] ?: art.getValue(1))

    /**
     * Somebody's record as the boards have it, each row as the profile draws it: nothing for
     * most people, and nothing until the boards have been read. The rows, their order and their
     * titles are the server's; a kind this app has never heard of is shown by its title alone.
     */
    fun rowsFor(b: VnBoards?, uid: String): List<AwardRow> = b?.people?.get(uid)?.rows.orEmpty().map(::rowOf)

    private fun rowOf(r: VnAwardRow): AwardRow = when (r.kind) {
        "board" -> {
            val time = duration(r.seconds) + if (r.doing.isBlank()) "" else " ${r.doing}"
            when (r.id) {
                "alltime" -> AwardRow(podium(ALLTIME, r.rank), r.title, "${r.board} · $time", r.rank, "${r.title} for airtime, all time")
                "month" -> AwardRow(podium(MONTHLY, r.rank), r.title, "${r.board} · $time", r.rank, "${r.title} for airtime this month")
                else -> AwardRow(AwardMark.Art(R.drawable.vn_snoop_alltime_1), r.title, "${r.board} · $time", r.rank, "${r.title}, all time")
            }
        }
        "clipper" -> AwardRow(AwardMark.Art(R.drawable.vn_clip_best), r.title,
            "All time · " + plural(r.upvotes.toLong(), "upvote") + " on " + plural(r.clips.toLong(), "clip"), r.rank,
            "${r.title}, the most upvoted clips of all time")
        "fan" -> AwardRow(AwardMark.Art(R.drawable.vn_fan_biggest), r.title, plural(r.hours, "hour") + " watching", r.rank, r.title)
        "achievements" -> {
            val chips = r.steps.map { AchChip(coin(it.id, it.tier), it.name) }
            AwardRow(AwardMark.Medal, r.title, null, 0, "${r.title}: " + chips.joinToString { it.text }, chips = chips)
        }
        "months" -> {
            val gold = r.gold
            val tallies = r.tallies.map { t ->
                Tally(t.rank, t.months.size, "${TITLES[t.rank] ?: "Place ${t.rank}"} in " + plural(t.months.size.toLong(), "month") + ": " + t.months.joinToString { month(it) })
            }
            AwardRow(
                podium(MONTHLY, r.rank), r.title,
                if (gold.isNotEmpty()) gold.take(3).joinToString(", ") { month(it) } + (if (gold.size > 3) " +${gold.size - 3} more" else "") else null,
                r.rank,
                // What a screen reader is told of the row: the months won, then the second and
                // third places, which are drawn as pictures.
                (listOf(if (gold.isNotEmpty()) "${r.title}, " + plural(gold.size.toLong(), "month") + ": " + gold.joinToString { month(it) } else r.title) +
                    tallies.map { it.a11y }).joinToString(". "),
                pill = if (gold.size >= 2) "×${gold.size}" else null,
                tallies = tallies,
            )
        }
        else -> AwardRow(AwardMark.Medal, r.title, null, r.rank, r.title)
    }

    /** PopoverLive's separate row: who this streamer's biggest fan is. */
    fun biggestFan(b: VnBoards?, uid: String): FanOf? =
        if (b == null || !b.fansOn) null else b.fans.firstOrNull { it.streamerId == uid }?.let { FanOf(it.fanId, it.fan, it.hours) }

    fun hoursInWords(h: Long) = plural(h, "hour")
}

// ---- drawing ------------------------------------------------------------------------------

private val GOLD = Color(0xFFF2C94C)
private val INK = Color(0xFF102338)

private fun tintFor(rank: Int) = when (rank) {
    1 -> Color(0xFFF2C94C).copy(alpha = 0.18f)
    3 -> Color(0xFFDB944F).copy(alpha = 0.16f)
    else -> Color(0xFFC7CFD9).copy(alpha = 0.13f)
}

/** icons.tsx MedalIcon: a ribbon over a coin, in VillaNET red. */
private val MedalIcon: ImageVector by lazy {
    val stroke = SolidColor(VillaNetRedColor)
    ImageVector.Builder("vnMedal", 24.dp, 24.dp, 24f, 24f).apply {
        listOf("M8 3h8l-2.6 6.6M8 3l2.6 6.6", "M17.5 15.5a5.5 5.5 0 1 1 -11 0a5.5 5.5 0 1 1 11 0", "M12 13v5").forEach { d ->
            addPath(PathParser().parsePathString(d).toNodes(), stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()
}
private val VillaNetRedColor = Color(0xFFC8102E)

@Composable
private fun Mark(mark: AwardMark, size: Dp) {
    when (mark) {
        AwardMark.Medal -> Image(rememberVectorPainter(MedalIcon), null, Modifier.size(size * 0.8f))
        is AwardMark.Art -> Box(Modifier.size(size)) {
            Image(painterResource(mark.res), null, Modifier.fillMaxSize())
            if (mark.studs > 0) Canvas(Modifier.fillMaxSize()) {
                // badges.tsx: a gold stud on the rim for each step past six.
                val k = this.size.width / 128f
                for (i in 0 until mark.studs) {
                    val cx = (64f + (i - (mark.studs - 1) / 2f) * 18f) * k
                    val c = Offset(cx, 113f * k)
                    drawCircle(GOLD, 8.5f * k, c)
                    drawCircle(INK, 8.5f * k, c, style = Stroke(3.5f * k))
                }
            }
        }
    }
}

/**
 * The awards on someone's profile; nothing at all when they have none, and nothing until the
 * boards have been read (the plugin asks as the profile opens: [VillaNetPlugin.boardsLookedAt]).
 */
@Composable
fun VillaNetAwardList(boards: VnBoards?, userId: String, name: String, avatarUrl: (String) -> String) {
    val rows = VillaNetAwardLogic.rowsFor(boards, userId)
    val fan = VillaNetAwardLogic.biggestFan(boards, userId)
    if (rows.isEmpty() && fan == null) return
    val c = Matty.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$name Awards", style = MaterialTheme.typography.labelLarge, color = c.textSecondary, modifier = Modifier.padding(start = 8.dp, top = 4.dp))
        rows.forEach { r -> AwardRowView(r) }
        if (fan != null) {
            Row(
                Modifier.fillMaxWidth().background(GOLD.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "Biggest fan: ${fan.fan}, ${VillaNetAwardLogic.hoursInWords(fan.hours)}" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(32.dp)) {
                    Box(Modifier.size(30.dp).background(GOLD, CircleShape).padding(1.5.dp)) {
                        Avatar(avatarUrl(fan.fanId), fan.fan, 27.dp)
                    }
                    Icon(Icons.Filled.Favorite, null, tint = Color(0xFFE5484D),
                        modifier = Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 4.dp).size(14.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Biggest fan", style = MaterialTheme.typography.titleSmall, color = c.title, maxLines = 1)
                    Text(fan.fan + " · " + VillaNetAwardLogic.hoursInWords(fan.hours), style = MaterialTheme.typography.bodySmall,
                        color = c.text, modifier = Modifier.alpha(0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun AwardRowView(r: AwardRow) {
    val c = Matty.colors
    Row(
        Modifier.fillMaxWidth().background(tintFor(r.tint), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = r.a11y },
        verticalAlignment = if (r.chips.isNotEmpty()) Alignment.Top else Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { Mark(r.mark, 30.dp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.title, style = MaterialTheme.typography.titleSmall, color = c.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (r.pill != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(r.pill, color = INK, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.background(GOLD, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                }
            }
            if (r.meta != null || r.tallies.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    if (r.meta != null) Text(r.meta, style = MaterialTheme.typography.bodySmall, color = c.text, modifier = Modifier.alpha(0.7f))
                    r.tallies.forEach { t ->
                        // Said in the row's own sentence; not read again as a bare "×1".
                        Row(Modifier.clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
                            Mark(AwardMark.Art(if (t.rank == 2) R.drawable.vn_stream_monthly_2 else R.drawable.vn_stream_monthly_3), 16.dp)
                            Spacer(Modifier.width(3.dp))
                            Text("×${t.count}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = c.text)
                        }
                    }
                }
            }
            if (r.chips.isNotEmpty()) {
                Spacer(Modifier.padding(top = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    r.chips.forEach { chip ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Mark(chip.mark, 24.dp)
                            Spacer(Modifier.width(5.dp))
                            Text(chip.text, style = MaterialTheme.typography.bodySmall, color = c.text)
                        }
                    }
                }
            }
        }
    }
}
