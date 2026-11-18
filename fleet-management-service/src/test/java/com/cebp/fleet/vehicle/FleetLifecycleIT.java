package com.cebp.fleet.vehicle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cebp.fleet.AbstractPostgresIT;
import com.cebp.fleet.vehicle.dto.VehicleCreateRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc
class FleetLifecycleIT extends AbstractPostgresIT {
    @Autowired private MockMvc http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private FleetTelemetry telemetry;
    @Autowired private VehicleService vehicles;
    @Autowired private TransactionTemplate transactions;

    @BeforeEach
    void reset() {
        jdbc.execute("truncate fleet_outbox, rentals, maintenance_records, vehicles, processed_events, zones");
        jdbc.update("""
                insert into zones (id, name, center_latitude, center_longitude, radius_meters)
                values ('zone_001', 'Center', 48.21, 16.36, 800), ('zone_002', 'South', 48.1858, 16.3758, 800)
                """);
    }

    @Test
    void midTripFaultIsVisibleAndRiderCanRecoverAndReturnTheTrip() throws Exception {
        String vehicle = register("Fault test", 90, 48.21);
        JsonNode rental = send(start(vehicle, "rider-a", "fault-test-key"), 201);
        String id = rental.path("id").asText();
        assertThat(send(start(vehicle, "rider-a", "fault-test-key"), 200)).isEqualTo(rental);
        assertThat(send(start(vehicle, "rider-b", "fault-test-key"), 409).path("code").asText())
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");

        Instant now = Instant.now();
        String fault = event(vehicle, "evt_fault_a", "VehicleFaultReported", now, now, Map.of("faultCode", "BRAKE"));
        telemetry.consume(vehicle, fault);
        telemetry.consume(vehicle, fault);
        http.perform(get("/vehicles/" + vehicle)).andExpect(jsonPath("$.status").value("MAINTENANCE_REQUIRED"));
        http.perform(get("/fleet/status")).andExpect(jsonPath("$.activeRentals").value(1));
        http.perform(get("/vehicles/nearby").param("lat", "48.21").param("lon", "16.36"))
                .andExpect(jsonPath("$.length()").value(0));
        http.perform(get("/rentals").param("riderId", "rider-a").param("status", "ACTIVE"))
                .andExpect(jsonPath("$[0].id").value(id));

        String path = "/rentals/" + id + "/return";
        JsonNode returned = send(post(path).content("{\"latitude\":48.24,\"longitude\":16.40}"), 200);
        assertThat(returned.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(send(post(path).content("{\"latitude\":0,\"longitude\":0}"), 200)).isEqualTo(returned);
        http.perform(get("/vehicles/" + vehicle)).andExpect(jsonPath("$.status").value("MAINTENANCE_REQUIRED"))
                .andExpect(jsonPath("$.latitude").value(48.24)).andExpect(jsonPath("$.zoneId").isEmpty());
        http.perform(get("/fleet/status")).andExpect(jsonPath("$.activeRentals").value(0));
        assertThat(eventCount("RentalStarted")).isEqualTo(1);
        assertThat(eventCount("RentalEnded")).isEqualTo(1);
        assertThat(eventCount("VehicleMaintenanceChanged")).isEqualTo(1);
    }

    @Test
    void resolutionDuringTripRestoresRentedAndFaultRacingReturnLeavesMaintenance() throws Exception {
        String vehicle = register("Resolve test", 80, 48.21);
        String rental = send(start(vehicle, "rider-a", "resolve-test-key"), 201).path("id").asText();
        JsonNode fault = send(post("/vehicles/" + vehicle + "/maintenance")
                .content("{\"reason\":\"FAULT\",\"faultCode\":\"BRAKE\"}"), 201);
        send(patch("/maintenance/" + fault.path("id").asText()).content("{\"status\":\"RESOLVED\"}"), 200);
        http.perform(get("/vehicles/" + vehicle)).andExpect(jsonPath("$.status").value("RENTED"));
        Instant now = Instant.now();
        String incoming = event(vehicle, "evt_fault_b", "VehicleFaultReported", now, now, Map.of("faultCode", "MOTOR"));
        CompletableFuture<Void> ingest = CompletableFuture.runAsync(() -> telemetry.consume(vehicle, incoming));
        CompletableFuture<JsonNode> returned = CompletableFuture.supplyAsync(() ->
                unchecked(post("/rentals/" + rental + "/return").content("{\"latitude\":48.21,\"longitude\":16.36}")));
        CompletableFuture.allOf(ingest, returned).get(10, TimeUnit.SECONDS);
        assertThat(returned.join().path("status").asText()).isEqualTo("COMPLETED");
        http.perform(get("/vehicles/" + vehicle)).andExpect(jsonPath("$.status").value("MAINTENANCE_REQUIRED"));
    }

    @Test
    void concurrentStartsAllowOneRentalAndMatchingReplayReturnsSameTrip() throws Exception {
        String vehicle = register("Concurrent test", 90, 48.21);
        var requestA = start(vehicle, "rider-a", "concurrent-a");
        var requestB = start(vehicle, "rider-b", "concurrent-b");
        CompletableFuture<JsonNode> a = CompletableFuture.supplyAsync(() -> unchecked(requestA));
        CompletableFuture<JsonNode> b = CompletableFuture.supplyAsync(() -> unchecked(requestB));
        CompletableFuture.allOf(a, b).get(10, TimeUnit.SECONDS);
        JsonNode winner = a.join().has("id") ? a.join() : b.join();
        JsonNode loser = a.join().has("id") ? b.join() : a.join();
        assertThat(loser.path("code").asText()).isEqualTo("VEHICLE_NOT_AVAILABLE");
        String rider = winner.path("riderId").asText();
        assertThat(send(start(vehicle, rider, "concurrent-" + rider.charAt(rider.length() - 1)), 200)).isEqualTo(winner);
        assertThat(eventCount("RentalStarted")).isEqualTo(1);
    }

    @Test
    void telemetryUsesIndependentOrderingAndBatteryThresholds() throws Exception {
        String vehicle = register("Telemetry test", 20, 48.21);
        register("Farther", 20, 48.214);
        register("Low", 19, 48.2101);
        http.perform(get("/vehicles/nearby").param("lat", "48.21").param("lon", "16.36").param("minBattery", "0"))
                .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].id").value(vehicle))
                .andExpect(jsonPath("$[0].distanceMeters").value(0));

        Instant t = Instant.now();
        sendBattery(vehicle, "evt_battery_1", t, t, 19);
        sendBattery(vehicle, "evt_battery_2", t, t.plusNanos(1), 49);
        sendBattery(vehicle, "evt_battery_0", t, t, 99);
        telemetry.consume(vehicle, event(vehicle, "evt_location_1", "VehicleLocationUpdated", t.minusNanos(1), t,
                Map.of("latitude", 48.1858, "longitude", 16.3758)));
        http.perform(get("/vehicles/" + vehicle)).andExpect(jsonPath("$.batteryPercent").value(49))
                .andExpect(jsonPath("$.status").value("MAINTENANCE_REQUIRED")).andExpect(jsonPath("$.zoneId").value("zone_002"));
        sendBattery(vehicle, "evt_battery_3", t, t.plusNanos(1), 50);
        http.perform(get("/vehicles/" + vehicle)).andExpect(jsonPath("$.status").value("AVAILABLE"));
    }

