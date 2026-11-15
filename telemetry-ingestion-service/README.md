# Week 2 Telemetry Demo

From the repository root, copy `.env.example` to `.env` first. The normal stack
and even the telemetry profile leave the simulator disabled.

```bash
docker compose --profile telemetry up --build -d --wait kafka telemetry
```

Enable the simulator explicitly for one demo run (PowerShell users can enter
this command on one line):

```bash
docker compose --profile telemetry run --rm -e TELEMETRY_SIMULATOR_ENABLED=true telemetry
```

The web application remains running after publishing, so stop the one-off
container with Ctrl+C after the two `Published measurement` lines. Compose
selects the network automatically; no project-specific network name is needed.
The image connects to `kafka:9092` and waits for Kafka health.

In a second terminal, read the records:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic fleet.vehicle --from-beginning --property print.key=true --max-messages 4 --timeout-ms 10000
```

On a fresh topic, expect two battery events (75% and 74%) and two location
events, keyed `veh_001`, each with its own distinct `evt_` event ID. On an existing
topic, `--from-beginning` can return older records. For a repeatable check of fresh
records against the shared schema, run `node scripts/compose-smoke.mjs`; it uses
an isolated project and fresh volumes and captures logs under `build/`.

Telemetry does not update fleet state: backend consumers and the MCP Kafka
projection remain deferred. The host-side standalone default is
`localhost:29092`; override `KAFKA_BOOTSTRAP_SERVERS` when running elsewhere.
