package com.cebp.telemetry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.regex.Pattern;

/** Producer-side checks matching the current fleet Kafka consumer contract. */
final class TelemetryValidator {
    // The Week 3 fleet consumer accepts 3..36 identifier chars after the prefix.
    private static final Pattern VEHICLE_ID =
            Pattern.compile("^veh_[A-Za-z0-9][A-Za-z0-9_-]{2,35}$");

    private TelemetryValidator() { }

    static void validate(TelemetryMeasurement reading) {
        if (reading == null) throw new IllegalArgumentException("measurement is required");
        validateIdentity(reading.vehicleId(), reading.measuredAt());
        if (reading.batteryPercent() < 0 || reading.batteryPercent() > 100) {
            throw new IllegalArgumentException("batteryPercent must be between 0 and 100");
        }
        coordinate("latitude", reading.latitude(), -90, 90);
        coordinate("longitude", reading.longitude(), -180, 180);
    }

    static void validateFault(String vehicleId, Instant measuredAt, String code, String description) {
        validateIdentity(vehicleId, measuredAt);
        if (code == null || code.isBlank() || code.length() > 50) {
            throw new IllegalArgumentException("faultCode must be nonblank and at most 50 characters");
        }
        if (description != null && (description.isBlank() || description.length() > 500)) {
            throw new IllegalArgumentException("description must be nonblank and at most 500 characters");
        }
    }

    private static void validateIdentity(String vehicleId, Instant measuredAt) {
        if (vehicleId == null || !VEHICLE_ID.matcher(vehicleId).matches()) {
            throw new IllegalArgumentException("vehicleId must match the veh_... format");
        }
        if (measuredAt == null) throw new IllegalArgumentException("measuredAt is required");
    }

    private static void coordinate(String name, BigDecimal value, int min, int max) {
        if (value == null || value.compareTo(BigDecimal.valueOf(min)) < 0
                || value.compareTo(BigDecimal.valueOf(max)) > 0) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
        }
    }
}
