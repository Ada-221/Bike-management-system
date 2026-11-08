package com.cebp.rider.ui.components

import androidx.annotation.StringRes
import com.cebp.rider.R
import com.cebp.rider.model.VehicleStatus
import com.cebp.rider.model.VehicleType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@get:StringRes
val VehicleType.labelRes: Int
    get() = when (this) {
        VehicleType.E_BIKE -> R.string.vehicle_type_e_bike
        VehicleType.SCOOTER -> R.string.vehicle_type_scooter
    }

@get:StringRes
val VehicleStatus.labelRes: Int
    get() = when (this) {
        VehicleStatus.AVAILABLE -> R.string.vehicle_status_available
        VehicleStatus.RENTED -> R.string.vehicle_status_rented
        VehicleStatus.MAINTENANCE -> R.string.vehicle_status_maintenance
    }

fun Instant.toLocalTimeText(): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(this)
