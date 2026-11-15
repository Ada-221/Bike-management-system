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

`vehicleId` uses a stable `veh_…` identifier. Battery event IDs use the
`battery_…` prefix; location event IDs use `location_…`. A retry preserves its
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
vice versa. How distinct events with equal measurement times are ordered is
still open and must not be assumed by a consumer.

## Still open

- The `OFFLINE` timeout and evidence rule.
- Fault description requiredness and blank/length rules, fault code list,
  repeated-fault behavior, and resolution event.
- Kafka retention, retry, dead-letter, and complete producer/consumer details.
- Exact handling of equal measurement timestamps.

See the [integration decision register](integration-review.md) for owners and
the rest of the unresolved integration decisions. This file does not settle
fault payload validation or those operational policies.
