package com.cebp.telemetry;

import java.time.Instant;
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
        return publishInternal(measurement, false);
    }

    /** Send the exact same JSON (including eventId) twice to test consumer idempotency. */
    CompletableFuture<Void> publishExactDuplicateEvents(TelemetryMeasurement measurement)
            throws JsonProcessingException {
        return publishInternal(measurement, true);
    }

    private CompletableFuture<Void> publishInternal(TelemetryMeasurement measurement, boolean duplicate)
            throws JsonProcessingException {
        TelemetryValidator.validate(measurement); // reject before ANY Kafka sends

        String correlationId = "corr_" + UUID.randomUUID();
        Instant publishedAt = Instant.now();

        VehicleEvent battery = new VehicleEvent(
                "evt_" + UUID.randomUUID(), "VehicleBatteryUpdated", "1.0",
                measurement.measuredAt(), publishedAt, measurement.vehicleId(),
                correlationId, "telemetry-ingestion-service",
                new VehicleEvent.Battery(measurement.batteryPercent())
        );
        VehicleEvent location = new VehicleEvent(
                "evt_" + UUID.randomUUID(), "VehicleLocationUpdated", "1.0",
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
