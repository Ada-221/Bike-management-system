package com.cebp.fleet.vehicle;

import com.cebp.fleet.common.AfterCommit;
import com.cebp.fleet.common.BadRequestException;
import com.cebp.fleet.common.ConflictException;
import com.cebp.fleet.common.CorrelationIdFilter;
import com.cebp.fleet.common.ErrorCode;
import com.cebp.fleet.common.IdGenerator;
import com.cebp.fleet.common.NotFoundException;
import com.cebp.fleet.config.FleetProperties;
import com.cebp.fleet.event.EventPublisher;
import com.cebp.fleet.event.Topics;
import com.cebp.fleet.event.VehicleRegisteredEvent;
import com.cebp.fleet.vehicle.dto.VehicleCreateRequest;
import com.cebp.fleet.zone.ZoneAssigner;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Vehicle registration, lookup, listing and renaming. */
@Service
public class VehicleService {

    private final VehicleRepository vehicles;
    private final ZoneAssigner zoneAssigner;
    private final IdGenerator ids;
    private final EventPublisher publisher;
    private final Clock clock;
    private final FleetProperties props;

    public VehicleService(VehicleRepository vehicles, ZoneAssigner zoneAssigner, IdGenerator ids,
                          EventPublisher publisher, Clock clock, FleetProperties props) {
        this.vehicles = vehicles;
        this.zoneAssigner = zoneAssigner;
        this.ids = ids;
        this.publisher = publisher;
        this.clock = clock;
        this.props = props;
    }

    /**
     * Creates the vehicle as AVAILABLE and publishes VehicleRegistered after commit.
     * Note: a battery under the rentable minimum is stored as given; the low-battery
     * maintenance rules (BR-08) arrive with the telemetry work in Week 3.
     */
    @Transactional
    public Vehicle register(VehicleCreateRequest request) {
        String label = request.label().trim();
        if (vehicles.existsByLabel(label)) {
            throw duplicateLabel(label);
        }

        Instant now = clock.instant();
        String zoneId = zoneAssigner.zoneIdFor(request.latitude(), request.longitude());
        Vehicle vehicle = Vehicle.register(ids.nextVehicleId(), label, request.type(),
                request.batteryPercent(), request.latitude(), request.longitude(), zoneId, now);

        try {
            vehicles.saveAndFlush(vehicle);
        } catch (DataIntegrityViolationException e) {
            // Two requests with the same label raced past the check above; the database caught it.
            if (isLabelViolation(e)) {
                throw duplicateLabel(label);
            }
            throw e;
        }

        VehicleRegisteredEvent event = VehicleRegisteredEvent.from(
                ids.nextEventId(), vehicle, MDC.get(CorrelationIdFilter.MDC_KEY), now);
        AfterCommit.run(() -> publisher.publish(Topics.FLEET_VEHICLE, vehicle.getId(), event));
        return vehicle;
    }

    @Transactional(readOnly = true)
    public Vehicle get(String id) {
        return vehicles.findById(id)
                .orElseThrow(() -> new NotFoundException("Vehicle " + id + " not found."));
    }

    @Transactional(readOnly = true)
    public List<Vehicle> list(VehicleStatus status, String zoneId, Integer maxBattery, Integer limit) {
        int effectiveLimit = limit == null ? props.listDefaultLimit() : limit;
        if (effectiveLimit < 1 || effectiveLimit > props.listMaxLimit()) {
            throw new BadRequestException("limit", "must be between 1 and " + props.listMaxLimit());
        }
        if (maxBattery != null && (maxBattery < 0 || maxBattery > 100)) {
            throw new BadRequestException("maxBattery", "must be between 0 and 100");
        }

        // Only the filters that were actually supplied are applied.
        Specification<Vehicle> spec = (root, query, cb) -> cb.conjunction();
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (zoneId != null && !zoneId.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("zoneId"), zoneId));
        }
        if (maxBattery != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("batteryPercent"), maxBattery));
        }
        return vehicles.findAll(spec, PageRequest.of(0, effectiveLimit, Sort.by("id"))).getContent();
    }

    /** Only the label can be changed; status, battery and location are system-controlled. */
    @Transactional
    public Vehicle rename(String id, String newLabel) {
        Vehicle vehicle = get(id);
        String label = newLabel.trim();
        if (label.equals(vehicle.getLabel())) {
            return vehicle;
        }
        if (vehicles.existsByLabel(label)) {
            throw duplicateLabel(label);
        }
        vehicle.rename(label, clock.instant());
        try {
            return vehicles.saveAndFlush(vehicle);
        } catch (DataIntegrityViolationException e) {
            if (isLabelViolation(e)) {
                throw duplicateLabel(label);
            }
            throw e;
        }
    }

    private static ConflictException duplicateLabel(String label) {
        return new ConflictException(ErrorCode.DUPLICATE_VEHICLE_LABEL,
                "A vehicle with label '" + label + "' already exists.");
    }

    // PostgreSQL names the unique constraint on vehicles.label "vehicles_label_key".
    private static boolean isLabelViolation(DataIntegrityViolationException e) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.contains("vehicles_label_key");
    }
}
