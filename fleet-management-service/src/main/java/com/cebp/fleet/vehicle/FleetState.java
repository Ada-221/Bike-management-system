package com.cebp.fleet.vehicle;

import com.cebp.fleet.common.BadRequestException;
import com.cebp.fleet.common.CorrelationIdFilter;
import com.cebp.fleet.common.IdGenerator;
import com.cebp.fleet.common.NotFoundException;
import com.cebp.fleet.config.FleetProperties;
import com.cebp.fleet.event.EventPublisher;
import com.cebp.fleet.event.FleetEvent;
import java.time.Instant;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class FleetState {
    private final VehicleRepository vehicles;
    private final JdbcTemplate jdbc;
    private final FleetProperties properties;
    private final IdGenerator ids;
    private final EventPublisher events;

    public FleetState(VehicleRepository vehicles, JdbcTemplate jdbc, FleetProperties properties,
                      IdGenerator ids, EventPublisher events) {
        this.vehicles = vehicles;
        this.jdbc = jdbc;
        this.properties = properties;
        this.ids = ids;
        this.events = events;
    }

    public Vehicle lockVehicle(String id) {
        return vehicles.findForUpdate(id).orElseThrow(() -> new NotFoundException("Vehicle " + id + " not found."));
    }

    public void refreshStatus(Vehicle v, Instant now) {
        boolean fault = exists("""
                select exists(select 1 from maintenance_records
                    where vehicle_id = ? and reason = 'FAULT' and status <> 'RESOLVED')
                """, v.getId());
        boolean active = exists("select exists(select 1 from rentals where vehicle_id = ? and status = 'ACTIVE')", v.getId());
        boolean maintenance = lowBattery(v) || exists("""
                select exists(select 1 from maintenance_records where vehicle_id = ? and status <> 'RESOLVED')
                """, v.getId());
        VehicleStatus status = VehicleStatus.AVAILABLE;
        if (fault || (!active && maintenance)) {
            status = VehicleStatus.MAINTENANCE_REQUIRED;
        } else if (active) {
            status = VehicleStatus.RENTED;
        }
        v.changeStatus(status, now);
    }

    public boolean lowBattery(Vehicle v) {
        return v.getBatteryPercent() < properties.minRentableBatteryPercent();
    }

    public int limit(Integer requested) {
        int limit = requested == null ? properties.listDefaultLimit() : requested;
        if (limit < 1 || limit > properties.listMaxLimit()) {
            throw new BadRequestException("limit", "must be between 1 and " + properties.listMaxLimit());
        }
        return limit;
    }

    public void emit(String topic, String type, Vehicle v, Instant now, Map<String, Object> details) {
        emit(ids.nextEventId(), topic, type, v, now, details);
    }

    public void emit(String id, String topic, String type, Vehicle v, Instant now, Map<String, Object> details) {
        events.publish(topic, v.getId(), FleetEvent.from(id, type, v, now,
                MDC.get(CorrelationIdFilter.MDC_KEY), details));
    }

    private boolean exists(String sql, String id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, id));
    }
}
