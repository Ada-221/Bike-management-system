# Telemetry contract — settled measurement events

Battery and location are separate events on the existing `fleet.vehicle` Kafka
topic. The Kafka record key is the same `vehicleId` carried in the event. Do not
publish a combined battery/location event for the same measurements.

## Shared event envelope

Each event carries `eventId`, `eventType`, `schemaVersion`, `vehicleId`,
`measuredAt`, `publishedAt`, `correlationId`, `producer`, and `payload` at the
top level. Timestamps are UTC ISO-8601 strings ending in `Z`. `measuredAt` is
when the sensor made the observation; `publishedAt` is when the normalized event
was published. The timestamps are distinct fields and may have different values.

`vehicleId` uses a stable `veh_` identifier. Both battery and location event
IDs use the `evt_` prefix, matching the Java producer. A retry preserves its
event ID. The producer is `telemetry-ingestion-service`.

## Event payloads

- `VehicleBatteryUpdated`: `{ "batteryPercent": 0..100 }`.
- `VehicleLocationUpdated`: `{ "latitude": -90..90, "longitude": -180..180 }`.

See [battery example](telemetry-example.json) and
[location example](location-example.json). The combined
[`telemetry.schema.json`](telemetry.schema.json) accepts exactly one of the two
event shapes.

## Independent measurement freshness

Consumers track the most recent accepted `measuredAt` separately for battery
and location. A newer battery event does not make a location event stale, or
vice versa. Each field compares events by `(measuredAt, publishedAt, eventId)`.
The greatest tuple wins.

`VehicleFaultReported` uses the same envelope and vehicle key on `fleet.vehicle`.
It carries a required nonblank `faultCode` and an optional nonblank description.
See the [fault schema](fault.schema.json) and [example](fault-example.json).

## Still open

- The `OFFLINE` timeout and evidence rule.
- A shared fault code list.
- Kafka retention, retry, dead-letter, and complete producer/consumer details.

See the [integration decision register](integration-review.md) for owners and
the rest of the unresolved integration decisions.
