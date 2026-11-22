package com.cebp.fleet.vehicle;

/**
 * A telemetry record that can never be applied (malformed JSON, failed validation, conflicting duplicate).
 * Retrying it cannot succeed, so the error handler treats it as non-retryable.
 */
class InvalidTelemetryRecordException extends RuntimeException {
    InvalidTelemetryRecordException(String message, Throwable cause) {
        super(message, cause);
    }
}
