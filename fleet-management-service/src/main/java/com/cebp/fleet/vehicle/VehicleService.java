package com.cebp.fleet.vehicle;

import com.cebp.fleet.common.BadRequestException;
import com.cebp.fleet.common.ConflictException;
import com.cebp.fleet.common.ErrorCode;
import com.cebp.fleet.common.Geo;
import com.cebp.fleet.common.IdGenerator;
import com.cebp.fleet.common.NotFoundException;
import com.cebp.fleet.config.FleetProperties;
import com.cebp.fleet.event.Topics;
import com.cebp.fleet.vehicle.dto.VehicleCreateRequest;
import com.cebp.fleet.zone.ZoneAssigner;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
    private final FleetState fleet;
    private final MaintenanceService maintenance;
    private final Clock clock;
    private final FleetProperties props;

    public VehicleService(VehicleRepository vehicles, ZoneAssigner zoneAssigner, IdGenerator ids,
                          FleetState fleet, MaintenanceService maintenance, Clock clock, FleetProperties props) {
        this.vehicles = vehicles;
        this.zoneAssigner = zoneAssigner;
        this.ids = ids;
        this.fleet = fleet;
        this.maintenance = maintenance;
        this.clock = clock;
        this.props = props;
    }

    @Transactional
    public Vehicle register(VehicleCreateRequest request) {
        String label = request.label().trim();
        if (vehicles.existsByLabel(label)) {
            throw duplicateLabel(label);
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
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

        maintenance.applyBattery(vehicle, now);
        fleet.emit(Topics.FLEET_VEHICLE, "VehicleRegistered", vehicle, now, Map.of(
                "batteryPercent", vehicle.getBatteryPercent(), "latitude", vehicle.getLatitude(),
                "longitude", vehicle.getLongitude(), "batteryMeasuredAt", vehicle.getLastBatteryAt(),
                "locationMeasuredAt", vehicle.getLastLocationAt()));
        return vehicle;
    }

    @Transactional(readOnly = true)
    public Vehicle get(String id) {
        return vehicles.findById(id)
                .orElseThrow(() -> new NotFoundException("Vehicle " + id + " not found."));
    }

    @Transactional(readOnly = true)
    public List<Vehicle> list(VehicleStatus status, String zoneId, Integer maxBattery, Integer limit) {
        int effectiveLimit = fleet.limit(limit);
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

    record Nearby(Vehicle vehicle, double distanceMeters) {}

    @Transactional(readOnly = true)
    public List<Nearby> nearby(double lat, double lon, Integer radiusMeters, Integer minBattery, Integer limit) {
        if (!Double.isFinite(lat) || Math.abs(lat) > 90) throw new BadRequestException("lat", "must be between -90 and 90");
        if (!Double.isFinite(lon) || Math.abs(lon) > 180) throw new BadRequestException("lon", "must be between -180 and 180");
        int radius = radiusMeters == null ? props.nearbyDefaultRadiusMeters() : radiusMeters;
        if (radius < 1 || radius > props.nearbyMaxRadiusMeters()) {
            throw new BadRequestException("radiusMeters", "must be between 1 and " + props.nearbyMaxRadiusMeters());
        }
        if (minBattery != null && (minBattery < 0 || minBattery > 100)) {
            throw new BadRequestException("minBattery", "must be between 0 and 100");
        }
        int effectiveLimit = fleet.limit(limit);
        int threshold = minBattery == null ? props.minRentableBatteryPercent()
                : Math.max(minBattery, props.minRentableBatteryPercent());
        double latitudeDelta = Geo.latitudeDelta(radius);
        return vehicles.findByStatusAndBatteryPercentGreaterThanEqualAndLatitudeBetween(
                        VehicleStatus.AVAILABLE, threshold, lat - latitudeDelta, lat + latitudeDelta).stream()
                .map(v -> new Nearby(v, Geo.distanceMeters(lat, lon, v.getLatitude(), v.getLongitude())))
                .filter(candidate -> candidate.distanceMeters() <= radius)
                .sorted(Comparator.comparingDouble(Nearby::distanceMeters)
                        .thenComparing(candidate -> candidate.vehicle().getId()))
                .limit(effectiveLimit).toList();
    }

    /** Only the label can be changed; status, battery and location are system-controlled. */
    @Transactional
    public Vehicle rename(String id, String newLabel) {
        Vehicle vehicle = fleet.lockVehicle(id);
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
