package com.cebp.telemetry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Reproducible battery/location fixtures for producer and consumer checks. */
final class TelemetryScenarios {
    private TelemetryScenarios() { }

    static List<TelemetryMeasurement> forName(String name, String vehicleId, Instant start) {
        Objects.requireNonNull(start, "start");
        return switch (name) {
            case "baseline", "normal" -> List.of(
                    reading(vehicleId, start.minusSeconds(5), 75, "48.2102", "16.3598"),
                    reading(vehicleId, start, 74, "48.2104", "16.3600"));
            case "out-of-order" -> List.of(
                    reading(vehicleId, start.minusSeconds(10), 75, "48.2102", "16.3598"),
                    reading(vehicleId, start, 73, "48.2106", "16.3602"),
                    reading(vehicleId, start.minusSeconds(5), 74, "48.2104", "16.3600"));
            case "duplicate-event" -> List.of(
                    reading(vehicleId, start, 75, "48.2102", "16.3598"));
            default -> throw new IllegalArgumentException("Unknown scenario: " + name
                    + ". Use baseline, normal, out-of-order, duplicate-event, or fault");
        };
    }

    private static TelemetryMeasurement reading(String id, Instant at, int battery, String lat, String lon) {
        return new TelemetryMeasurement(id, at, battery,
                new BigDecimal(lat), new BigDecimal(lon));
    }
}
