
# Telemetry Ingestion Service — Week 4

This service publishes `VehicleBatteryUpdated`, `VehicleLocationUpdated`, and
`VehicleFaultReported` records to Kafka topic `fleet.vehicle`, keyed by vehicle ID.
The producer validates readings before sending. The fleet backend applies events
to the database, deduplicates IDs, and ignores stale measurements.

## Run unit tests

From the repository root:

```bash
mvn -f telemetry-ingestion-service/pom.xml test
```

Alternatively, run tests using Docker:

```bash
docker run --rm \
  -v "$PWD/telemetry-ingestion-service:/app" \
  -w /app maven:3.9-eclipse-temurin-21 mvn -ntp test
```

## Run scenarios

Register a vehicle with the fleet API first; use the returned ID. The default
simulator ID is `veh_001`. Run Kafka and the telemetry image from the repo root:

```bash
docker compose --profile telemetry up --build -d --wait kafka
docker compose --profile telemetry build telemetry
```

Start the simulator with a selected scenario:

```bash
docker compose --profile telemetry run --rm \
  -e TELEMETRY_SIMULATOR_ENABLED=true \
  -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
  -e TELEMETRY_SIMULATOR_VEHICLE_ID=veh_001 \
  -e TELEMETRY_SIMULATOR_SCENARIO=out-of-order telemetry
```

Available scenarios:

- `baseline` (or `normal`): 2 battery and 2 location events
  (75% then 74%).
- `out-of-order`: newer measurement followed by an older measurement;
  downstream state should retain the newer battery/location.
- `duplicate-event`: 2 battery and 2 location deliveries with the **same
  original IDs and JSON** (4 messages total); downstream application should
  count each event ID only once.
- `replay`: combines duplicates and late battery and location measurements.
  For the same vehicle and `TELEMETRY_SIMULATOR_REPLAY_AT` (UTC ISO timestamp),
  it emits the same records, including IDs, in the same order. The default date
  is fixed for fixture replay. For a live check, choose a time newer than the
  registration and the vehicle's latest readings. The final battery should be
  65% and the location should be `48.2105, 16.3605`.
- `fault`: one `VehicleFaultReported` for the configured vehicle. Fleet marks it
  `MAINTENANCE_REQUIRED`. The rider can still return an active rental.
- `continuous`: continuously publishes battery and location events with a
  5-second delay between completed publishing cycles. Battery starts at 100%
  and gradually decreases to 0%. Stop with Ctrl+C.
- `low-battery`: publishes deterministic battery readings of 25%, 15%, 10%,
  and 5%.

For one-shot scenarios other than `replay`, supply
`TELEMETRY_SIMULATOR_START_AT=2026-11-22T12:00:00Z` to repeat the exact
`measuredAt` timestamps; omit it for fresh live demos. Do not use an old
fixed timestamp for a newly registered vehicle: the fleet backend can
correctly reject historical measurements as stale.

The continuous scenario uses current measurement timestamps.

You can inspect the raw Kafka records with:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:9092 --topic fleet.vehicle \
  --from-beginning --property print.key=true --max-messages 4 --timeout-ms 10000
```

The topic may contain older records, so compare event IDs/timestamps rather
than treating old messages as the latest run. Run `node scripts/compose-smoke.mjs`
from the repository root to verify telemetry replay against fleet REST and MCP
on a fresh broker.

## Week 4 — Continuous telemetry and reliability

### Run continuous telemetry

```bash
docker compose --profile telemetry run --rm \
  -e TELEMETRY_SIMULATOR_ENABLED=true \
  -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
  -e TELEMETRY_SIMULATOR_VEHICLE_ID=veh_001 \
  -e TELEMETRY_SIMULATOR_SCENARIO=continuous telemetry
```

Use `low-battery` instead of `continuous` to run the low-battery scenario.

### Kafka producer reliability

| Setting | Value |
|---|---|
| Acknowledgments | all |
| Retries | 5 |
| Idempotence | true |
| Retry backoff | 1000 ms |
| Request timeout | 30000 ms |
| Delivery timeout | 120000 ms |

### Broker outage and recovery test

Start the continuous simulator. In a separate terminal, run:

```bash
docker compose stop kafka && sleep 10 && docker compose start kafka
```

The producer should reconnect and resume publishing after Kafka becomes
available again, without restarting the simulator.

This behavior was verified during local integration testing.

### Dead-letter queue integration

The existing fleet consumer supports the `fleet.vehicle.DLT` topic.

The dead-letter policy is disabled by default and can be enabled for local
integration testing with:

```bash
FLEET_KAFKA_DEAD_LETTER_ENABLED=true \
docker compose --profile telemetry up -d --build --wait fleet-management-service
```

Send an intentionally malformed record in the local test environment:

```bash
printf 'week4-dlq-test:invalid-json\n' | \
docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka:9092 \
  --topic fleet.vehicle \
  --property parse.key=true \
  --property key.separator=:
```

Inspect the dead-letter topic:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:9092 \
  --topic fleet.vehicle.DLT \
  --from-beginning \
  --property print.key=true \
  --timeout-ms 10000
```

Verified output:

```text
week4-dlq-test  invalid-json
```

The malformed record was successfully routed to `fleet.vehicle.DLT`.
The existing backend implementation and default configuration remain unchanged.

See `docs/integration/dead-letter-draft.md` for the proposed policy.

### Test results

| Test | Result |
|---|---|
| Unit tests | 24 passed, 0 failures |
| Docker image build | Passed |
| Continuous telemetry publishing | Passed |
| Low-battery publishing | Passed |
| Fault event publishing | Passed |
| Kafka broker outage and recovery | Passed |
| Dead-letter topic routing | Passed |

Message loss and duplicate counts during broker recovery were not
independently verified.

### Pending integration decision

`DemandRecorded` publishing remains pending agreement between Persons 2 and 5.

The open decision is whether demand should be published by the simulator
or derived by MCP from `RentalStarted` events.

See Q4 in `docs/backend/domain-model.md`.

No new demand topic or event schema has been introduced.

## Constraints

Validation rules match the Week 3 fleet consumer's ID length and battery/GPS
bounds. The shared telemetry.schema.json currently allows longer IDs; do not
relax the consumer-facing limit without coordinating contract updates.

The `fleet.vehicle` topic, record key and schemaVersion `1.0` stay unchanged.
