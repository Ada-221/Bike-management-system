package com.cebp.fleet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Proves the Flyway migrations build the schema and that the database enforces the rules (D11). */
class MigrationIT extends AbstractPostgresIT {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource datasource;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE rentals, maintenance_records, vehicles, zones, processed_events");
    }

    @Test
    void migrationsWereApplied() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success", Integer.class);
        assertThat(applied).isGreaterThanOrEqualTo(3);
    }

    @Test
    void upgradesExistingWeekTwoFleetAndQueuesOneRegistrationPerVehicle() {
        String schema = "week2_upgrade";
        Flyway.configure().dataSource(datasource).schemas(schema).target("2").load().migrate();
        jdbc.update("""
                insert into week2_upgrade.vehicles (id, label, type, battery_percent, latitude, longitude)
                values ('veh_099', 'Legacy low', 'E_BIKE', 19, 48.21, 16.36)
                """);
        Flyway flyway = Flyway.configure().dataSource(datasource).schemas(schema).load();
        flyway.migrate();
        flyway.migrate();
        assertThat(jdbc.queryForObject("select status from week2_upgrade.vehicles where id = 'veh_099'", String.class))
                .isEqualTo("MAINTENANCE_REQUIRED");
        assertThat(jdbc.queryForObject("select count(*) from week2_upgrade.maintenance_records", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from week2_upgrade.fleet_outbox", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select body->'payload'->>'locationMeasuredAt' from week2_upgrade.fleet_outbox
                where body->>'eventType' = 'VehicleRegistered' and body->'payload'->'zoneId' = 'null'::jsonb
                """, String.class)).endsWith("Z");
    }

    @Test
    void allTablesExist() {
        Integer tables = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema = 'public'
                  and table_name in ('zones','vehicles','rentals','maintenance_records','processed_events')
                """, Integer.class);
        assertThat(tables).isEqualTo(5);
    }

    @Test
    void idSequencesExist() {
        Integer sequences = jdbc.queryForObject("""
                select count(*) from information_schema.sequences
                where sequence_schema = 'public'
                  and sequence_name in ('vehicle_id_seq','rental_id_seq','maintenance_id_seq','event_id_seq')
                """, Integer.class);
        assertThat(sequences).isEqualTo(4);
    }

    @Test
    void vehicleIdMustHaveTheVehPrefix() {
        assertThatThrownBy(() -> insertVehicle("bad-id", "X-1", "AVAILABLE", 50))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void batteryMustBeBetween0And100() {
        assertThatThrownBy(() -> insertVehicle("veh_001", "X-1", "AVAILABLE", 150))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void statusMustBeAKnownValue() {
        assertThatThrownBy(() -> insertVehicle("veh_001", "X-1", "BROKEN", 50))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void labelMustBeUnique() {
        insertVehicle("veh_001", "EB-001", "AVAILABLE", 50);
        assertThatThrownBy(() -> insertVehicle("veh_002", "EB-001", "AVAILABLE", 50))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aVehicleCanHaveOnlyOneActiveRental() {
        insertVehicle("veh_001", "EB-001", "AVAILABLE", 80);
        insertActiveRental("rnt_001", "veh_001", "rider-1", "key-1");

        assertThatThrownBy(() -> insertActiveRental("rnt_002", "veh_001", "rider-2", "key-2"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aRiderCanHaveOnlyOneActiveRental() {
        insertVehicle("veh_001", "EB-001", "AVAILABLE", 80);
        insertVehicle("veh_002", "EB-002", "AVAILABLE", 80);
        insertActiveRental("rnt_001", "veh_001", "rider-1", "key-1");

        assertThatThrownBy(() -> insertActiveRental("rnt_002", "veh_002", "rider-1", "key-2"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void idempotencyKeyMustBeUnique() {
        insertVehicle("veh_001", "EB-001", "AVAILABLE", 80);
        insertVehicle("veh_002", "EB-002", "AVAILABLE", 80);
        insertActiveRental("rnt_001", "veh_001", "rider-1", "same-key");

        assertThatThrownBy(() -> insertActiveRental("rnt_002", "veh_002", "rider-2", "same-key"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyOneUnresolvedMaintenanceRecordPerVehicleAndReason() {
        insertVehicle("veh_001", "EB-001", "AVAILABLE", 10);
        jdbc.update("insert into maintenance_records (id, vehicle_id, reason, status) "
                + "values ('mnt_001', 'veh_001', 'LOW_BATTERY', 'OPEN')");

        assertThatThrownBy(() -> jdbc.update("insert into maintenance_records (id, vehicle_id, reason, status) "
                + "values ('mnt_002', 'veh_001', 'LOW_BATTERY', 'OPEN')"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // A different reason is fine, and so is a new record once the old one is resolved.
        jdbc.update("insert into maintenance_records (id, vehicle_id, reason, status) "
                + "values ('mnt_003', 'veh_001', 'FAULT', 'OPEN')");
        jdbc.update("update maintenance_records set status = 'RESOLVED', resolved_at = now() where id = 'mnt_001'");
        jdbc.update("insert into maintenance_records (id, vehicle_id, reason, status) "
                + "values ('mnt_004', 'veh_001', 'LOW_BATTERY', 'OPEN')");
    }

    private void insertVehicle(String id, String label, String status, int battery) {
        jdbc.update("insert into vehicles (id, label, type, status, battery_percent, latitude, longitude) "
                + "values (?, ?, 'E_BIKE', ?, ?, 48.21, 16.36)", id, label, status, battery);
    }

    private void insertActiveRental(String id, String vehicleId, String riderId, String idempotencyKey) {
        jdbc.update("insert into rentals (id, vehicle_id, rider_id, status, start_latitude, start_longitude, "
                + "start_battery_percent, idempotency_key) values (?, ?, ?, 'ACTIVE', 48.21, 16.36, 80, ?)",
                id, vehicleId, riderId, idempotencyKey);
    }
}
