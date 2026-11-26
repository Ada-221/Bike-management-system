
package com.cebp.telemetry;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TelemetrySimulatorTest {

    @Test
    void simulatorUsesConfiguredVehicleAndReplaysOldMeasurementLast() throws Exception {
        TelemetryIngestion ingestion = mock(TelemetryIngestion.class);

        when(ingestion.publish(any(TelemetryMeasurement.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        var simulator = new TelemetrySimulator(
                ingestion, "veh_123", "out-of-order",
                "2026-11-22T10:00:00Z", "");

        simulator.run(null);

        ArgumentCaptor<TelemetryMeasurement> readings =
                ArgumentCaptor.forClass(TelemetryMeasurement.class);

        verify(ingestion, times(3)).publish(readings.capture());

        var sent = readings.getAllValues();

        assertTrue(sent.stream().allMatch(r -> "veh_123".equals(r.vehicleId())));
        assertTrue(sent.get(2).measuredAt().isBefore(sent.get(1).measuredAt()));
        assertEquals(Instant.parse("2026-11-22T10:00:00Z"),
                sent.get(1).measuredAt());
    }

    @Test
    void duplicateScenarioUsesExactEventReplayPath() throws Exception {
        TelemetryIngestion ingestion = mock(TelemetryIngestion.class);

        when(ingestion.publishExactDuplicateEvents(any(TelemetryMeasurement.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        new TelemetrySimulator(
                ingestion, "veh_001", "duplicate-event",
                "2026-11-22T10:00:00Z", "").run(null);

        verify(ingestion).publishExactDuplicateEvents(any(TelemetryMeasurement.class));
        verify(ingestion, never()).publish(any(TelemetryMeasurement.class));
    }

    // Week 4: Continuous telemetry test

    @Test
    void continuousTelemetryPublishesDecreasingBatteryLevels() throws Exception {
        TelemetryIngestion ingestion = mock(TelemetryIngestion.class);

        when(ingestion.publish(any(TelemetryMeasurement.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        var simulator = new TelemetrySimulator(
                ingestion, "veh_001", "continuous", "", "");

        try {
            simulator.publishContinuousReading();
            simulator.publishContinuousReading();
            simulator.publishContinuousReading();

            ArgumentCaptor<TelemetryMeasurement> readings =
                    ArgumentCaptor.forClass(TelemetryMeasurement.class);

            verify(ingestion, times(3)).publish(readings.capture());

            var sent = readings.getAllValues();

            assertEquals(100, sent.get(0).batteryPercent());
            assertEquals(99, sent.get(1).batteryPercent());
            assertEquals(98, sent.get(2).batteryPercent());

            assertTrue(sent.stream()
                    .allMatch(r -> "veh_001".equals(r.vehicleId())));

        } finally {
            simulator.shutdown();
        }
    }
}
