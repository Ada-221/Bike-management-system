# fleet-management-service

Spring Boot service owning vehicles, zones, rentals and maintenance (Person 1).
Contract: `api/fleet-api.yaml`. Domain rules: [backend domain model](../docs/backend/domain-model.md).

## Run locally (PowerShell, from the repo root)

    docker compose up -d postgres
    cd fleet-management-service
    mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
    curl.exe http://localhost:8080/api/v1/health

`dev` profile = also loads demo zones (`zone_001`, `zone_002`). Settings and
business constants live in `src/main/resources/application.yml`; database
credentials can be overridden with `FLEET_DB_URL`, `FLEET_DB_USER`, `FLEET_DB_PASSWORD`.

## Run everything in Docker instead

    # First copy .env.example to .env in the repository root.
    docker compose up -d --build --wait

This builds the service image and starts Postgres, the service (port 8080) and the
dashboard. Stop any `mvn spring-boot:run` first, because both use port 8080.
Use `docker compose logs -f fleet-management-service` to follow the log and
`docker compose down` to stop (add `-v` to also wipe the database).

## Tests

    mvn test       # fast unit and web-layer tests, no Docker needed
    mvn verify     # everything, including integration tests (*IT) that need Docker Desktop running

Integration tests start a throwaway PostgreSQL container, build the schema with
the real Flyway migrations and call the API through MockMvc.

## Try the vehicle API (second PowerShell window)

    $body = @{ label="EB-001"; type="E_BIKE"; latitude=48.2102; longitude=16.3598; batteryPercent=92 } | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/vehicles -ContentType "application/json" -Body $body
    Invoke-RestMethod http://localhost:8080/api/v1/vehicles
    Invoke-RestMethod http://localhost:8080/api/v1/vehicles/veh_001
    Invoke-RestMethod "http://localhost:8080/api/v1/vehicles?status=AVAILABLE&maxBattery=50&limit=10"

To see error bodies and status codes, use `curl.exe -i` with the JSON in a file:

    $body | Out-File -Encoding ascii vehicle.json
    curl.exe -i -X POST -H "Content-Type: application/json" --data "@vehicle.json" http://localhost:8080/api/v1/vehicles
    curl.exe -i http://localhost:8080/api/v1/vehicles/veh_999

Registering the same label twice returns `409 DUPLICATE_VEHICLE_LABEL`; an unknown
ID returns `404 NOT_FOUND`; bad input returns `400 VALIDATION_ERROR`.
Run the Compose smoke for the complete nearby, rental, fault, and return flow.
Compose enables Kafka delivery by default. When running Spring Boot directly,
set `FLEET_KAFKA_ENABLED=true` and point `KAFKA_BOOTSTRAP_SERVERS` at a broker
with all three fleet topics.

## Database

Flyway runs `src/main/resources/db/migration` on startup. Never edit an applied
migration; add a new `V<n>__name.sql` instead.

For a Week 2 upgrade, back up the database and Kafka history. Keep telemetry
paused while V3 migrates existing vehicles and queues one registration snapshot
for each of them. Start the fleet service to drain the outbox, then activate the
reviewed MCP adapter and resume telemetry. Inspect existing topics for
complete retained history. Use the documented MCP offline rebuild for a
preexisting projection. Keep the database with its outbox and avoid deleting
Kafka history that an independent projection needs for replay.

Outbox publish failures log the oldest pending age in seconds. The fleet API
can still commit while Kafka is unavailable. Delivery retries with the original
event ID after the broker recovers. On Docker 29, a local Maven test run may
require `mvn "-Dapi.version=1.44" verify`.

## Telemetry error handling (draft)

Records on `fleet.vehicle` that fail validation (`IllegalArgumentException`) can never succeed.
With `fleet.kafka.dead-letter-enabled=true` (env `FLEET_KAFKA_DEAD_LETTER_ENABLED`) they are
published to `fleet.vehicle.DLT` and the consumer continues. Transient failures (database or
Kafka unavailable) are still retried every `fleet.kafka.retry-interval-ms` (5000) without limit.
The flag is off by default, which keeps the previous behavior of retrying every failure forever.
Compose creates the `fleet.vehicle.DLT` topic. The policy is a proposal: see
[the dead-letter draft](../docs/integration/dead-letter-draft.md).

Unknown telemetry fields are ignored. The zone list is cached for
`fleet.zone-cache-ttl-seconds` (60); zones are read-only in this service.

## `processed_events` growth and retention

Every consumed telemetry event stores one row (`event_id`, `fingerprint`) in `processed_events`
for idempotency and conflict detection, and nothing deletes them, so the table grows with
telemetry volume. Do not add automatic deletion until the replay and retention policy is decided.

Safe retention rule: a row may only be removed once the event can no longer be redelivered. That
means the row's event must be older than the Kafka retention of `fleet.vehicle` (today
`retention.ms=-1`, i.e. never, so nothing is safe to delete) and older than any planned replay
window or consumer-group offset reset. If a row is deleted early and the event is redelivered, the
duplicate is processed again and the conflicting-content check is lost. Until retention is
bounded, monitor the table size instead.
