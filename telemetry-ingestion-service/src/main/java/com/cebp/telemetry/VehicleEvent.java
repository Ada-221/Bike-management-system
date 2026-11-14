package com.cebp.telemetry;

import java.math.BigDecimal;
import java.time.Instant;

record VehicleEvent(
        String eventId,
        String eventType,
        String schemaVersion,
        Instant measuredAt,
        Instant publishedAt,
        String vehicleId,
        String correlationId,
        String producer,
        Payload payload
) {
    sealed interface Payload permits Battery, Location {}

    record Battery(int batteryPercent) implements Payload {}

    record Location(BigDecimal latitude, BigDecimal longitude) implements Payload {}
}