# Kafka topics and routing

The project keeps its three existing domain topics. Do not add `fleet.events`.
Telemetry battery, location, and fault records are separate events on `fleet.vehicle`.

For the implemented topic routing and the adapter awaiting shared activation,
see the [Week 3 fleet event contract](contracts/fleet/v1/README.md).

## Settled telemetry envelope

Battery and location messages carry top-level `eventId`, `eventType`,
`schemaVersion`, `vehicleId`, `measuredAt`, `publishedAt`, `correlationId`,
`producer`, and `payload`. `measuredAt` and `publishedAt` are distinct UTC
timestamps. Each separate event uses its own distinct `evt_` ID; IDs are
preserved for retries. The producer is `telemetry-ingestion-service`.

The Kafka record key is `vehicleId`, matching the event field. Kafka ordering
alone does not resolve delayed measurements: consumers track battery and
location freshness independently. Equal-measurement-time behavior remains open.
See [telemetry contract](telemetry-contract.md) and the
[integration register](integration-review.md).

## Still open

- Kafka retention, retry, dead-letter handling, and partition-count policy.
- Producer and consumer ownership for every event outside the settled telemetry
  envelope.
- Demand event ownership, payload, and routing.
- Fault event requiredness and handling.
