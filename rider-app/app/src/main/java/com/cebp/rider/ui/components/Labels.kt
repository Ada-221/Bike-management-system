package com.cebp.rider.ui.components

import androidx.annotation.StringRes
import com.cebp.rider.R
import com.cebp.rider.data.ErrorCodes
import com.cebp.rider.data.RiderException
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
        VehicleType.E_SCOOTER -> R.string.vehicle_type_e_scooter
    }

@get:StringRes
val VehicleStatus.labelRes: Int
    get() = when (this) {
        VehicleStatus.AVAILABLE -> R.string.vehicle_status_available
        VehicleStatus.RENTED -> R.string.vehicle_status_rented
        VehicleStatus.MAINTENANCE_REQUIRED -> R.string.vehicle_status_maintenance
    }

/** What to tell the rider. Chosen from the error type and contract code, never the server text. */
@get:StringRes
val RiderException.messageRes: Int
    get() = when (this) {
        is RiderException.Unreachable -> R.string.error_unreachable
        is RiderException.Unexpected -> R.string.error_unexpected
        is RiderException.Rejected -> when (code) {
            ErrorCodes.NOT_FOUND -> R.string.error_not_found
            ErrorCodes.VEHICLE_NOT_AVAILABLE -> R.string.error_vehicle_not_available
            ErrorCodes.BATTERY_TOO_LOW -> R.string.error_battery_too_low
            ErrorCodes.RIDER_HAS_ACTIVE_RENTAL -> R.string.error_rider_has_active_rental
            // The app built a request the server could not accept: not something the rider can fix.
            ErrorCodes.VALIDATION_ERROR, ErrorCodes.MISSING_IDEMPOTENCY_KEY -> R.string.error_invalid_request
            else -> R.string.error_rejected
        }
    }

fun Instant.toLocalTimeText(): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(this)
