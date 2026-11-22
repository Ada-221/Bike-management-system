package com.cebp.fleet.vehicle;

import com.cebp.fleet.common.BadRequestException;
import com.cebp.fleet.common.ConflictException;
import com.cebp.fleet.common.ErrorCode;
import com.cebp.fleet.common.IdGenerator;
import com.cebp.fleet.common.NotFoundException;
import com.cebp.fleet.config.FleetProperties;
import com.cebp.fleet.event.Topics;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaintenanceService {
    public enum Reason { LOW_BATTERY, FAULT, MANUAL }
    public enum Status { OPEN, IN_PROGRESS, RESOLVED }

    public record Record(String id, String vehicleId, Reason reason, Status status, String faultCode,
                         String description, Instant openedAt, Instant resolvedAt) {}
    public record Report(Record record, boolean created) {}

    private static final RowMapper<Record> RECORD = (rs, row) -> {
        Timestamp resolved = rs.getTimestamp("resolved_at");
        return new Record(rs.getString("id"), rs.getString("vehicle_id"), Reason.valueOf(rs.getString("reason")),
                Status.valueOf(rs.getString("status")), rs.getString("fault_code"), rs.getString("description"),
                rs.getTimestamp("opened_at").toInstant(), resolved == null ? null : resolved.toInstant());
    };

    private final JdbcTemplate jdbc;
    private final FleetState fleet;
    private final FleetProperties properties;
    private final IdGenerator ids;
    private final Clock clock;

    public MaintenanceService(JdbcTemplate jdbc, FleetState fleet, FleetProperties properties, IdGenerator ids, Clock clock) {
        this.jdbc = jdbc;
        this.fleet = fleet;
        this.properties = properties;
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional
    public Report report(String vehicleId, Reason reason, String faultCode, String description) {
        if (reason == Reason.LOW_BATTERY) throw new BadRequestException("reason", "must be FAULT or MANUAL");
        if (reason == Reason.FAULT && (faultCode == null || faultCode.isBlank())) {
            throw new BadRequestException("faultCode", "required for FAULT");
        }
        Vehicle v = fleet.lockVehicle(vehicleId);
        return open(v, reason, faultCode, description, clock.instant());
    }

    @Transactional(readOnly = true)
    public List<Record> list(String vehicleId, Status status, Reason reason, Integer limit) {
        return jdbc.query("""
                select * from maintenance_records
                where (cast(? as text) is null or vehicle_id = ?)
                  and (cast(? as text) is null or reason = ?)
                  and case when cast(? as text) is null then status <> 'RESOLVED' else status = ? end
                order by opened_at desc, id desc limit ?
                """, RECORD, vehicleId, vehicleId, name(reason), name(reason), name(status), name(status), fleet.limit(limit));
    }

    @Transactional
    public Record update(String id, Status status, String description) {
        Record current = get(id);
        Vehicle v = fleet.lockVehicle(current.vehicleId());
        current = get(id);
        if (status == Status.OPEN || current.status() == Status.RESOLVED || current.status() == status
                || (current.reason() == Reason.LOW_BATTERY && status == Status.RESOLVED && fleet.lowBattery(v))) {
            throw new ConflictException(ErrorCode.INVALID_STATUS_TRANSITION, "Cannot change this maintenance record to " + status + ".");
        }
        return transition(v, current, status, description, clock.instant());
    }

    // The caller holds the vehicle write lock, or has just inserted it in the same transaction.
    public Report open(Vehicle v, Reason reason, String faultCode, String description, Instant now) {
        Record existing = unresolved(v, reason);
        if (existing != null) return new Report(existing, false);

        String id = ids.nextMaintenanceId();
        jdbc.update("""
                insert into maintenance_records (id, vehicle_id, reason, status, fault_code, description, opened_at)
                values (?, ?, ?, 'OPEN', ?, ?, ?)
                """, id, v.getId(), reason.name(), faultCode, description, Timestamp.from(now));
        Record record = get(id);
        fleet.refreshStatus(v, now);
        emit(v, record, null, now);
        return new Report(record, true);
    }

    public void applyBattery(Vehicle v, Instant now) {
        if (fleet.lowBattery(v)) {
            open(v, Reason.LOW_BATTERY, null, null, now);
        } else if (v.getBatteryPercent() >= properties.lowBatteryResolvePercent()) {
            Record existing = unresolved(v, Reason.LOW_BATTERY);
            if (existing != null) transition(v, existing, Status.RESOLVED, null, now);
        }
        fleet.refreshStatus(v, now);
    }

    private Record transition(Vehicle v, Record record, Status status, String description, Instant now) {
        jdbc.update("""
                update maintenance_records set status = ?, description = coalesce(?, description), resolved_at = ?
                where id = ?
                """, status.name(), description, status == Status.RESOLVED ? Timestamp.from(now) : null, record.id());
        Record updated = get(record.id());
        fleet.refreshStatus(v, now);
        emit(v, updated, record.status(), now);
        return updated;
    }

    private Record unresolved(Vehicle v, Reason reason) {
        return jdbc.query("""
                select * from maintenance_records where vehicle_id = ? and reason = ? and status <> 'RESOLVED'
                """, RECORD, v.getId(), reason.name()).stream().findFirst().orElse(null);
    }

    private Record get(String id) {
        return jdbc.query("select * from maintenance_records where id = ?", RECORD, id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Maintenance record " + id + " not found."));
    }

    private void emit(Vehicle v, Record record, Status previous, Instant now) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("maintenanceId", record.id());
        details.put("reason", record.reason());
        details.put("previousStatus", previous);
        details.put("newStatus", record.status());
        details.put("faultCode", record.faultCode());
        details.put("changedAt", now);
        fleet.emit(Topics.FLEET_MAINTENANCE, "VehicleMaintenanceChanged", v, now, details);
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
