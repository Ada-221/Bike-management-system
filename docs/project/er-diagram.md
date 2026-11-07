# Fleet Management Service: ER Diagram (v0.1)

```mermaid
erDiagram
    ZONES ||--o{ VEHICLES : "contains"
    VEHICLES ||--o{ RENTALS : "is rented in"
    VEHICLES ||--o{ MAINTENANCE_RECORDS : "has"

    ZONES {
        uuid id PK
        varchar name UK
        double center_latitude
        double center_longitude
        int radius_meters
        timestamptz created_at
    }

    VEHICLES {
        uuid id PK
        varchar label UK
        varchar type
        varchar status
        int battery_percent
        double latitude
        double longitude
        uuid zone_id FK
        timestamptz last_telemetry_at
        bigint version
        timestamptz created_at
        timestamptz updated_at
    }

    RENTALS {
        uuid id PK
        uuid vehicle_id FK
        varchar rider_id
        varchar status
        timestamptz started_at
        timestamptz ended_at
        double start_latitude
        double start_longitude
        double end_latitude
        double end_longitude
        int start_battery_percent
        int end_battery_percent
        varchar idempotency_key UK
    }

    MAINTENANCE_RECORDS {
        uuid id PK
        uuid vehicle_id FK
        varchar reason
        varchar status
        varchar fault_code
        text description
        timestamptz opened_at
        timestamptz resolved_at
    }

    PROCESSED_EVENTS {
        uuid event_id PK
        timestamptz processed_at
    }
```

## Reading the diagram

- **One zone has many vehicles.** `zone_id` is optional because a vehicle can be outside every zone.
- **One vehicle has many rentals over time**, but only one can be `ACTIVE` at once (partial unique index).
- **One vehicle has many maintenance records over time**, with at most one unresolved record per reason (partial unique index).
- **`PROCESSED_EVENTS` stands alone.** It is internal bookkeeping so Kafka consumers can ignore duplicate events.

## Constraints that are not visible in the diagram

| Constraint | Table | Purpose |
|---|---|---|
| `battery_percent` between 0 and 100 | vehicles | Reject invalid data |
| `status`, `type`, `reason` limited to known values | all | Reject typos |
| One `ACTIVE` rental per vehicle | rentals | Prevent double rentals |
| One `ACTIVE` rental per rider | rentals | Business rule BR-02 |
| One unresolved record per vehicle and reason | maintenance_records | Make duplicate telemetry harmless |
| `ended_at` set if and only if `COMPLETED` | rentals | Keep data consistent |
