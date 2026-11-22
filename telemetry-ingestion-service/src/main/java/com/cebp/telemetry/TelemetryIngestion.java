package com.cebp.telemetry;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
class TelemetryIngestion {
    private static final Pattern VEHICLE_ID = Pattern.compile("veh_[A-Za-z0-9][A-Za-z0-9_-]{2,35}");
    private final VehicleEventSerializer serializer;
    private final KafkaTemplate<String, String> kafka;

    TelemetryIngestion(VehicleEventSerializer serializer, KafkaTemplate<String, String> kafka) {
        this.serializer = serializer;
        this.kafka = kafka;
    }

    CompletableFuture<Void> publish(TelemetryMeasurement measurement)
            throws JsonProcessingException {
        return publish(measurement, Instant.now(), null);
    }

    CompletableFuture<Void> publishReplay(TelemetryMeasurement measurement, Instant publishedAt)
            throws JsonProcessingException {
        String seed = "week3-replay:" + measurement.vehicleId() + ":" + measurement.measuredAt() + ":" + publishedAt;
        return publish(measurement, publishedAt, seed);
    }

    private CompletableFuture<Void> publish(TelemetryMeasurement measurement, Instant publishedAt, String replaySeed)
            throws JsonProcessingException {
        validateIdentity(measurement.vehicleId(), measurement.measuredAt());
        if (measurement.batteryPercent() < 0 || measurement.batteryPercent() > 100) {
            throw new IllegalArgumentException("batteryPercent must be between 0 and 100");
        }
        validateCoordinate(measurement.latitude(), 90, "latitude");
        validateCoordinate(measurement.longitude(), 180, "longitude");

        String correlationId = "corr_" + id(replaySeed, "measurement");
        VehicleEvent battery = new VehicleEvent(
                "evt_" + id(replaySeed, "battery"), "VehicleBatteryUpdated", "1.0",
                measurement.measuredAt(), publishedAt, measurement.vehicleId(),
                correlationId, "telemetry-ingestion-service",
                new VehicleEvent.Battery(measurement.batteryPercent())
        );
        VehicleEvent location = new VehicleEvent(
                "evt_" + id(replaySeed, "location"), "VehicleLocationUpdated", "1.0",
                measurement.measuredAt(), publishedAt, measurement.vehicleId(),
                correlationId, "telemetry-ingestion-service",
                new VehicleEvent.Location(measurement.latitude(), measurement.longitude())
        );

        String batteryJson = serializer.toJson(battery);
        String locationJson = serializer.toJson(location);

        return CompletableFuture.allOf(
                kafka.send(TelemetryApplication.VEHICLE_TOPIC, measurement.vehicleId(), batteryJson),
                kafka.send(TelemetryApplication.VEHICLE_TOPIC, measurement.vehicleId(), locationJson)
        );
    }

    CompletableFuture<Void> publishFault(String vehicleId, Instant measuredAt, String code, String description)
            throws JsonProcessingException {
        validateIdentity(vehicleId, measuredAt);
        validateText(code, 50, "faultCode");
        if (description != null) validateText(description, 500, "description");
        VehicleEvent fault = new VehicleEvent("evt_" + UUID.randomUUID(), "VehicleFaultReported", "1.0",
                measuredAt, Instant.now(), vehicleId, "corr_" + UUID.randomUUID(), "telemetry-ingestion-service",
                new VehicleEvent.Fault(code, description));
        return kafka.send(TelemetryApplication.VEHICLE_TOPIC, vehicleId, serializer.toJson(fault))
                .thenApply(result -> null);
    }

    private static UUID id(String replaySeed, String kind) {
        return replaySeed == null ? UUID.randomUUID()
                : UUID.nameUUIDFromBytes((replaySeed + ":" + kind).getBytes(StandardCharsets.UTF_8));
    }

    private static void validateIdentity(String vehicleId, Instant measuredAt) {
        if (vehicleId == null || !VEHICLE_ID.matcher(vehicleId).matches()) {
            throw new IllegalArgumentException("Invalid telemetry vehicleId");
        }
        if (measuredAt == null) throw new IllegalArgumentException("measuredAt is required");
    }

    private static void validateCoordinate(BigDecimal value, int limit, String name) {
        if (value == null || value.abs().compareTo(BigDecimal.valueOf(limit)) > 0) {
            throw new IllegalArgumentException(name + " must be between " + -limit + " and " + limit);
        }
    }

    private static void validateText(String value, int limit, String name) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > limit) {
            throw new IllegalArgumentException(name + " must contain 1 to " + limit + " characters");
        }
    }
}
