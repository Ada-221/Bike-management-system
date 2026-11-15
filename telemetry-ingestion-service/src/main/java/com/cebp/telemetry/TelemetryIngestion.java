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

        return CompletableFuture.allOf(
                kafka.send(TelemetryApplication.VEHICLE_TOPIC, measurement.vehicleId(), batteryJson),
                kafka.send(TelemetryApplication.VEHICLE_TOPIC, measurement.vehicleId(), locationJson)
        );
    }
}