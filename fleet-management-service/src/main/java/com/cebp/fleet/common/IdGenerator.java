package com.cebp.fleet.common;

import java.util.Objects;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Builds prefixed IDs (veh_001, evt_0001) from the PostgreSQL sequences created in V2. */
@Component
public class IdGenerator {

    private final JdbcTemplate jdbc;

    public IdGenerator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String nextVehicleId() {
        return String.format(Locale.ROOT, "veh_%03d", next("vehicle_id_seq"));
    }

    public String nextEventId() {
        return String.format(Locale.ROOT, "evt_%04d", next("event_id_seq"));
    }

    public String nextRentalId() {
        return String.format(Locale.ROOT, "rnt_%03d", next("rental_id_seq"));
    }

    public String nextMaintenanceId() {
        return String.format(Locale.ROOT, "mnt_%03d", next("maintenance_id_seq"));
    }

    // The sequence name is always a constant from this class, never user input.
    private long next(String sequence) {
        Long value = jdbc.queryForObject("select nextval('" + sequence + "')", Long.class);
        return Objects.requireNonNull(value);
    }
}
