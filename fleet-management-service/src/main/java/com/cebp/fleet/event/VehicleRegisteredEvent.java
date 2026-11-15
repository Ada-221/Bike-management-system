package com.cebp.fleet.event;

import com.cebp.fleet.vehicle.Vehicle;
import com.cebp.fleet.vehicle.VehicleStatus;
import com.cebp.fleet.vehicle.VehicleType;
import java.time.Instant;

/**
 * VehicleRegistered (topic fleet.vehicle). Fields follow events-owned.md section 1.1.
 * PROPOSED ADDITIONS, to confirm with Person 2 before the Kafka adapter: schemaVersion
 * and correlationId (required by the plan's event envelope, section 4.2).
 */
public record VehicleRegisteredEvent(
        String eventId,
        String eventType,
        String schemaVersion,
        String producer,
        String correlationId,
        String vehicleId,
        Instant publishedAt,
        String label,
        VehicleType type,
        VehicleStatus status,
        int batteryPercent,
        double latitude,
        double longitude,
        String zoneId) {

    public static final String EVENT_TYPE = "VehicleRegistered";
    public static final String SCHEMA_VERSION = "1.0";
    public static final String PRODUCER = "fleet-management-service";

    public static VehicleRegisteredEvent from(String eventId, Vehicle v, String correlationId, Instant publishedAt) {
        return new VehicleRegisteredEvent(eventId, EVENT_TYPE, SCHEMA_VERSION, PRODUCER, correlationId,
                v.getId(), publishedAt, v.getLabel(), v.getType(), v.getStatus(), v.getBatteryPercent(),
                v.getLatitude(), v.getLongitude(), v.getZoneId());
    }
}
