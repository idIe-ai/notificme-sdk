package me.notific.cards.wear

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle

/** How long ago something arrived, short enough for the corner of a watch row. */
public sealed interface ShortAge {
    public data object Now : ShortAge

    public data class Minutes(val minutes: Long) : ShortAge

    public data class Hours(val hours: Long) : ShortAge

    /** Earlier this week: the day's name. */
    public data class Weekday(val date: LocalDate) : ShortAge

    /** Older: the date. */
    public data class Date(val date: LocalDate) : ShortAge

    public companion object {
        /** [iso] is an RFC 3339 timestamp from the API; null when it can't be read. */
        public fun of(iso: String, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): ShortAge? {
            val at = runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull() ?: return null
            val ago = Duration.between(at, now)
            return when {
                ago.toMinutes() < 1 -> Now
                ago.toMinutes() < 60 -> Minutes(ago.toMinutes())
                ago.toHours() < 24 -> Hours(ago.toHours())
                ago.toDays() < 6 -> Weekday(at.atZone(zone).toLocalDate())
                else -> Date(at.atZone(zone).toLocalDate())
            }
        }
    }
}

/** "now", "5 min", "3 h", "Mon", "12/11/26" in the app's language. */
@Composable
public fun shortAgeText(iso: String, now: Instant = Instant.now()): String {
    val locale = LocalConfiguration.current.locales[0]
    return when (val age = ShortAge.of(iso, now)) {
        null -> ""
        ShortAge.Now -> stringResource(R.string.nmw_now)
        is ShortAge.Minutes -> stringResource(R.string.nmw_minutes, age.minutes)
        is ShortAge.Hours -> stringResource(R.string.nmw_hours, age.hours)
        is ShortAge.Weekday -> age.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
        is ShortAge.Date -> age.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale))
    }
}