    @Test
    void healthyReturnAcrossZonesMovesDiscoveryToTheNewZone() throws Exception {
        String vehicle = register("Return across zones", 90, 48.21);
        String rental = send(start(vehicle, "rider-a", "cross-zone-return"), 201).path("id").asText();
        send(post("/rentals/" + rental + "/return").content("{\"latitude\":48.1858,\"longitude\":16.3758}"), 200);
        http.perform(get("/vehicles/" + vehicle)).andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.zoneId").value("zone_002"));
        http.perform(get("/vehicles/nearby").param("lat", "48.1858").param("lon", "16.3758"))
                .andExpect(jsonPath("$[0].id").value(vehicle));
    }

    @Test
    void rollbackDiscardsTheDomainChangeAndItsOutboxRow() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            vehicles.register(new VehicleCreateRequest("Rolled back", VehicleType.E_BIKE, 48.21, 16.36, 15));
            throw new IllegalStateException("rollback");
        })).hasMessage("rollback");
        assertThat(eventCount("VehicleRegistered")).isZero();
        assertThat(eventCount("VehicleMaintenanceChanged")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from vehicles", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from maintenance_records", Integer.class)).isZero();
    }

    private int eventCount(String type) {
        return jdbc.queryForObject("select count(*) from fleet_outbox where body->>'eventType' = ?", Integer.class, type);
    }

    private String register(String label, int battery, double latitude) throws Exception {
        return send(post("/vehicles").content(json.writeValueAsString(Map.of("label", label, "type", "E_BIKE",
                "batteryPercent", battery, "latitude", latitude, "longitude", 16.36))), 201).path("id").asText();
    }

    private MockHttpServletRequestBuilder start(String vehicle, String rider, String key) throws Exception {
        return post("/rentals").header("Idempotency-Key", key)
                .content(json.writeValueAsString(Map.of("vehicleId", vehicle, "riderId", rider)));
    }

    private JsonNode unchecked(MockHttpServletRequestBuilder request) {
        try {
            var result = http.perform(request.contentType("application/json")).andReturn().getResponse();
            assertThat(List.of(200, 201, 409)).contains(result.getStatus());
            return json.readTree(result.getContentAsString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode send(MockHttpServletRequestBuilder request, int code) throws Exception {
        String response = http.perform(request.contentType("application/json")).andExpect(status().is(code))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private void sendBattery(String vehicle, String id, Instant measured, Instant published, int percent) throws Exception {
        telemetry.consume(vehicle, event(vehicle, id, "VehicleBatteryUpdated", measured, published, Map.of("batteryPercent", percent)));
    }

    private String event(String vehicle, String id, String type, Instant measured, Instant published,
                         Map<String, Object> payload) throws Exception {
        return json.writeValueAsString(Map.of("eventType", type, "eventId", id, "schemaVersion", "1.0",
                "vehicleId", vehicle, "correlationId", "corr_test", "producer", "telemetry-ingestion-service",
                "measuredAt", measured, "publishedAt", published, "payload", payload));
    }
}
