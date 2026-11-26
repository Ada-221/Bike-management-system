
package com.cebp.telemetry;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TelemetryScenariosTest {
    private static final Instant START = Instant.parse("2026-11-22T10:00:00Z");

    @Test
    void normalScenarioIsReproducible() {
        List<TelemetryMeasurement> a = TelemetryScenarios.forName("normal", "veh_001", START);
        List<TelemetryMeasurement> b = TelemetryScenarios.forName("normal", "veh_001", START);
        assertEquals(a, b);
        assertEquals(2, a.size());
        assertEquals(75, a.get(0).batteryPercent());
        assertEquals(74, a.get(1).batteryPercent());
        a.forEach(TelemetryValidator::validate);
    }

    @Test
    void outOfOrderScenarioHasOlderEventAfterNewerEvent() {
        List<TelemetryMeasurement> readings = TelemetryScenarios.forName("out-of-order", "veh_001", START);
        assertEquals(3, readings.size());
        assertTrue(readings.get(2).measuredAt().isBefore(readings.get(1).measuredAt()));
        readings.forEach(TelemetryValidator::validate);
    }

    @Test
    void duplicateScenarioUsesOneReadingForExactKafkaReplay() {
        List<TelemetryMeasurement> readings = TelemetryScenarios.forName("duplicate-event", "veh_001", START);
        assertEquals(1, readings.size());
        TelemetryValidator.validate(readings.get(0));
    }

    @Test
    void scenariosUseConfiguredVehicleId() {
        var readings = TelemetryScenarios.forName("baseline", "veh_123", START);
        assertTrue(readings.stream().allMatch(r -> r.vehicleId().equals("veh_123")));
    }

    @Test
    void rejectsUnknownScenario() {
        assertThrows(IllegalArgumentException.class,
                () -> TelemetryScenarios.forName("not-a-scenario", "veh_001", START));
    }

    // Week 4: Low-battery scenario tests

    @Test
    void lowBatteryScenarioProducesDecreasingBatteryLevels() {
        var readings = TelemetryScenarios.forName("low-battery", "veh_001", START);

        assertEquals(4, readings.size());

        assertEquals(25, readings.get(0).batteryPercent());
        assertEquals(15, readings.get(1).batteryPercent());
        assertEquals(10, readings.get(2).batteryPercent());
        assertEquals(5, readings.get(3).batteryPercent());

        readings.forEach(TelemetryValidator::validate);
    }

    @Test
    void lowBatteryScenarioHasIncreasingTimestamps() {
        var readings = TelemetryScenarios.forName("low-battery", "veh_001", START);

        for (int i = 1; i < readings.size(); i++) {
            assertTrue(readings.get(i).measuredAt()
                    .isAfter(readings.get(i - 1).measuredAt()));
        }

        assertEquals(START, readings.get(3).measuredAt());
    }
}
