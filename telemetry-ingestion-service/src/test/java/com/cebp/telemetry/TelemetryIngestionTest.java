package com.cebp.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class TelemetryIngestionTest {
    private static final Instant MEASURED_AT = Instant.parse("2026-11-05T20:00:00Z");
    private static final String VEHICLE_ID = "veh_001";
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Mock
    private KafkaTemplate<String, String> kafka;

    @Test
    void rejectsInvalidMeasurementsAndFaultsBeforePublishing() {
        TelemetryIngestion ingestion = new TelemetryIngestion(new VehicleEventSerializer(json), kafka);
        assertThrows(IllegalArgumentException.class, () -> ingestion.publish(
                new TelemetryMeasurement(VEHICLE_ID, MEASURED_AT, 101, BigDecimal.ZERO, BigDecimal.ZERO)));
        assertThrows(IllegalArgumentException.class, () -> ingestion.publish(
                new TelemetryMeasurement(VEHICLE_ID, MEASURED_AT, 80, BigDecimal.ZERO, new BigDecimal("180.1"))));
        assertThrows(IllegalArgumentException.class, () -> ingestion.publish(
                new TelemetryMeasurement(VEHICLE_ID, null, 80, BigDecimal.ZERO, BigDecimal.ZERO)));
        assertThrows(IllegalArgumentException.class, () -> ingestion.publishFault(
                "invalid", MEASURED_AT, "BRAKE_SENSOR", null));
        assertThrows(IllegalArgumentException.class, () -> ingestion.publishFault(
                VEHICLE_ID, MEASURED_AT, " ", null));
        verifyNoInteractions(kafka);
    }

    @Test
    void replayPublishesByteIdenticalDuplicatesWithDistinctBatteryAndLocationIds() throws Exception {
        String topic = TelemetryApplication.VEHICLE_TOPIC;
        when(kafka.send(eq(topic), eq(VEHICLE_ID), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        TelemetryIngestion ingestion = new TelemetryIngestion(new VehicleEventSerializer(json), kafka);
        TelemetryMeasurement measurement = new TelemetryMeasurement(VEHICLE_ID, MEASURED_AT, 0,
                new BigDecimal("-90"), new BigDecimal("180"));
        Instant publishedAt = MEASURED_AT.plusSeconds(6);
        ingestion.publishReplay(measurement, publishedAt).join();
        ingestion.publishReplay(measurement, publishedAt).join();

        ArgumentCaptor<String> records = ArgumentCaptor.forClass(String.class);
        verify(kafka, times(4)).send(eq(topic), eq(VEHICLE_ID), records.capture());
        var values = records.getAllValues();
        assertEquals(values.get(0), values.get(2));
        assertEquals(values.get(1), values.get(3));
        var battery = json.readTree(values.get(0));
        var location = json.readTree(values.get(1));
        assertEquals(MEASURED_AT.toString(), battery.get("measuredAt").textValue());
        assertEquals(publishedAt.toString(), location.get("publishedAt").textValue());
        assertEquals(battery.get("correlationId"), location.get("correlationId"));
        assertNotEquals(battery.get("eventId"), location.get("eventId"));
    }
}
