package com.cebp.fleet.rental;

import com.cebp.fleet.common.BadRequestException;
import com.cebp.fleet.common.ConflictException;
import com.cebp.fleet.common.ErrorCode;
import com.cebp.fleet.common.IdGenerator;
import com.cebp.fleet.common.NotFoundException;
import com.cebp.fleet.event.Topics;
import com.cebp.fleet.vehicle.FleetState;
import com.cebp.fleet.vehicle.MaintenanceService;
import com.cebp.fleet.vehicle.MeasurementOrder;
import com.cebp.fleet.vehicle.Vehicle;
import com.cebp.fleet.vehicle.VehicleStatus;
import com.cebp.fleet.zone.ZoneAssigner;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class RentalService {
    enum Status { ACTIVE, COMPLETED }

    record Rental(String id, String vehicleId, String riderId, Status status, Instant startedAt, Instant endedAt,
                  double startLatitude, double startLongitude, int startBatteryPercent,
                  Double endLatitude, Double endLongitude, Integer endBatteryPercent) {}
    record Start(Rental rental, boolean created) {}

    private static final RowMapper<Rental> RENTAL = (rs, row) -> {
        Timestamp ended = rs.getTimestamp("ended_at");
        return new Rental(rs.getString("id"), rs.getString("vehicle_id"), rs.getString("rider_id"),
                Status.valueOf(rs.getString("status")), rs.getTimestamp("started_at").toInstant(),
                ended == null ? null : ended.toInstant(), rs.getDouble("start_latitude"),
                rs.getDouble("start_longitude"), rs.getInt("start_battery_percent"),
                rs.getObject("end_latitude", Double.class), rs.getObject("end_longitude", Double.class),
                rs.getObject("end_battery_percent", Integer.class));
    };

    private final JdbcTemplate jdbc;
    private final FleetState fleet;
    private final MaintenanceService maintenance;
    private final IdGenerator ids;
    private final ZoneAssigner zones;
    private final Clock clock;

    RentalService(JdbcTemplate jdbc, FleetState fleet, MaintenanceService maintenance, IdGenerator ids,
                  ZoneAssigner zones, Clock clock) {
        this.jdbc = jdbc;
        this.fleet = fleet;
        this.maintenance = maintenance;
        this.ids = ids;
        this.zones = zones;
        this.clock = clock;
    }

    @Transactional
    public Start start(String vehicleId, String riderId, String key) {
        if (key == null || key.isBlank()) {
            throw new BadRequestException(ErrorCode.MISSING_IDEMPOTENCY_KEY, "Idempotency-Key is required.");
        }
        if (key.length() < 8 || key.length() > 100) {
            throw new BadRequestException("Idempotency-Key", "must be between 8 and 100 characters");
        }

        lock("rental-key:" + key);
        Rental existing = jdbc.query("select * from rentals where idempotency_key = ?", RENTAL, key)
                .stream().findFirst().orElse(null);
        if (existing != null) {
            if (existing.vehicleId().equals(vehicleId) && existing.riderId().equals(riderId)) {
                return new Start(existing, false);
            }
            throw new ConflictException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "This key belongs to another rental request.");
        }

        lock("rental-rider:" + riderId);
        Vehicle vehicle = fleet.lockVehicle(vehicleId);
        // Availability first: a RENTED or in-maintenance vehicle is "not available" whatever its battery says.
        if (vehicle.getStatus() != VehicleStatus.AVAILABLE) {
            throw new ConflictException(ErrorCode.VEHICLE_NOT_AVAILABLE, "Vehicle is " + vehicle.getStatus() + ".");
        }
        // Safety net: an AVAILABLE vehicle below the threshold should already be MAINTENANCE_REQUIRED.
        if (fleet.lowBattery(vehicle)) {
            throw new ConflictException(ErrorCode.BATTERY_TOO_LOW, "Vehicle battery is below the rental threshold.");
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from rentals where rider_id = ? and status = 'ACTIVE')
                """, Boolean.class, riderId))) {
            throw new ConflictException(ErrorCode.RIDER_HAS_ACTIVE_RENTAL, "The rider already has an active rental.");
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String id = ids.nextRentalId();
        jdbc.update("""
                insert into rentals (id, vehicle_id, rider_id, status, started_at, start_latitude, start_longitude,
                                     start_battery_percent, idempotency_key)
                values (?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?)
                """, id, vehicleId, riderId, Timestamp.from(now), vehicle.getLatitude(), vehicle.getLongitude(),
                vehicle.getBatteryPercent(), key);
        fleet.refreshStatus(vehicle, now);
        fleet.emit(Topics.FLEET_RENTAL, "RentalStarted", vehicle, now, Map.of("rentalId", id, "riderId", riderId,
                "startedAt", now, "startLatitude", vehicle.getLatitude(), "startLongitude", vehicle.getLongitude(),
                "startBatteryPercent", vehicle.getBatteryPercent()));
        return new Start(get(id), true);
    }

    @Transactional
    public Rental end(String id, double latitude, double longitude) {
        Rental rental = get(id);
        if (rental.status() == Status.COMPLETED) return rental;
        Vehicle vehicle = fleet.lockVehicle(rental.vehicleId());
        rental = get(id);
        if (rental.status() == Status.COMPLETED) return rental;

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant endedAt = now.isBefore(rental.startedAt()) ? rental.startedAt() : now;
        jdbc.update("""
                update rentals set status = 'COMPLETED', ended_at = ?, end_latitude = ?, end_longitude = ?,
                                   end_battery_percent = ? where id = ?
                """, Timestamp.from(endedAt), latitude, longitude, vehicle.getBatteryPercent(), id);

        String eventId = ids.nextEventId();
        Map<String, Object> details = new LinkedHashMap<>(Map.of("rentalId", id, "riderId", rental.riderId(),
                "startedAt", rental.startedAt(), "endedAt", endedAt,
                "durationSeconds", Duration.between(rental.startedAt(), endedAt).toSeconds(),
                "endLatitude", latitude, "endLongitude", longitude, "endBatteryPercent", vehicle.getBatteryPercent()));
        MeasurementOrder order = new MeasurementOrder(endedAt, now, eventId);
        if (vehicle.updateLocation(latitude, longitude, zones.zoneIdFor(latitude, longitude), order, now)) {
            details.put("locationMeasuredAt", endedAt);
        }
        maintenance.applyBattery(vehicle, now);
        fleet.emit(eventId, Topics.FLEET_RENTAL, "RentalEnded", vehicle, now, details);
        return get(id);
    }

    @Transactional(readOnly = true)
    public Rental get(String id) {
        return jdbc.query("select * from rentals where id = ?", RENTAL, id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Rental " + id + " not found."));
    }

    @Transactional(readOnly = true)
    public List<Rental> list(String riderId, String vehicleId, Status status, Integer limit) {
        String statusName = status == null ? null : status.name();
        return jdbc.query("""
                select * from rentals where (cast(? as text) is null or rider_id = ?)
                    and (cast(? as text) is null or vehicle_id = ?)
                    and (cast(? as text) is null or status = ?)
                order by started_at desc, id desc limit ?
                """, RENTAL, riderId, riderId, vehicleId, vehicleId, statusName, statusName, fleet.limit(limit));
    }

    private void lock(String key) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {}, key);
    }
}
