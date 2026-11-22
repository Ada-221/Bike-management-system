# Week 3 telemetry producer — validation and deterministic scenarios

This service publishes `VehicleBatteryUpdated`, `VehicleLocationUpdated`, and
`VehicleFaultReported` records to Kafka topic `fleet.vehicle`, keyed by vehicle ID.
The producer validates readings before sending. The fleet backend applies events
to the database, deduplicates IDs, and ignores stale measurements.

## Run unit tests

From the repository root:

```bash
mvn -f telemetry-ingestion-service/pom.xml test
```

## Run scenarios

Register a vehicle with the fleet API first; use the returned ID. The default
simulator ID is `veh_001`. Run Kafka and the telemetry image from the repo root:

```bash
docker compose --profile telemetry up --build -d --wait kafka
docker compose --profile telemetry build telemetry
```

Start one one-shot simulator run with a selected scenario:

```bash
docker compose --profile telemetry run --rm \
  -e TELEMETRY_SIMULATOR_ENABLED=true \
  -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
  -e TELEMETRY_SIMULATOR_VEHICLE_ID=veh_001 \
  -e TELEMETRY_SIMULATOR_SCENARIO=out-of-order telemetry
```

Choose `baseline` (or `normal`), `out-of-order`, `duplicate-event`, `replay`, or `fault`.

- `baseline`: 2 battery and 2 location events (75% then 74%).
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

For scenarios other than `replay`, supply
`TELEMETRY_SIMULATOR_START_AT=2026-11-22T12:00:00Z` to repeat the exact
`measuredAt` timestamps; omit it for fresh live demos. Do not use an old
fixed timestamp for a newly registered vehicle: the fleet backend can
correctly reject historical measurements as stale.

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

## Constraints

Validation rules match the Week 3 fleet consumer's ID length and battery/GPS
bounds. The shared telemetry.schema.json currently allows longer IDs; do not
relax the consumer-facing limit without coordinating contract updates. The
`fleet.vehicle` topic, record key and schemaVersion `1.0` stay unchanged.
