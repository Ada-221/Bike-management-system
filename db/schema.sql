
-- zones: named circular areas used to group vehicles
CREATE TABLE zones (
    id                UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    name              VARCHAR(100)     NOT NULL UNIQUE,
    center_latitude   DOUBLE PRECISION NOT NULL
                      CHECK (center_latitude  BETWEEN -90  AND 90),
    center_longitude  DOUBLE PRECISION NOT NULL
                      CHECK (center_longitude BETWEEN -180 AND 180),
    radius_meters     INTEGER          NOT NULL CHECK (radius_meters > 0),
    created_at        TIMESTAMPTZ      NOT NULL DEFAULT now()
);

-- vehicles: the physical e-bikes and e-scooters
CREATE TABLE vehicles (
    id                 UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    label              VARCHAR(50)      NOT NULL UNIQUE,   -- e.g. 'EB-001'
    type               VARCHAR(20)      NOT NULL
                       CHECK (type IN ('E_BIKE', 'E_SCOOTER')),
    status             VARCHAR(30)      NOT NULL DEFAULT 'AVAILABLE'
                       CHECK (status IN ('AVAILABLE', 'RENTED', 'MAINTENANCE_REQUIRED')),
    battery_percent    INTEGER          NOT NULL
                       CHECK (battery_percent BETWEEN 0 AND 100),
    latitude           DOUBLE PRECISION NOT NULL CHECK (latitude  BETWEEN -90  AND 90),
    longitude          DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    zone_id            UUID             REFERENCES zones (id) ON DELETE SET NULL,
    last_telemetry_at  TIMESTAMPTZ,                         -- used to ignore stale events
    version            BIGINT           NOT NULL DEFAULT 0, -- optimistic locking
    created_at         TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ      NOT NULL DEFAULT now()
);

-- Indexes for the most common queries (dashboard filters, nearby search).
CREATE INDEX idx_vehicles_status   ON vehicles (status);
CREATE INDEX idx_vehicles_zone     ON vehicles (zone_id);
CREATE INDEX idx_vehicles_location ON vehicles (latitude, longitude); -- bounding-box search
CREATE INDEX idx_vehicles_battery  ON vehicles (battery_percent);


-- rentals: one row per trip
CREATE TABLE rentals (
    id                     UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    vehicle_id             UUID             NOT NULL REFERENCES vehicles (id),
    rider_id               VARCHAR(64)      NOT NULL,   -- no rider table (no auth in scope)
    status                 VARCHAR(20)      NOT NULL
                           CHECK (status IN ('ACTIVE', 'COMPLETED')),
    started_at             TIMESTAMPTZ      NOT NULL DEFAULT now(),
    ended_at               TIMESTAMPTZ,
    start_latitude         DOUBLE PRECISION NOT NULL,
    start_longitude        DOUBLE PRECISION NOT NULL,
    end_latitude           DOUBLE PRECISION,
    end_longitude          DOUBLE PRECISION,
    start_battery_percent  INTEGER          NOT NULL
                           CHECK (start_battery_percent BETWEEN 0 AND 100),
    end_battery_percent    INTEGER
                           CHECK (end_battery_percent BETWEEN 0 AND 100),
    idempotency_key        VARCHAR(100)     NOT NULL UNIQUE,  -- from the Idempotency-Key header

    -- An ACTIVE rental has no end data; a COMPLETED rental has all end data.
    CONSTRAINT chk_rental_end_consistency CHECK (
        (status = 'ACTIVE'
            AND ended_at IS NULL
            AND end_latitude IS NULL
            AND end_longitude IS NULL
            AND end_battery_percent IS NULL)
        OR
        (status = 'COMPLETED'
            AND ended_at IS NOT NULL
            AND end_latitude IS NOT NULL
            AND end_longitude IS NOT NULL
            AND end_battery_percent IS NOT NULL)
    ),
    CONSTRAINT chk_rental_time_order CHECK (ended_at IS NULL OR ended_at >= started_at)
);

-- Business rule enforcement: at most ONE active rental per vehicle and per rider.
-- If two requests race, the second INSERT fails and the service returns 409.
CREATE UNIQUE INDEX uq_rentals_one_active_per_vehicle
    ON rentals (vehicle_id) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX uq_rentals_one_active_per_rider
    ON rentals (rider_id)   WHERE status = 'ACTIVE';

CREATE INDEX idx_rentals_vehicle ON rentals (vehicle_id);
CREATE INDEX idx_rentals_rider   ON rentals (rider_id);
CREATE INDEX idx_rentals_started ON rentals (started_at); -- demand statistics


-- maintenance_records: history of faults, low batteries and manual reports
CREATE TABLE maintenance_records (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    vehicle_id   UUID         NOT NULL REFERENCES vehicles (id),
    reason       VARCHAR(20)  NOT NULL
                 CHECK (reason IN ('LOW_BATTERY', 'FAULT', 'MANUAL')),
    status       VARCHAR(20)  NOT NULL DEFAULT 'OPEN'
                 CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED')),
    fault_code   VARCHAR(50),
    description  TEXT,
    opened_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at  TIMESTAMPTZ,

    -- resolved_at is set if and only if the record is RESOLVED.
    CONSTRAINT chk_maintenance_resolved CHECK (
        (status = 'RESOLVED' AND resolved_at IS NOT NULL)
        OR (status <> 'RESOLVED' AND resolved_at IS NULL)
    )
);

-- At most one unresolved record per vehicle and reason. A duplicate telemetry
-- event therefore cannot create a second "low battery" record.
CREATE UNIQUE INDEX uq_maintenance_one_open_per_reason
    ON maintenance_records (vehicle_id, reason) WHERE status <> 'RESOLVED';

CREATE INDEX idx_maintenance_vehicle ON maintenance_records (vehicle_id);
CREATE INDEX idx_maintenance_status  ON maintenance_records (status);

-- processed_events: lets Kafka consumers ignore duplicate deliveries
CREATE TABLE processed_events (
    event_id      UUID        PRIMARY KEY,   -- eventId from the event envelope
    processed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);