ALTER TABLE vehicles
    ADD COLUMN last_battery_order TEXT,
    ADD COLUMN last_location_order TEXT,
    ADD COLUMN last_fault_order TEXT;

ALTER TABLE processed_events ADD COLUMN fingerprint TEXT;
ALTER SEQUENCE event_id_seq MAXVALUE 9007199254740991;

CREATE TABLE fleet_outbox (
    event_id VARCHAR(40) PRIMARY KEY,
    topic VARCHAR(40) NOT NULL,
    vehicle_id VARCHAR(40) NOT NULL,
    body JSONB NOT NULL,
    state_revision BIGINT NOT NULL UNIQUE,
    queued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at TIMESTAMPTZ
);
CREATE INDEX idx_fleet_outbox_pending ON fleet_outbox (state_revision) WHERE delivered_at IS NULL;

-- Week 2 registration could leave low-battery vehicles AVAILABLE and only logged events.
WITH low_battery AS MATERIALIZED (
    SELECT id, nextval('maintenance_id_seq')::text AS seq FROM vehicles WHERE battery_percent < 20
)
INSERT INTO maintenance_records (id, vehicle_id, reason, status)
SELECT 'mnt_' || lpad(seq, greatest(3, length(seq)), '0'), id, 'LOW_BATTERY', 'OPEN'
FROM low_battery
ON CONFLICT DO NOTHING;

UPDATE vehicles SET status = 'MAINTENANCE_REQUIRED', updated_at = now(), version = version + 1
WHERE status = 'AVAILABLE' AND EXISTS (
    SELECT 1 FROM maintenance_records m WHERE m.vehicle_id = vehicles.id AND m.status <> 'RESOLVED'
);

WITH baseline AS MATERIALIZED (
    SELECT v.*, nextval('event_id_seq') AS revision FROM vehicles v
)
INSERT INTO fleet_outbox (event_id, topic, vehicle_id, state_revision, body)
SELECT event.id, 'fleet.vehicle', baseline.id, revision, jsonb_build_object(
    'eventId', event.id, 'eventType', 'VehicleRegistered', 'schemaVersion', '1.0',
    'vehicleId', baseline.id, 'publishedAt', to_char(statement_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
    'correlationId', event.id, 'producer', 'fleet-management-service', 'stateRevision', revision,
    'payload', jsonb_build_object('status', status, 'zoneId', zone_id, 'batteryPercent', battery_percent,
        'latitude', latitude, 'longitude', longitude,
        'batteryMeasuredAt', to_char(last_battery_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
        'locationMeasuredAt', to_char(last_location_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'))
)
FROM baseline
CROSS JOIN LATERAL (SELECT 'evt_' || lpad(revision::text, greatest(4, length(revision::text)), '0') AS id) event;
