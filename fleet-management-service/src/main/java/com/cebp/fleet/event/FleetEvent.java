package com.cebp.fleet.event;

import com.cebp.fleet.vehicle.Vehicle;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record FleetEvent(String eventId, String eventType, String schemaVersion, String vehicleId,
                         Instant publishedAt, String correlationId, String producer, long stateRevision,
                         Map<String, Object> payload) {

    public static FleetEvent from(String id, String type, Vehicle vehicle, Instant timestamp,
                                  String correlation, Map<String, Object> details) {
        Map<String, Object> payload = new LinkedHashMap<>(details);
        payload.put("status", vehicle.getStatus());
        payload.put("zoneId", vehicle.getZoneId());
        // The event ID sequence supplies a numeric revision across topics under the vehicle row lock.
        return new FleetEvent(id, type, "1.0", vehicle.getId(), timestamp,
                correlation == null || correlation.isBlank() ? id : correlation,
                "fleet-management-service", Long.parseLong(id.substring(4)), payload);
    }
}
