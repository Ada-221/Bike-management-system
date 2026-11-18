package com.cebp.fleet.vehicle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cebp.fleet.common.BadRequestException;
import com.cebp.fleet.common.ConflictException;
import com.cebp.fleet.common.ErrorCode;
import com.cebp.fleet.common.IdGenerator;
import com.cebp.fleet.common.NotFoundException;
import com.cebp.fleet.config.FleetProperties;
import com.cebp.fleet.event.EventPublisher;
import com.cebp.fleet.event.FleetEvent;
import com.cebp.fleet.event.Topics;
import com.cebp.fleet.vehicle.dto.VehicleCreateRequest;
import com.cebp.fleet.zone.ZoneAssigner;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
class VehicleServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-09T08:30:00Z");

    @Mock
    private VehicleRepository vehicles;
    @Mock
    private ZoneAssigner zoneAssigner;
    @Mock
    private IdGenerator ids;
    @Mock
    private EventPublisher publisher;
    @Mock
    private MaintenanceService maintenance;

    private VehicleService service;

    @BeforeEach
    void setUp() {
        FleetProperties props = new FleetProperties(20, 50, 1000, 5000, 100, 500, 15);
        service = new VehicleService(vehicles, zoneAssigner, ids,
                new FleetState(vehicles, null, props, ids, publisher), maintenance,
                Clock.fixed(NOW, ZoneOffset.UTC), props);
    }

    private static Vehicle existingVehicle(String id, String label) {
        return Vehicle.register(id, label, VehicleType.E_BIKE, 80, 48.21, 16.36, "zone_001",
                NOW.minusSeconds(3600));
    }

    // ------------------------------------------------------------------ register

    @Test
    void registerCreatesAvailableVehicleAndPublishesEvent() {
        when(vehicles.existsByLabel("EB-001")).thenReturn(false);
        when(zoneAssigner.zoneIdFor(48.2102, 16.3598)).thenReturn("zone_001");
        when(ids.nextVehicleId()).thenReturn("veh_001");
        when(ids.nextEventId()).thenReturn("evt_0001");
        when(vehicles.saveAndFlush(any(Vehicle.class))).thenAnswer(inv -> inv.getArgument(0));

        // The label is trimmed before it is stored.
        Vehicle v = service.register(new VehicleCreateRequest("  EB-001  ", VehicleType.E_BIKE, 48.2102, 16.3598, 92));

        assertThat(v.getId()).isEqualTo("veh_001");
        assertThat(v.getLabel()).isEqualTo("EB-001");
        assertThat(v.getStatus()).isEqualTo(VehicleStatus.AVAILABLE);
        assertThat(v.getBatteryPercent()).isEqualTo(92);
        assertThat(v.getZoneId()).isEqualTo("zone_001");
        assertThat(v.getLastBatteryAt()).isEqualTo(NOW);
        assertThat(v.getLastLocationAt()).isEqualTo(NOW);
        assertThat(v.getLastTelemetryAt()).isNull();
        assertThat(v.getCreatedAt()).isEqualTo(NOW);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publish(eq(Topics.FLEET_VEHICLE), eq("veh_001"), event.capture());
        assertThat(event.getValue()).isInstanceOf(FleetEvent.class);
        FleetEvent e = (FleetEvent) event.getValue();
        assertThat(e.eventId()).isEqualTo("evt_0001");
        assertThat(e.eventType()).isEqualTo("VehicleRegistered");
        assertThat(e.producer()).isEqualTo("fleet-management-service");
        assertThat(e.vehicleId()).isEqualTo("veh_001");
        assertThat(e.payload()).containsEntry("zoneId", "zone_001");
    }

    @Test
    void registerRejectsDuplicateLabelWithoutSavingOrPublishing() {
        when(vehicles.existsByLabel("EB-001")).thenReturn(true);

        assertThatThrownBy(() -> service.register(
                new VehicleCreateRequest("EB-001", VehicleType.E_BIKE, 48.2, 16.3, 50)))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(((ConflictException) ex).getCode())
                        .isEqualTo(ErrorCode.DUPLICATE_VEHICLE_LABEL));

        verify(vehicles, never()).saveAndFlush(any(Vehicle.class));
        verifyNoInteractions(publisher);
    }

    @Test
    void registerTurnsDatabaseLabelRaceIntoDuplicateLabelError() {
        when(vehicles.existsByLabel("EB-001")).thenReturn(false);
        when(vehicles.saveAndFlush(any(Vehicle.class))).thenThrow(new DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("duplicate key value violates unique constraint \"vehicles_label_key\"")));

        assertThatThrownBy(() -> service.register(
                new VehicleCreateRequest("EB-001", VehicleType.E_BIKE, 48.2, 16.3, 50)))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(publisher);
    }

    @Test
    void registerDoesNotHideOtherIntegrityErrors() {
        when(vehicles.existsByLabel("EB-001")).thenReturn(false);
        when(vehicles.saveAndFlush(any(Vehicle.class))).thenThrow(new DataIntegrityViolationException(
                "could not execute statement", new RuntimeException("violates check constraint")));

        assertThatThrownBy(() -> service.register(
                new VehicleCreateRequest("EB-001", VehicleType.E_BIKE, 48.2, 16.3, 50)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ----------------------------------------------------------------------- get

    @Test
    void getUnknownVehicleIsNotFound() {
        when(vehicles.findById("veh_999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("veh_999"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Vehicle veh_999 not found.");
    }

    // ---------------------------------------------------------------------- list

    @Test
    @SuppressWarnings("unchecked")
    void listUsesDefaultLimitWhenNoneGiven() {
        Page<Vehicle> empty = new PageImpl<>(List.of());
        when(vehicles.findAll(any(Specification.class), any(Pageable.class))).thenReturn(empty);

        service.list(null, null, null, null);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(vehicles).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
    }

    @ParameterizedTest
    @ValueSource(ints = {-5, 0, 501})
    void listRejectsOutOfRangeLimit(int limit) {
        assertThatThrownBy(() -> service.list(null, null, null, limit))
                .isInstanceOf(BadRequestException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 101})
    void listRejectsOutOfRangeMaxBattery(int maxBattery) {
        assertThatThrownBy(() -> service.list(null, null, maxBattery, null))
                .isInstanceOf(BadRequestException.class);
    }

    // -------------------------------------------------------------------- rename

    @Test
    void renameToSameLabelChangesNothing() {
        Vehicle existing = existingVehicle("veh_001", "EB-001");
        when(vehicles.findForUpdate("veh_001")).thenReturn(Optional.of(existing));

        Vehicle result = service.rename("veh_001", " EB-001 ");

        assertThat(result).isSameAs(existing);
        verify(vehicles, never()).existsByLabel(anyString());
        verify(vehicles, never()).saveAndFlush(any(Vehicle.class));
    }

    @Test
    void renameUpdatesLabelAndTimestamp() {
        Vehicle existing = existingVehicle("veh_001", "EB-001");
        when(vehicles.findForUpdate("veh_001")).thenReturn(Optional.of(existing));
        when(vehicles.existsByLabel("EB-002")).thenReturn(false);
        when(vehicles.saveAndFlush(any(Vehicle.class))).thenAnswer(inv -> inv.getArgument(0));

        Vehicle result = service.rename("veh_001", "EB-002");

        assertThat(result.getLabel()).isEqualTo("EB-002");
        assertThat(result.getUpdatedAt()).isEqualTo(NOW);
        assertThat(result.getStatus()).isEqualTo(VehicleStatus.AVAILABLE);
    }

    @Test
    void renameToTakenLabelIsConflict() {
        when(vehicles.findForUpdate("veh_001")).thenReturn(Optional.of(existingVehicle("veh_001", "EB-001")));
        when(vehicles.existsByLabel("EB-002")).thenReturn(true);

        assertThatThrownBy(() -> service.rename("veh_001", "EB-002"))
                .isInstanceOf(ConflictException.class);
        verify(vehicles, never()).saveAndFlush(any(Vehicle.class));
    }

    @Test
    void renameUnknownVehicleIsNotFound() {
        when(vehicles.findForUpdate("veh_999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rename("veh_999", "EB-002"))
                .isInstanceOf(NotFoundException.class);
    }
}
