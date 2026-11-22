package com.cebp.telemetry;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
class TelemetryIngestion {
    private final VehicleEventSerializer serializer;
    private final KafkaTemplate<String, String> kafka;

    TelemetryIngestion(VehicleEventSerializer serializer, KafkaTemplate<String, String> kafka) {
        this.serializer = serializer;
        this.kafka = kafka;
    }

    CompletableFuture<Void> publish(TelemetryMeasurement measurement)
            throws JsonProcessingException {
        return publishInternal(measurement, null, false);
    }

    /** Send the exact same JSON (including eventId) twice to test consumer idempotency. */
    CompletableFuture<Void> publishExactDuplicateEvents(TelemetryMeasurement measurement)
            throws JsonProcessingException {
        return publishInternal(measurement, null, true);
    }

    CompletableFuture<Void> publishReplay(TelemetryMeasurement measurement, Instant publishedAt)
            throws JsonProcessingException {
        return publishInternal(measurement, Objects.requireNonNull(publishedAt, "publishedAt"), false);
    }

    private CompletableFuture<Void> publishInternal(TelemetryMeasurement measurement, Instant replayPublishedAt,
                                                    boolean duplicate) throws JsonProcessingException {
        TelemetryValidator.validate(measurement);

        Instant publishedAt = replayPublishedAt == null ? Instant.now() : replayPublishedAt;
        String replaySeed = replayPublishedAt == null ? null
                : "week3-replay:" + measurement.vehicleId() + ":" + measurement.measuredAt() + ":" + publishedAt;
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
        String key = measurement.vehicleId();

        // For a duplicate test, wait for each acknowledgement before replaying
        // the same bytes. New events would have new IDs and test the wrong behavior.
        if (duplicate) {
            return kafka.send(TelemetryApplication.VEHICLE_TOPIC, key, batteryJson)
                    .thenCompose(r -> kafka.send(TelemetryApplication.VEHICLE_TOPIC, key, batteryJson))
                    .thenCompose(r -> kafka.send(TelemetryApplication.VEHICLE_TOPIC, key, locationJson))
                    .thenCompose(r -> kafka.send(TelemetryApplication.VEHICLE_TOPIC, key, locationJson))
                    .thenApply(r -> null);
        }
        return CompletableFuture.allOf(
                kafka.send(TelemetryApplication.VEHICLE_TOPIC, key, batteryJson),
                kafka.send(TelemetryApplication.VEHICLE_TOPIC, key, locationJson)
        );
    }

    private static UUID id(String replaySeed, String kind) {
        return replaySeed == null ? UUID.randomUUID()
                : UUID.nameUUIDFromBytes((replaySeed + ":" + kind).getBytes(StandardCharsets.UTF_8));
    }

    /** Week 3 fleet integration also supports fault reports on fleet.vehicle. */
    CompletableFuture<Void> publishFault(String vehicleId, Instant measuredAt, String code, String description)
            throws JsonProcessingException {
        TelemetryValidator.validateFault(vehicleId, measuredAt, code, description);
        VehicleEvent fault = new VehicleEvent("evt_" + UUID.randomUUID(), "VehicleFaultReported", "1.0",
                measuredAt, Instant.now(), vehicleId, "corr_" + UUID.randomUUID(),
                "telemetry-ingestion-service", new VehicleEvent.Fault(code, description));
        return kafka.send(TelemetryApplication.VEHICLE_TOPIC, vehicleId, serializer.toJson(fault))
                .thenApply(r -> null);
    }
}
