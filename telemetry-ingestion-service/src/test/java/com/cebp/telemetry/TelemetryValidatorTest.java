package com.cebp.telemetry;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TelemetryValidatorTest {
    private static final Instant TIME = Instant.parse("2026-11-22T10:00:00Z");

    private static TelemetryMeasurement reading(String id, Instant time, int battery,
                                                 BigDecimal latitude, BigDecimal longitude) {
        return new TelemetryMeasurement(id, time, battery, latitude, longitude);
    }

    private static TelemetryMeasurement withBattery(int battery) {
        return reading("veh_001", TIME, battery, new BigDecimal("48.2102"),
                new BigDecimal("16.3598"));
    }

    @Test
    void acceptsValidBatteryAndBoundaries() {
        assertDoesNotThrow(() -> TelemetryValidator.validate(withBattery(75)));
        assertDoesNotThrow(() -> TelemetryValidator.validate(withBattery(0)));
        assertDoesNotThrow(() -> TelemetryValidator.validate(withBattery(100)));
    }

    @Test
    void rejectsInvalidBattery() {
        assertThrows(IllegalArgumentException.class, () -> TelemetryValidator.validate(withBattery(-1)));
        assertThrows(IllegalArgumentException.class, () -> TelemetryValidator.validate(withBattery(101)));
    }

    @Test
    void rejectsInvalidIdAndTimestamp() {
        assertThrows(IllegalArgumentException.class, () -> TelemetryValidator.validate(
                reading(null, TIME, 75, BigDecimal.ZERO, BigDecimal.ZERO)));
        assertThrows(IllegalArgumentException.class, () -> TelemetryValidator.validate(
                reading("bike_001", TIME, 75, BigDecimal.ZERO, BigDecimal.ZERO)));
        assertThrows(IllegalArgumentException.class, () -> TelemetryValidator.validate(
                reading("veh_001", null, 75, BigDecimal.ZERO, BigDecimal.ZERO)));
    }

    @Test
    void rejectsInvalidGps() {
        assertThrows(IllegalArgumentException.class, () -> TelemetryValidator.validate(
                reading("veh_001", TIME, 75, new BigDecimal("90.1"), BigDecimal.ZERO)));
        assertThrows(IllegalArgumentException.class, () -> TelemetryValidator.validate(
                reading("veh_001", TIME, 75, BigDecimal.ZERO, new BigDecimal("-180.1"))));
        assertThrows(IllegalArgumentException.class, () -> TelemetryValidator.validate(
                reading("veh_001", TIME, 75, null, BigDecimal.ZERO)));
        assertThrows(IllegalArgumentException.class, () -> TelemetryValidator.validate(
                reading("veh_001", TIME, 75, BigDecimal.ZERO, null)));
    }

    @Test
    void acceptsBoundaryCoordinates() {
        assertDoesNotThrow(() -> TelemetryValidator.validate(reading(
                "veh_001", TIME, 50, new BigDecimal("-90"), new BigDecimal("180"))));
    }

    @Test
    void checksFaultPayloadBeforePublish() {
        assertDoesNotThrow(() -> TelemetryValidator.validateFault("veh_001", TIME, "BRAKE_SENSOR", null));
        assertThrows(IllegalArgumentException.class,
                () -> TelemetryValidator.validateFault("veh_001", TIME, " ", null));
        assertThrows(IllegalArgumentException.class,
                () -> TelemetryValidator.validateFault("veh_001", TIME, "OK", "  "));
    }

    @Test
    void rejectsMissingReading() {
        assertThrows(IllegalArgumentException.class, () -> TelemetryValidator.validate(null));
    }
}
