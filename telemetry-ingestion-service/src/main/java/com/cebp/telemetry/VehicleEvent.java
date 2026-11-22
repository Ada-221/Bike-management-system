package com.cebp.telemetry;

import com.fasterxml.jackson.annotation.JsonInclude;
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
    sealed interface Payload permits Battery, Location, Fault {}

    record Battery(int batteryPercent) implements Payload {}

    record Location(BigDecimal latitude, BigDecimal longitude) implements Payload {}
    record Fault(String faultCode, @JsonInclude(JsonInclude.Include.NON_NULL) String description) implements Payload {}
}
