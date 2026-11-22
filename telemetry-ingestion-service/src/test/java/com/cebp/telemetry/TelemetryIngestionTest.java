package com.cebp.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TelemetryIngestionTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static TelemetryMeasurement reading(int battery) {
        return new TelemetryMeasurement("veh_001", Instant.parse("2026-11-22T10:00:00Z"),
                battery, new BigDecimal("48.2102"), new BigDecimal("16.3598"));
    }

    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, String> kafkaMock() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(eq(TelemetryApplication.VEHICLE_TOPIC), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        return kafka;
    }

    @Test
    void validMeasurementPublishesSeparateBatteryAndLocationEvents() throws Exception {
        KafkaTemplate<String, String> kafka = kafkaMock();
        var ingestion = new TelemetryIngestion(new VehicleEventSerializer(MAPPER), kafka);
        ingestion.publish(reading(75)).join();

        ArgumentCaptor<String> data = ArgumentCaptor.forClass(String.class);
        verify(kafka, times(2)).send(eq("fleet.vehicle"), eq("veh_001"), data.capture());
        List<String> sent = data.getAllValues();
        JsonNode battery = MAPPER.readTree(sent.get(0));
        JsonNode location = MAPPER.readTree(sent.get(1));
        assertEquals("VehicleBatteryUpdated", battery.get("eventType").asText());
        assertEquals("VehicleLocationUpdated", location.get("eventType").asText());
        assertEquals(75, battery.path("payload").get("batteryPercent").asInt());
        assertEquals("telemetry-ingestion-service", battery.get("producer").asText());
        assertEquals(battery.get("correlationId"), location.get("correlationId"));
        assertNotEquals(battery.get("eventId"), location.get("eventId"));
    }

    @Test
    void outOfOrderMeasurementsKeepTheirOriginalMeasurementTimestamps() throws Exception {
        KafkaTemplate<String, String> kafka = kafkaMock();
        var ingestion = new TelemetryIngestion(new VehicleEventSerializer(MAPPER), kafka);
        for (TelemetryMeasurement reading : TelemetryScenarios.forName("out-of-order", "veh_001",
                Instant.parse("2026-11-22T10:00:00Z"))) {
            ingestion.publish(reading).join();
        }

        ArgumentCaptor<String> data = ArgumentCaptor.forClass(String.class);
        verify(kafka, times(6)).send(eq("fleet.vehicle"), eq("veh_001"), data.capture());
        var messages = data.getAllValues();
        Instant second = Instant.parse(MAPPER.readTree(messages.get(2)).get("measuredAt").asText());
        Instant third = Instant.parse(MAPPER.readTree(messages.get(4)).get("measuredAt").asText());
        assertTrue(third.isBefore(second));
    }

    @Test
    void rejectsInvalidMeasurementBeforeAnyKafkaSend() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        var ingestion = new TelemetryIngestion(new VehicleEventSerializer(MAPPER), kafka);
        assertThrows(IllegalArgumentException.class, () -> ingestion.publish(reading(120)));
        verifyNoInteractions(kafka);
    }

    @Test
    void validFaultUsesTheWeek3ConsumerContract() throws Exception {
        KafkaTemplate<String, String> kafka = kafkaMock();
        var ingestion = new TelemetryIngestion(new VehicleEventSerializer(MAPPER), kafka);
        ingestion.publishFault("veh_001", Instant.parse("2026-11-22T10:00:00Z"),
                "BRAKE_SENSOR", "Simulated brake sensor fault").join();

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq("fleet.vehicle"), eq("veh_001"), json.capture());
        JsonNode event = MAPPER.readTree(json.getValue());
        assertEquals("VehicleFaultReported", event.get("eventType").asText());
        assertEquals("1.0", event.get("schemaVersion").asText());
        assertEquals("BRAKE_SENSOR", event.path("payload").get("faultCode").asText());
        assertEquals("telemetry-ingestion-service", event.get("producer").asText());
    }

    @Test
    void invalidFaultIsRejectedWithoutSending() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        var ingestion = new TelemetryIngestion(new VehicleEventSerializer(MAPPER), kafka);
        assertThrows(IllegalArgumentException.class, () -> ingestion.publishFault(
                "veh_001", Instant.parse("2026-11-22T10:00:00Z"), "", null));
        verifyNoInteractions(kafka);
    }

    @Test
    void duplicateScenarioReplaysExactJsonAndEventIds() throws Exception {
        KafkaTemplate<String, String> kafka = kafkaMock();
        var ingestion = new TelemetryIngestion(new VehicleEventSerializer(MAPPER), kafka);
        ingestion.publishExactDuplicateEvents(reading(75)).join();
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(kafka, times(4)).send(eq("fleet.vehicle"), eq("veh_001"), json.capture());
        List<String> sent = json.getAllValues();
        assertEquals(sent.get(0), sent.get(1)); // exact duplicate battery eventId
        assertEquals(sent.get(2), sent.get(3)); // exact duplicate location eventId
    }
}
