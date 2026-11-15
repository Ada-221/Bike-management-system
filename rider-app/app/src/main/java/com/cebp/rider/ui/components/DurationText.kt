package com.cebp.rider.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.cebp.rider.R
import java.time.Duration

/** The non-zero hours, minutes and seconds of a duration, largest first. */
internal data class DurationParts(val hours: Long, val minutes: Long, val seconds: Long)

/** Splits [duration] into whole hours, minutes and seconds. Negative durations count as zero. */
internal fun durationParts(duration: Duration): DurationParts {
    val totalSeconds = duration.seconds.coerceAtLeast(0)
    return DurationParts(
        hours = totalSeconds / 3600,
        minutes = totalSeconds % 3600 / 60,
        seconds = totalSeconds % 60,
    )
}

/**
 * A ride length such as "42 seconds", "1 minute and 36 seconds" or "1 hour, 5 minutes and 3 seconds".
 * Zero parts are left out; a duration under one second reads "0 seconds".
 */
@Composable
fun durationText(duration: Duration): String {
    val parts = durationParts(duration)
    val words = buildList {
        if (parts.hours > 0) add(plural(R.plurals.duration_hours, parts.hours))
        if (parts.minutes > 0) add(plural(R.plurals.duration_minutes, parts.minutes))
        if (parts.seconds > 0 || isEmpty()) add(plural(R.plurals.duration_seconds, parts.seconds))
    }
    return when (words.size) {
        1 -> words[0]
        2 -> stringResource(R.string.duration_two_parts, words[0], words[1])
        else -> stringResource(R.string.duration_three_parts, words[0], words[1], words[2])
    }
}

@Composable
private fun plural(id: Int, count: Long): String =
    pluralStringResource(id, count.toInt(), count)
