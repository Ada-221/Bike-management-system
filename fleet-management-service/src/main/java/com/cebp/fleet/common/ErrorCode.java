package com.cebp.fleet.common;

/** Stable machine-readable error codes (Error.code in fleet-api.yaml). */
public enum ErrorCode {
    VALIDATION_ERROR,
    MISSING_IDEMPOTENCY_KEY,
    NOT_FOUND,
    VEHICLE_NOT_AVAILABLE,
    BATTERY_TOO_LOW,
    RIDER_HAS_ACTIVE_RENTAL,
    IDEMPOTENCY_KEY_REUSED,
    DUPLICATE_VEHICLE_LABEL,
    INVALID_STATUS_TRANSITION,
    PROPOSAL_ALREADY_DECIDED,
    INTERNAL_ERROR
}
