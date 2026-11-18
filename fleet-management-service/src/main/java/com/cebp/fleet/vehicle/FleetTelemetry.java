package com.cebp.fleet.vehicle;

import com.cebp.fleet.common.CorrelationIdFilter;
import com.cebp.fleet.event.Topics;
import com.cebp.fleet.zone.ZoneAssigner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FleetTelemetry {
    private static final Logger log = LoggerFactory.getLogger(FleetTelemetry.class);
    private static final String BATTERY = "VehicleBatteryUpdated";
    private static final String LOCATION = "VehicleLocationUpdated";
    private static final String FAULT = "VehicleFaultReported";
    private static final Set<String> SUPPORTED = Set.of(BATTERY, LOCATION, FAULT);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final VehicleRepository vehicles;
    private final FleetState fleet;
    private final MaintenanceService maintenance;
    private final ZoneAssigner zones;
    private final Clock clock;

    public FleetTelemetry(JdbcTemplate jdbc, ObjectMapper json, VehicleRepository vehicles, FleetState fleet,
                          MaintenanceService maintenance, ZoneAssigner zones, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.vehicles = vehicles;
        this.fleet = fleet;
        this.maintenance = maintenance;
        this.zones = zones;
        this.clock = clock;
    }

    @Transactional
    public void consume(String key, String value) {
        JsonNode event = parse(value);
        String type = requiredText(event, "eventType");
        if (!SUPPORTED.contains(type)) return;

        String id = requiredText(event, "eventId");
        String vehicleId = requiredText(event, "vehicleId");
        String correlationId = requiredText(event, "correlationId");
        if (event.size() != 9 || !id.matches("^evt_[A-Za-z0-9][A-Za-z0-9_-]{2,35}$")
                || !vehicleId.matches("^veh_[A-Za-z0-9][A-Za-z0-9_-]{2,35}$") || !vehicleId.equals(key)
                || !"telemetry-ingestion-service".equals(event.path("producer").textValue())
                || !"1.0".equals(event.path("schemaVersion").textValue())) {
            throw new IllegalArgumentException("Invalid telemetry envelope or vehicle key for " + id);
        }

        MeasurementOrder order = new MeasurementOrder(timestamp(event, "measuredAt"), timestamp(event, "publishedAt"), id);
        JsonNode payload = event.path("payload");
        validatePayload(type, payload);

        String fingerprint = fingerprint(value);
        if (jdbc.update("""
                insert into processed_events (event_id, fingerprint) values (?, ?)
                on conflict (event_id) do nothing
                """, id, fingerprint) == 0) {
            String previous = jdbc.queryForObject("select fingerprint from processed_events where event_id = ?", String.class, id);
            if (previous != null && !previous.equals(fingerprint)) {
                throw new IllegalArgumentException("Conflicting content for telemetry event " + id);
            }
            return;
        }

        Vehicle v = vehicles.findForUpdate(vehicleId).orElse(null);
        if (v == null) {
            log.warn("Discarded telemetry {} for unregistered vehicle {}", id, vehicleId);
            return;
        }

        try (MDC.MDCCloseable ignored = MDC.putCloseable(CorrelationIdFilter.MDC_KEY, correlationId)) {
            apply(v, type, order, payload);
        }
    }

    private void apply(Vehicle v, String type, MeasurementOrder order, JsonNode payload) {
        Instant now = clock.instant();
        boolean applied;
        switch (type) {
            case BATTERY -> {
                applied = v.updateBattery(payload.path("batteryPercent").intValue(), order, now);
                if (applied) maintenance.applyBattery(v, now);
            }
            case LOCATION -> {
                double lat = payload.path("latitude").doubleValue();
                double lon = payload.path("longitude").doubleValue();
                String oldZone = v.getZoneId();
                applied = v.updateLocation(lat, lon, zones.zoneIdFor(lat, lon), order, now);
                if (applied && !Objects.equals(oldZone, v.getZoneId())) {
                    fleet.emit(Topics.FLEET_VEHICLE, "VehicleStateUpdated", v, now, Map.of());
                }
            }
            case FAULT -> {
                applied = v.acceptFault(order);
                if (applied) maintenance.open(v, MaintenanceService.Reason.FAULT, payload.path("faultCode").textValue(),
                        payload.path("description").textValue(), now);
            }
            default -> throw new IllegalArgumentException("Unsupported telemetry event " + type);
        }
        if (applied) v.noteTelemetry(order.measuredAt(), now);
    }

    private JsonNode parse(String value) {
        try {
            JsonNode event = value == null ? null : json.readTree(value);
            if (event != null && event.isObject()) return event;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Telemetry record must contain a JSON object", e);
        }
        throw new IllegalArgumentException("Telemetry record must contain a JSON object");
    }

    private static String requiredText(JsonNode node, String field) {
        String result = node.path(field).textValue();
        if (result == null || result.isBlank()) throw new IllegalArgumentException("Missing telemetry " + field);
        return result;
    }

    private static Instant timestamp(JsonNode event, String field) {
        String value = requiredText(event, field);
        if (!value.matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z$")) {
            throw new IllegalArgumentException(field + " must be a UTC timestamp");
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid telemetry " + field, e);
        }
    }

    private static void validatePayload(String type, JsonNode payload) {
        if (!payload.isObject()) throw new IllegalArgumentException("Invalid telemetry payload");
        if (BATTERY.equals(type)) {
            JsonNode battery = payload.path("batteryPercent");
            if (payload.size() != 1 || !battery.isIntegralNumber() || !battery.canConvertToInt()
                    || battery.intValue() < 0 || battery.intValue() > 100) {
                throw new IllegalArgumentException("Invalid telemetry battery percentage");
            }
        } else if (LOCATION.equals(type)) {
            if (payload.size() != 2 || !coordinate(payload.path("latitude"), 90) || !coordinate(payload.path("longitude"), 180)) {
                throw new IllegalArgumentException("Invalid telemetry location");
            }
        } else {
            String fault = requiredText(payload, "faultCode");
            JsonNode description = payload.path("description");
            if (fault.length() > 50 || payload.size() != (description.isMissingNode() ? 1 : 2)
                    || (!description.isMissingNode() && (!description.isTextual() || description.textValue().isBlank()
                    || description.textValue().length() > 500))) {
                throw new IllegalArgumentException("Invalid telemetry fault");
            }
        }
    }

    private static boolean coordinate(JsonNode value, int max) {
        return value.isNumber() && Double.isFinite(value.doubleValue()) && Math.abs(value.doubleValue()) <= max;
    }

    private static String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
