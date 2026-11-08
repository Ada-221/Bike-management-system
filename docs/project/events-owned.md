# Fleet Service: Events Published and Consumed

## 1. Events Published

**Topics:** `fleet.vehicle`, `fleet.rental`, `fleet.maintenance`

### 1.1 `VehicleRegistered` (topic `fleet.vehicle`)

```json
{
  "vehicleId": "b0000000-0000-0000-0000-000000000001",
  "label": "EB-001",
  "type": "E_BIKE",
  "status": "AVAILABLE",
  "batteryPercent": 92,
  "latitude": 48.2102,
  "longitude": 16.3598,
  "zoneId": "a0000000-0000-0000-0000-000000000001"
}
```

### 1.2 `RentalStarted` (topic `fleet.rental`)

```json
{
  "rentalId": "c0000000-0000-0000-0000-000000000001",
  "vehicleId": "b0000000-0000-0000-0000-000000000005",
  "riderId": "rider-demo-1",
  "startedAt": "2026-11-09T08:20:00Z",
  "startLatitude": 48.1858,
  "startLongitude": 16.3758,
  "startBatteryPercent": 70,
  "zoneId": "a0000000-0000-0000-0000-000000000002"
}
```

### 1.3 `RentalEnded` (topic `fleet.rental`)

```json
{
  "rentalId": "c0000000-0000-0000-0000-000000000001",
  "vehicleId": "b0000000-0000-0000-0000-000000000005",
  "riderId": "rider-demo-1",
  "startedAt": "2026-11-09T08:20:00Z",
  "endedAt": "2026-11-09T08:45:00Z",
  "durationSeconds": 1500,
  "endLatitude": 48.2099,
  "endLongitude": 16.3605,
  "endBatteryPercent": 58,
  "startZoneId": "a0000000-0000-0000-0000-000000000002",
  "endZoneId": "a0000000-0000-0000-0000-000000000001",
  "vehicleStatusAfter": "AVAILABLE"
}
```

### 1.4 `VehicleMaintenanceChanged` (topic `fleet.maintenance`)

```json
{
  "maintenanceId": "d0000000-0000-0000-0000-000000000001",
  "vehicleId": "b0000000-0000-0000-0000-000000000011",
  "reason": "LOW_BATTERY",
  "previousStatus": null,
  "newStatus": "OPEN",
  "faultCode": null,
  "vehicleStatus": "MAINTENANCE_REQUIRED",
  "changedAt": "2026-11-09T07:30:00Z"
}
```

---

## 2. Events Consumed

| Event | Producer | Payload Requirements | Action / Rule Applied |
|---|---|---|---|
| `VehicleBatteryUpdated` | telemetry-ingestion | `vehicleId`, `batteryPercent`, `measuredAt` | Update `batteryPercent`; apply BR-08 and BR-10; trigger SSE `vehicle.updated`. |
| `VehicleLocationUpdated` | telemetry-ingestion | `vehicleId`, `latitude`, `longitude`, `measuredAt` | Update `latitude` / `longitude`; recompute `zoneId`; trigger SSE `vehicle.updated`. |
| `VehicleFaultReported` | telemetry-ingestion | `vehicleId`, `faultCode`, `description` (optional), `measuredAt` | Create `FAULT` record per BR-09; publish `VehicleMaintenanceChanged`. |
| `RebalancingProposalApproved` | ai-planner | `proposalId`, `approvedBy`, `approvedAt`, `actions[]` (`type`, `vehicleId`, `targetZoneId`) | Apply rebalancing operational changes; update maintenance status. |
| `RebalancingProposalRejected` | ai-planner | `proposalId`, `rejectedBy`, `rejectedAt` | Audit log entry only. No status change. |

---

## 3. Consumer Event Handling Logic

1. **Idempotency Check:** Search `eventId` in `processed_events`. If present, skip execution. Otherwise, process message and persist `eventId` within the same database transaction.
2. **Stale Event Check:** For telemetry events, if `measuredAt < vehicle.last_telemetry_at`, skip execution.
3. **Missing Record Handling:** If `vehicleId` does not match an existing entity, log warning and skip.
4. **Context Propagation:** Preserve `correlationId` in all downstream events emitted during handling.
