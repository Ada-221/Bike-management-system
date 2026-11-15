# Kafka topics and routing

The project keeps its three existing domain topics. Do not add `fleet.events`.
Telemetry battery and location updates are separate events on `fleet.vehicle`.

| Topic | Settled routing | Event details still requiring owner agreement |
|---|---|---|
| `fleet.vehicle` | `VehicleBatteryUpdated` and `VehicleLocationUpdated`, keyed by the Kafka record key `vehicleId`. | Complete producer/consumer matrix, schema version rollout, retention, retry, and dead-letter policy. `VehicleRegistered` owner/routing remains to be confirmed. |
| `fleet.rental` | Existing rental domain topic. | Exact event routing and producer/consumer ownership. Demand may be derived from rentals or published separately; `DemandRecorded` ownership and payload remain open. |
| `fleet.maintenance` | Existing maintenance domain topic. | Exact event routing and producer/consumer ownership, including unsettled fault description rules. |

## Settled telemetry envelope

Battery and location messages carry top-level `eventId`, `eventType`,
`schemaVersion`, `vehicleId`, `measuredAt`, `publishedAt`, `correlationId`,
`producer`, and `payload`. `measuredAt` and `publishedAt` are distinct UTC
timestamps. Event IDs use distinct `battery_…` or `location_…` prefixes and are
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
