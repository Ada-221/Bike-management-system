# Week 3 telemetry demo

The normal stack and the telemetry profile leave the simulator disabled.
Register a vehicle through the fleet API first. The simulator targets
`veh_001` by default. Set `TELEMETRY_SIMULATOR_VEHICLE_ID` if registration
returns a different ID.

Run the one-shot simulator from the repository root.

```bash
docker compose --profile telemetry run --rm -e TELEMETRY_SIMULATOR_ENABLED=true -e SPRING_MAIN_WEB_APPLICATION_TYPE=none telemetry
```

The baseline scenario publishes two battery readings (75% then 74%) and two
locations. Wait for the fleet API to report 74%. To report a brake sensor
fault, add `-e TELEMETRY_SIMULATOR_SCENARIO=fault` before `telemetry` in the
same command. The fleet API reports `MAINTENANCE_REQUIRED` even during a
trip. The rider can still return the active rental.

Set `TELEMETRY_SIMULATOR_SCENARIO=replay` to publish newer, duplicate, and late
battery and location measurements. For the same vehicle and
`TELEMETRY_SIMULATOR_REPLAY_AT` (UTC ISO timestamp), the same records are emitted
in the same order. For a live check, choose a time newer than the registration
and the vehicle's latest readings. The final battery should be 65% and the
location should be `48.2105, 16.3605`. The default date is fixed for fixture replay.

Use `node scripts/compose-smoke.mjs` to check real telemetry against fleet REST
and MCP on a fresh broker. See the [telemetry contract](../telemetry-contract.md)
for event shapes and [fleet contract](../contracts/fleet/v1/README.md) for routing.

Compose connects to `kafka:9092`. The standalone broker default is
`localhost:29092`. Set `KAFKA_BOOTSTRAP_SERVERS` to use a different broker.
