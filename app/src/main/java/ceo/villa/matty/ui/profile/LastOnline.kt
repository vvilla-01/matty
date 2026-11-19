package ceo.villa.matty.ui.profile

import ceo.villa.matty.ui.chat.dateLabel
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS

/**
 * Whether a profile says when somebody was last online: only for a person (not a bot, not you)
 * who is offline, and only when the server shares the time ([lastActiveMs] above 0).
 */
internal fun showsLastOnline(status: String?, isBot: Boolean, mine: Boolean, lastActiveMs: Long): Boolean =
    status == "offline" && !isBot && !mine && lastActiveMs > 0

/**
 * "Last online 5 min ago": "just now" under a minute, then minutes and hours under a day. From a
 * day on it counts calendar days in [zone] (the phone's own, by default): "yesterday" is the day
 * before today on this phone's calendar, then days within the week, and after that the date as
 * the app writes dates ("Nov 12", with the year when it is another). Elapsed time would call
 * Monday 23:30 "yesterday" when read on Wednesday at 00:30. A time ahead of this device's clock
 * is "just now".
 */
internal fun lastOnlineWords(lastActiveMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val ago = nowMs - lastActiveMs
    val words = when {
        ago < MINUTE_MS -> "just now"
        ago < HOUR_MS -> "${ago / MINUTE_MS} min ago"
        ago < DAY_MS -> "${ago / HOUR_MS} hr ago"
        else -> {
            val days = ChronoUnit.DAYS.between(
                Instant.ofEpochMilli(lastActiveMs).atZone(zone).toLocalDate(),
                Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            )
            when {
                // On a 25-hour day a full day can pass within one calendar date: still hours, not "0 days".
                days < 1 -> "${ago / HOUR_MS} hr ago"
                days == 1L -> "yesterday"
                days < 7 -> "$days days ago"
                else -> dateLabel(lastActiveMs)
            }
        }
    }
    return "Last online $words"
}
