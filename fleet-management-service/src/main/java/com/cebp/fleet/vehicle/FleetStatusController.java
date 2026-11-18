package com.cebp.fleet.vehicle;

import com.cebp.fleet.config.FleetProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class FleetStatusController {
    record ZoneStatus(String zoneId, String zoneName, long available, long rented, long maintenanceRequired) {}
    record FleetStatus(Instant generatedAt, long totalVehicles, Map<VehicleStatus, Long> countByStatus,
                       long activeRentals, long lowBatteryCount, double averageBatteryPercent, List<ZoneStatus> zones) {}

    private final JdbcTemplate jdbc;
    private final FleetProperties properties;
    private final Clock clock;

    FleetStatusController(JdbcTemplate jdbc, FleetProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    @GetMapping("/fleet/status")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FleetStatus status() {
        List<ZoneStatus> zones = jdbc.query("""
                select z.id, z.name, count(*) filter (where v.status = 'AVAILABLE'),
                    count(*) filter (where v.status = 'RENTED'),
                    count(*) filter (where v.status = 'MAINTENANCE_REQUIRED')
                from zones z left join vehicles v on v.zone_id = z.id group by z.id, z.name order by z.id
                """, (rs, row) -> new ZoneStatus(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)));
        return jdbc.queryForObject("""
                select count(*) as total, count(*) filter (where status = 'AVAILABLE') as available,
                    count(*) filter (where status = 'RENTED') as rented,
                    count(*) filter (where status = 'MAINTENANCE_REQUIRED') as maintenance,
                    (select count(*) from rentals where status = 'ACTIVE') as active_rentals,
                    count(*) filter (where battery_percent < ?) as low_battery,
                    coalesce(avg(battery_percent), 0) as average
                from vehicles
                """, (rs, row) -> new FleetStatus(clock.instant(), rs.getLong("total"), Map.of(
                    VehicleStatus.AVAILABLE, rs.getLong("available"), VehicleStatus.RENTED, rs.getLong("rented"),
                    VehicleStatus.MAINTENANCE_REQUIRED, rs.getLong("maintenance")), rs.getLong("active_rentals"),
                    rs.getLong("low_battery"), rs.getDouble("average"), zones), properties.minRentableBatteryPercent());
    }
}
