package com.cebp.rider.data

/** Why a [RiderRepository] request failed. Screens choose the message to show from the type. */
sealed class RiderException(message: String?, cause: Throwable? = null) : Exception(message, cause) {

    /** The backend refused the request. [code] is the contract's `Error.code`, see [ErrorCodes]. */
    class Rejected(val code: String, message: String) : RiderException(message)

    /** The backend could not be reached, or the connection broke. The request can be retried. */
    class Unreachable(cause: Throwable) : RiderException(cause.message, cause)

    /** The backend answered, but not with something the app can use (server error, bad data). */
    class Unexpected(message: String, cause: Throwable? = null) : RiderException(message, cause)
}

/** `Error.code` values from api/fleet-api.yaml that the rider app reacts to. */
object ErrorCodes {
    const val VALIDATION_ERROR = "VALIDATION_ERROR"
    const val MISSING_IDEMPOTENCY_KEY = "MISSING_IDEMPOTENCY_KEY"
    const val NOT_FOUND = "NOT_FOUND"
    const val VEHICLE_NOT_AVAILABLE = "VEHICLE_NOT_AVAILABLE"
    const val BATTERY_TOO_LOW = "BATTERY_TOO_LOW"
    const val RIDER_HAS_ACTIVE_RENTAL = "RIDER_HAS_ACTIVE_RENTAL"
    const val IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED"
}
