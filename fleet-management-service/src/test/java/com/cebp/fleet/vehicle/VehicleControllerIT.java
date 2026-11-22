package com.cebp.fleet.vehicle;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cebp.fleet.AbstractPostgresIT;
import com.cebp.fleet.event.EventPublisher;
import com.cebp.fleet.event.Topics;
import com.cebp.fleet.event.FleetEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Full stack: HTTP layer, validation, service, JPA and a real PostgreSQL.
 * MockMvc ignores the /api/v1 context path, so paths start at /vehicles.
 * Vehicle IDs come from a sequence that is not reset between tests, so no test depends on an exact ID.
 */
@AutoConfigureMockMvc
class VehicleControllerIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private com.cebp.fleet.zone.ZoneAssigner zoneAssigner;

    /** Captures staged events without running a Kafka broker. */
    @MockBean
    private EventPublisher publisher;

    @BeforeEach
    void resetDatabase() {
        zoneAssigner.invalidate();
        jdbc.execute("TRUNCATE rentals, maintenance_records, vehicles, zones, processed_events");
        jdbc.update("insert into zones (id, name, center_latitude, center_longitude, radius_meters) "
                + "values ('zone_001', 'City Center', 48.2100, 16.3600, 800)");
        jdbc.update("insert into zones (id, name, center_latitude, center_longitude, radius_meters) "
                + "values ('zone_002', 'South Station', 48.1858, 16.3758, 800)");
    }

    // ------------------------------------------------------------------ helpers

    private String vehicleJson(String label, double lat, double lon, int battery) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "label", label, "type", "E_BIKE", "latitude", lat, "longitude", lon, "batteryPercent", battery));
    }

    /** Registers a vehicle through the API and returns its generated ID. */
    private String register(String label, double lat, double lon, int battery) throws Exception {
        MvcResult result = mockMvc.perform(post("/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(vehicleJson(label, lat, lon, battery)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    // ----------------------------------------------------------------- register

    @Test
    void registerReturns201WithZoneAndDerivedFields() throws Exception {
        mockMvc.perform(post("/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(vehicleJson("EB-001", 48.2102, 16.3598, 92)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", matchesPattern("veh_\\d{3,}")))
                .andExpect(jsonPath("$.label").value("EB-001"))
                .andExpect(jsonPath("$.type").value("E_BIKE"))
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.batteryPercent").value(92))
                .andExpect(jsonPath("$.zoneId").value("zone_001"))
                .andExpect(jsonPath("$.rentable").value(true))
                .andExpect(jsonPath("$.lastBatteryAt").isNotEmpty())
                .andExpect(jsonPath("$.lastLocationAt").isNotEmpty())
                .andExpect(jsonPath("$.lastTelemetryAt").isEmpty());

        verify(publisher).publish(eq(Topics.FLEET_VEHICLE), anyString(), any(FleetEvent.class));
    }

    @Test
    void registerOutsideEveryZoneHasNoZone() throws Exception {
        mockMvc.perform(post("/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(vehicleJson("EB-001", 48.30, 16.50, 80)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.zoneId").isEmpty());
    }

    @Test
    void lowBatteryVehicleRequiresMaintenanceAndIsNotRentable() throws Exception {
        mockMvc.perform(post("/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(vehicleJson("EB-001", 48.2102, 16.3598, 15)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("MAINTENANCE_REQUIRED"))
                .andExpect(jsonPath("$.rentable").value(false));
    }

    @Test
    void registerDuplicateLabelIs409AndPublishesOnlyOnce() throws Exception {
        register("EB-001", 48.2102, 16.3598, 92);

        mockMvc.perform(post("/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(vehicleJson("EB-001", 48.2100, 16.3600, 50)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_VEHICLE_LABEL"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());

        verify(publisher, times(1)).publish(anyString(), anyString(), any());
    }

    @Test
    void registerInvalidBodyIs400WithFieldDetails() throws Exception {
        String body = "{\"label\":\"  \",\"latitude\":95,\"longitude\":16.3,\"batteryPercent\":150}";

        mockMvc.perform(post("/vehicles").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[*].field",
                        hasItems("label", "type", "latitude", "batteryPercent")));

        verifyNoInteractions(publisher);
    }

    @Test
    void registerMalformedJsonIs400() throws Exception {
        mockMvc.perform(post("/vehicles").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Request body is missing or malformed."));
    }

    // ---------------------------------------------------------------------- get

    @Test
    void getReturnsTheVehicle() throws Exception {
        String id = register("EB-001", 48.2102, 16.3598, 92);

        mockMvc.perform(get("/vehicles/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.label").value("EB-001"));
    }

    @Test
    void getUnknownVehicleIs404AndEchoesTheCorrelationId() throws Exception {
        mockMvc.perform(get("/vehicles/veh_999").header("X-Correlation-Id", "test-123"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Correlation-Id", "test-123"))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Vehicle veh_999 not found."))
                .andExpect(jsonPath("$.correlationId").value("test-123"));
    }

    // --------------------------------------------------------------------- list

    @Test
    void listAppliesFilters() throws Exception {
        register("EB-A", 48.2102, 16.3598, 90);   // zone_001
        register("EB-B", 48.1858, 16.3758, 40);   // zone_002
        register("EB-C", 48.2100, 16.3600, 10);   // zone_001

        mockMvc.perform(get("/vehicles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].label", containsInAnyOrder("EB-A", "EB-B", "EB-C")));
        mockMvc.perform(get("/vehicles?maxBattery=50"))
                .andExpect(jsonPath("$[*].label", containsInAnyOrder("EB-B", "EB-C")));
        mockMvc.perform(get("/vehicles?zoneId=zone_002"))
                .andExpect(jsonPath("$[*].label", containsInAnyOrder("EB-B")));
        mockMvc.perform(get("/vehicles?status=AVAILABLE"))
                .andExpect(jsonPath("$[*].label", containsInAnyOrder("EB-A", "EB-B")));
        mockMvc.perform(get("/vehicles?status=MAINTENANCE_REQUIRED"))
                .andExpect(jsonPath("$[*].label", containsInAnyOrder("EB-C")));
        mockMvc.perform(get("/vehicles?status=RENTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/vehicles?limit=2"))
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"limit=0", "limit=501", "maxBattery=101", "maxBattery=-1", "status=FLYING"})
    void listRejectsInvalidQueryValues(String query) throws Exception {
        mockMvc.perform(get("/vehicles?" + query))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // -------------------------------------------------------------------- patch

    @Test
    void patchRenamesTheLabelOnly() throws Exception {
        String id = register("EB-001", 48.2102, 16.3598, 92);

        mockMvc.perform(patch("/vehicles/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"EB-NEW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("EB-NEW"))
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.batteryPercent").value(92));

        mockMvc.perform(get("/vehicles/{id}", id))
                .andExpect(jsonPath("$.label").value("EB-NEW"));
    }

    @Test
    void patchToAnExistingLabelIs409() throws Exception {
        register("EB-001", 48.2102, 16.3598, 92);
        String second = register("EB-002", 48.2100, 16.3600, 80);

        mockMvc.perform(patch("/vehicles/{id}", second)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"EB-001\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_VEHICLE_LABEL"));
    }

    @Test
    void patchWithBlankLabelIs400() throws Exception {
        String id = register("EB-001", 48.2102, 16.3598, 92);

        mockMvc.perform(patch("/vehicles/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[*].field", hasItems("label")));
    }

    @Test
    void patchUnknownVehicleIs404() throws Exception {
        mockMvc.perform(patch("/vehicles/veh_999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"EB-NEW\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
