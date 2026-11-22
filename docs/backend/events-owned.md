# Fleet Service: Events Published and Consumed

The [v1 event contract](../../contracts/fleet/v1/README.md) is the source of truth for event
shapes. Every fleet event uses the same envelope (`schemaVersion`, `vehicleId`, `correlationId`,
`producer`, `eventId`, `eventType`, `publishedAt`, `stateRevision`, nested `payload`). The full
JSON Schema is [fleet-event.schema.json](../../contracts/fleet/v1/fleet-event.schema.json) and
real examples of `VehicleRegistered`, `RentalStarted`, `VehicleMaintenanceChanged` and
`RentalEnded` are in
[rental-fault-return.example.json](../../contracts/fleet/v1/rental-fault-return.example.json).
The Week 1 flat examples that used to be in this file were removed because they no longer match
the envelope.

## 1. Events Published

| Topic | Event | Producer |
|---|---|---|
| `fleet.vehicle` | `VehicleRegistered`, `VehicleStateUpdated` | fleet-management-service |
| `fleet.rental` | `RentalStarted`, `RentalEnded` | fleet-management-service |
| `fleet.maintenance` | `VehicleMaintenanceChanged` | fleet-management-service |

## 2. Events Consumed

Telemetry shapes (`VehicleBatteryUpdated`, `VehicleLocationUpdated`, `VehicleFaultReported`) are
owned by telemetry-ingestion; see the [fault schema](../../fault.schema.json) and the
[telemetry schema](../../telemetry.schema.json). Fleet validates required fields and ignores
unknown additional fields in the envelope and payload.

| Event | Producer | Payload Requirements | Action / Rule Applied |
|---|---|---|---|
| `VehicleBatteryUpdated` | telemetry-ingestion |  `eventId`, `vehicleId`, `batteryPercent`, `measuredAt`, `publishedAt` | Update `batteryPercent`; apply BR-08 and BR-10; trigger SSE `vehicle.updated`. |
| `VehicleLocationUpdated` | telemetry-ingestion | `eventId`, `vehicleId`, `latitude`, `longitude`, `measuredAt`, `publishedAt` | Update `latitude` / `longitude`; recompute `zoneId`; trigger SSE `vehicle.updated`. |
| `VehicleFaultReported` | telemetry-ingestion |  `eventId`, `vehicleId`, `faultCode`, `description` (optional), `measuredAt`, `publishedAt`| Create `FAULT` record per BR-09; publish `VehicleMaintenanceChanged`. |
| `RebalancingProposalApproved` | ai-planner |  `eventId`, `proposalId`, `approvedBy`, `approvedAt`, `publishedAt`, `actions[]` (`type`, `vehicleId`, `targetZoneId`) | Apply rebalancing operational changes; update maintenance status. |
| `RebalancingProposalRejected` | ai-planner |  `eventId`, `proposalId`, `rejectedBy`, `rejectedAt`, `publishedAt` | Audit log entry only. No status change. |

---

## 3. Consumer Event Handling Logic

1. **Idempotency Check:** Search `eventId` in `processed_events`. If present, skip execution. Otherwise, process message and persist `eventId` within the same database transaction.
2. **Stale Event Check:** For telemetry events, if `measuredAt < vehicle.last_telemetry_at`, skip execution.
3. **Missing Record Handling:** If `vehicleId` does not match an existing entity, log warning and skip.
4. **Context Propagation:** Preserve `correlationId` in all downstream events emitted during handling.
5. **Independent Freshness Check:**
  - For VehicleBatteryUpdated, compare measuredAt against vehicle.last_battery_at (skip if stale).
  - For VehicleLocationUpdated, compare measuredAt against vehicle.last_location_at (skip if stale).
  - Update vehicle.last_telemetry_at as a general activity indicator on accepted telemetry, never using it to validate or reject battery or location measurements.
  - (Open item: Confirm exact behavior when measuredAt equals last_battery_at / last_location_at.)
