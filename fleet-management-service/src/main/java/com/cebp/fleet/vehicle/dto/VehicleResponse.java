package com.cebp.fleet.vehicle.dto;

import com.cebp.fleet.vehicle.VehicleStatus;
import com.cebp.fleet.vehicle.VehicleType;
import java.time.Instant;

/** Vehicle as returned by the API (Vehicle schema in fleet-api.yaml). */
public record VehicleResponse(
        String id,
        String label,
        VehicleType type,
        VehicleStatus status,
        int batteryPercent,
        double latitude,
        double longitude,
        String zoneId,
        boolean rentable,
        Instant lastBatteryAt,
        Instant lastLocationAt,
        Instant lastTelemetryAt,
        Instant createdAt,
        Instant updatedAt) {
}
