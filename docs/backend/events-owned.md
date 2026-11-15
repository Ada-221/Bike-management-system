# Fleet Service: Events Published and Consumed

## 1. Events Published

**Topics:** `fleet.vehicle`, `fleet.rental`, `fleet.maintenance`

### 1.1 `VehicleRegistered` (topic `fleet.vehicle`)

```json
{
  "eventId": "evt_1001",
  "eventType": "VehicleRegistered",
  "producer": "fleet-management-service",
  "vehicleId": "veh_001",
  "publishedAt": "2026-11-09T07:00:00Z",
  "label": "EB-001",
  "type": "E_BIKE",
  "status": "AVAILABLE",
  "batteryPercent": 92,
  "latitude": 48.2102,
  "longitude": 16.3598,
  "zoneId": "zone_001"
}
```

### 1.2 `RentalStarted` (topic `fleet.rental`)

```json
{
  "eventId": "evt_1002",
  "eventType": "RentalStarted",
  "producer": "fleet-management-service",
  "rentalId": "rnt_001",
  "vehicleId": "veh_005",
  "riderId": "rider-demo-1",
  "startedAt": "2026-11-09T08:20:00Z",
  "publishedAt": "2026-11-09T08:20:01Z",
  "startLatitude": 48.1858,
  "startLongitude": 16.3758,
  "startBatteryPercent": 70,
  "zoneId": "zone_002"
}
```

### 1.3 `RentalEnded` (topic `fleet.rental`)

```json
{
  "eventId": "evt_1003",
  "eventType": "RentalEnded",
  "producer": "fleet-management-service",
  "rentalId": "rnt_001",
  "vehicleId": "veh_005",
  "riderId": "rider-demo-1",
  "startedAt": "2026-11-09T08:20:00Z",
  "endedAt": "2026-11-09T08:45:00Z",
  "publishedAt": "2026-11-09T08:45:01Z",
  "durationSeconds": 1500,
  "endLatitude": 48.2099,
  "endLongitude": 16.3605,
  "endBatteryPercent": 58,
  "startZoneId": "zone_002",
  "endZoneId": "zone_001",
  "vehicleStatusAfter": "AVAILABLE"
}
```

### 1.4 `VehicleMaintenanceChanged` (topic `fleet.maintenance`)

```json
{
  "eventId": "evt_1004",
  "eventType": "VehicleMaintenanceChanged",
  "producer": "fleet-management-service",
  "maintenanceId": "mnt_001",
  "vehicleId": "veh_011",
  "reason": "LOW_BATTERY",
  "previousStatus": null,
  "newStatus": "OPEN",
  "faultCode": null,
  "vehicleStatus": "MAINTENANCE_REQUIRED",
  "changedAt": "2026-11-09T07:30:00Z",
  "publishedAt": "2026-11-09T07:30:01Z"
}
```

---

## 2. Events Consumed

```json
{
  "eventId": "evt_2001",
  "eventType": "VehicleBatteryUpdated",
  "producer": "telemetry-ingestion-service",
  "vehicleId": "veh_001",
  "measuredAt": "2026-11-09T08:15:00Z",
  "publishedAt": "2026-11-09T08:15:02Z",
  "batteryPercent": 64
}
```

```json
{
  "eventId": "evt_2002",
  "eventType": "VehicleLocationUpdated",
  "producer": "telemetry-ingestion-service",
  "vehicleId": "veh_001",
  "measuredAt": "2026-11-09T08:15:05Z",
  "publishedAt": "2026-11-09T08:15:07Z",
  "latitude": 48.2105,
  "longitude": 16.3601
}
```

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
