package com.cebp.rider.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cebp.rider.R
import com.cebp.rider.model.MIN_RENTABLE_BATTERY_PERCENT
import com.cebp.rider.model.Vehicle
import com.cebp.rider.model.VehicleStatus
import java.time.Duration
import java.time.Instant

/** A battery reading older than this may no longer match the vehicle. */
val STALE_BATTERY_AFTER: Duration = Duration.ofMinutes(15)

private val BatteryHigh = Color(0xFF2E7D32)
private val BatteryMedium = Color(0xFFB26A00)

/** "Battery: 92%" with a bar coloured by level: green from 50%, amber from 20%, red below. */
@Composable
fun BatteryLevel(percent: Int, modifier: Modifier = Modifier) {
    val color = when {
        percent >= 50 -> BatteryHigh
        percent >= MIN_RENTABLE_BATTERY_PERCENT -> BatteryMedium
        else -> MaterialTheme.colorScheme.error
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.vehicle_battery, percent))
        LinearProgressIndicator(
            progress = { percent.coerceIn(0, 100) / 100f },
            color = color,
            modifier = Modifier.width(56.dp).height(6.dp),
        )
    }
}

/** How old a reading is, rounded for display. */
internal sealed interface Age {
    data object JustNow : Age
    data class Minutes(val count: Long) : Age
    data class Hours(val count: Long) : Age
    data class Days(val count: Long) : Age
}

/** Rounds [elapsed] down to whole minutes, hours or days. Under a minute, or negative, is "just now". */
internal fun ageOf(elapsed: Duration): Age = when {
    elapsed < Duration.ofMinutes(1) -> Age.JustNow
    elapsed < Duration.ofHours(1) -> Age.Minutes(elapsed.toMinutes())
    elapsed < Duration.ofDays(1) -> Age.Hours(elapsed.toHours())
    else -> Age.Days(elapsed.toDays())
}

/**
 * When the battery was last measured: "Battery measured 3 minutes ago", or a warning when the
 * reading is older than [STALE_BATTERY_AFTER]. Shows nothing if the vehicle never reported it.
 */
@Composable
fun BatteryFreshness(measuredAt: Instant?, now: Instant = Instant.now()) {
    if (measuredAt == null) return
    val elapsed = Duration.between(measuredAt, now)
    val age = when (val a = ageOf(elapsed)) {
        Age.JustNow -> null
        is Age.Minutes -> pluralStringResource(R.plurals.duration_minutes, a.count.toInt(), a.count)
        is Age.Hours -> pluralStringResource(R.plurals.duration_hours, a.count.toInt(), a.count)
        is Age.Days -> pluralStringResource(R.plurals.duration_days, a.count.toInt(), a.count)
    }
    val stale = elapsed > STALE_BATTERY_AFTER
    Text(
        text = when {
            age == null -> stringResource(R.string.battery_measured_just_now)
            stale -> stringResource(R.string.battery_measured_stale, age)
            else -> stringResource(R.string.battery_measured_ago, age)
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (stale) BatteryMedium else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** "27 m", "1 km" or "1.5 km". */
@Composable
fun distanceText(meters: Int): String = when {
    meters < 1000 -> stringResource(R.string.distance_meters, meters)
    meters % 1000 == 0 -> stringResource(R.string.distance_km_whole, meters / 1000)
    else -> stringResource(R.string.distance_km, meters / 1000.0)
}

/** Why the rider cannot rent this vehicle. Only meaningful when it is not rentable. */
@get:StringRes
val Vehicle.notRentableReasonRes: Int
    get() = when {
        status == VehicleStatus.RENTED -> R.string.reason_rented
        batteryPercent < MIN_RENTABLE_BATTERY_PERCENT -> R.string.reason_low_battery
        status == VehicleStatus.MAINTENANCE_REQUIRED -> R.string.reason_maintenance
        else -> R.string.reason_unavailable
    }
