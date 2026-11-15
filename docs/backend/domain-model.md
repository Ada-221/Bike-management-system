# Fleet Management Service: Domain Model 
This document defines what the `fleet-management-service` stores, which rules it enforces, and why.

---

## 1. Design Decisions and Reasons

| ID | Decision | Reason | Rejected Alternative |
|---|---|---|---|
| D1 | All entity and event IDs are **prefixed strings** (e.g., `veh_001`, `zone_001`, `rnt_001`, `mnt_001`, `evt_001`). | Provides human-readable, type-safe identifiers across Kafka messages, REST APIs, and MCP tools without cross-service ID translation. **Note:** Existing UUID-only schemas, OpenAPI definitions, and database DDL must be migrated to `VARCHAR`. | Raw UUIDs: harder to debug in logs/prompts and required translation layers; auto-increment integers: leak DB internals. |
| D2 | Vehicle `status` in the backend has only **3 values**: `AVAILABLE`, `RENTED`, `MAINTENANCE_REQUIRED`. | Fewer states mean fewer bugs and a simpler state machine. Status translation (`AVAILABLE` → `AVAILABLE`, `RENTED` → `IN_USE`, `MAINTENANCE_REQUIRED` → `MAINTENANCE`) lives in the MCP adapter, while `OFFLINE` is treated as a derived connectivity state. | Changing backend DB enums or storing `OFFLINE` directly on the vehicle entity. |
| D3 | "Rentable" is **derived**, not stored: `status = AVAILABLE` and `battery >= 20`. The API returns it as a read-only `rentable` field. | Battery changes constantly. Storing a duplicate flag would go stale. | A `LOW_BATTERY` status: mixes two independent facts (is it rented? is the battery low?). |
| D4 | Low battery (under 20%) on a vehicle that is not rented **automatically opens a `LOW_BATTERY` maintenance record** and sets the vehicle to `MAINTENANCE_REQUIRED`. | Gives the AI planner and operators a clear "needs charging" list, and the vehicle can never be rented with a nearly empty battery. | Leaving low-battery vehicles `AVAILABLE`: rental logic gets messy and maintenance endpoints would miss them. |
| D5 | Maintenance is its **own entity** (`maintenance_records`), not just a flag on the vehicle. | A vehicle can have a history of faults. We need reason, timestamps, and notes for audit. `VehicleMaintenanceChanged` needs a stable ID. | A boolean `needs_maintenance` column: loses history and reason. |
| D6 | A **rented vehicle stays `RENTED`** even if it faults or its battery drops. The change takes effect when it is returned. | Prevents stranding a rider mid-trip because of a status change. | Instantly forcing maintenance: breaks the active rental flow. |
| D7 | Locations are plain `latitude` / `longitude` doubles. "Nearby" uses a **bounding box plus Haversine formula** in SQL. No PostGIS. | PostGIS adds an extension, a special Docker image, and operational overhead. For 12 to 100 vehicles, plain math is fast enough. | PostGIS: too heavy for the demonstrator scope. |
| D8 | **Zones are circles** (center + radius). The service assigns `zone_id` from the vehicle's location and stores it. MCP area codes map to zone IDs via an explicit lookup table. | Needed for "availability by area" and rebalancing. A circle is trivial to compute and draw; explicit lookup avoids fragile ID inference from area codes. | Polygons or inferring zone IDs from MCP area code strings. |
| D9 | The rider is just a **`riderId` string**. There is no rider table. | Authentication is out of scope. A string identifies the rider well enough for the demo. | A riders table with accounts: scope creep. |
| D10 | **Start-rental uses an `Idempotency-Key` header** (stored in a unique column). **Return-rental and approve/reject are naturally idempotent** (same request twice gives the same result). | Mobile apps retry on bad networks, so a duplicate "start" must not create two rentals. | Requiring keys everywhere when state checks already make repeats harmless. |
| D11 | **Database constraints are the last line of defense**: partial unique indexes allow only one `ACTIVE` rental per vehicle and per rider; a `version` column supports optimistic locking. | Two riders tapping "rent" at the same moment must not both succeed. | Only checking in application code: fails under concurrency. |
| D12 | Telemetry reaches the service **only through Kafka** (`fleet.vehicle`, keyed by `vehicleId`), published by `telemetry-ingestion-service`. Duplicates are ignored via `processed_events`, and **battery and location freshness are tracked independently**. | Preserves per-vehicle ordering on `fleet.vehicle`, centralizes state updates in the backend (which MCP tools query), and prevents a late location packet from discarding a newer battery reading. | A single `last_telemetry_at` gate across all telemetry types, or a REST telemetry endpoint. |
| D13 | **One error format**, and **HTTP 409 for every business-rule conflict** (vehicle not available, battery too low, rider already renting). The specific reason is in the `code` field. | Mobile and dashboard developers handle one shape and one status class. | Mixing 409 and 422: two ways to handle one kind of problem. |
| D14 | **The backend is the sole execution authority.** `ai-planner` owns proposals and runs (advisory only before and after approval). `fleet-management-service` consumes `RebalancingProposalApproved` events to apply operational changes; approval decisions exclude telemetry. | Keeps AI output advisory and ensures human approval drives fleet state through an auditable backend command/event flow. | Letting `ai-planner` or MCP mutate vehicle state directly. |
| D15 | Live updates use **SSE** (one-way server push) with three event types: `vehicle.updated`, `fleet.status`, `heartbeat`. | SSE is simpler than WebSockets and fits one-way dashboard updates. | WebSockets: two-way channel not needed. |
| D16 | List endpoints return **plain arrays with a `limit` parameter** (default 100). No pagination wrapper. | Simple responses are easier for the mobile and dashboard teams. | Cursor pagination: over-engineering for the fleet size. |
| D17 | Status and type columns are **`VARCHAR` with `CHECK` constraints**, not PostgreSQL `ENUM` types. | Easier to change in migrations and works smoothly with JPA/Hibernate. | PostgreSQL enums: painful to alter. |
| D18 | **No vehicle delete endpoint.** | Rentals and maintenance history reference vehicles, so deleting would break the audit trail. | Hard delete: breaks history. |
| D19 | **No outbox table** in v0.1. Events are published right after the database commit. | Keeps early iterations simple; listed as a known limitation (Section 8). | Transactional outbox pattern: extra moving parts for v0.1. |

---

## 2. Scope

`fleet-management-service` **owns**:
- Vehicles and operational status
- Zones
- Rentals
- Maintenance records

---

## 3. Entities

### 3.1 Zone

| Field | Type | Rules |
|---|---|---|
| `id` | Prefixed string (`zone_...`) | Primary key |
| `name` | text, unique | e.g. "Campus" |
| `centerLatitude` / `centerLongitude` | decimal degrees | -90..90 and -180..180 |
| `radiusMeters` | integer | greater than 0 |
| `createdAt` | UTC timestamp | |

### 3.2 Vehicle

| Field | Type | Rules |
|---|---|---|
| `id` | Prefixed string (`veh_...`) | Primary key (e.g., `veh_001`) |
| `label` | text, unique | Human-readable code painted on the vehicle, e.g. `EB-001` |
| `type` | enum | `E_BIKE` or `E_SCOOTER` |
| `status` | enum | `AVAILABLE`, `RENTED`, `MAINTENANCE_REQUIRED` (backend source of truth) |
| `batteryPercent` | integer | 0..100 |
| `latitude` / `longitude` | decimal degrees | Updated by `VehicleLocationUpdated` events |
| `zoneId` | Prefixed string (`zone_...`) or null | Derived from location (nearest zone containing the point) |
| `lastBatteryAt` | UTC timestamp or null | `measuredAt` of the latest accepted battery reading; used for battery freshness |
| `lastLocationAt` | UTC timestamp or null | `measuredAt` of the latest accepted location reading; used for location freshness |
| `lastTelemetryAt` | UTC timestamp or null | Overall last telemetry activity timestamp; **must not** gate battery or location validity |
| `rentable` | boolean (**derived**, API only) | `status = AVAILABLE` and `batteryPercent >= 20` |
| `version` | integer (database only) | Optimistic locking |
| `createdAt` / `updatedAt` | UTC timestamps | |

### 3.3 Rental

| Field | Type | Rules |
|---|---|---|
| `id` | Prefixed string (`rnt_...`) | Primary key |
| `vehicleId` | Prefixed string (`veh_...`) | Foreign key to vehicle |
| `riderId` | text | Free-text rider identifier (no accounts) |
| `status` | enum | `ACTIVE` or `COMPLETED` |
| `startedAt` / `endedAt` | UTC timestamps | `endedAt` is empty while `ACTIVE` |
| `startLatitude` / `startLongitude` | decimal | Copied from the vehicle at start |
| `endLatitude` / `endLongitude` | decimal or null | Supplied by the rider's app on return |
| `startBatteryPercent` / `endBatteryPercent` | integer | Snapshot values for history and analytics |
| `idempotencyKey` | text, unique | From the `Idempotency-Key` header |

### 3.4 MaintenanceRecord

| Field | Type | Rules |
|---|---|---|
| `id` | Prefixed string (`mnt_...`) | Primary key |
| `vehicleId` | Prefixed string (`veh_...`) | Foreign key to vehicle |
| `reason` | enum | `LOW_BATTERY`, `FAULT`, `MANUAL` |
| `status` | enum | `OPEN`, `IN_PROGRESS`, `RESOLVED` |
| `faultCode` | text or null | Mandatory for `FAULT`, e.g. `BRAKE_SENSOR` |
| `description` | text or null | Free text |
| `openedAt` / `resolvedAt` | UTC timestamps | `resolvedAt` set only when `RESOLVED` |

At most **one unresolved record per vehicle per reason**. This makes duplicate telemetry events harmless.

### 3.5 ProcessedEvent (internal)

| Field | Type | Rules |
|---|---|---|
| `eventId` | Prefixed string (`evt_...`) | Primary key; stores IDs of handled Kafka events for deduplication |
| `processedAt` | UTC timestamp | Processing timestamp |

---

## 4. State Machines & MCP Status Mapping

### 4.1 Vehicle Status (Backend)

```mermaid
stateDiagram-v2
    [*] --> AVAILABLE: VehicleRegistered
    AVAILABLE --> RENTED: start rental
    RENTED --> AVAILABLE: return, healthy and battery >= 20
    RENTED --> MAINTENANCE_REQUIRED: return, open record or battery < 20
    AVAILABLE --> MAINTENANCE_REQUIRED: fault, low battery or manual report
    MAINTENANCE_REQUIRED --> AVAILABLE: all records RESOLVED
```

### 4.2 Rental status

```mermaid
stateDiagram-v2
    [*] --> ACTIVE: start rental
    ACTIVE --> COMPLETED: return vehicle
    COMPLETED --> [*]
```

### 4.3 Maintenance status

```mermaid
stateDiagram-v2
    [*] --> OPEN: fault, low battery or manual report
    OPEN --> IN_PROGRESS: operator starts work
    IN_PROGRESS --> RESOLVED: work finished
    OPEN --> RESOLVED: resolved directly
    RESOLVED --> [*]
```

---

## 5. Business rules

| ID | Rule |
|---|---|
| BR-01 | A rental can start only if the vehicle is `AVAILABLE` **and** `batteryPercent >= 20`. Otherwise `409 VEHICLE_NOT_AVAILABLE` or `409 BATTERY_TOO_LOW`. |
| BR-02 | A rider can have only one `ACTIVE` rental. Otherwise `409 RIDER_HAS_ACTIVE_RENTAL`. |
| BR-03 | Starting a rental sets the vehicle to `RENTED`, copies its location and battery into the rental, and publishes `RentalStarted`. |
| BR-04 | Repeating **start** with the same `Idempotency-Key` and the same vehicle and rider returns the original rental (`200`). Reusing a key with a different vehicle or rider gives `409 IDEMPOTENCY_KEY_REUSED`. |
| BR-05 | Returning a rental sets it `COMPLETED`, stores the end location and battery, and publishes `RentalEnded`. |
| BR-06 | Returning an already `COMPLETED` rental returns it unchanged (`200`) and publishes **no** second event. |
| BR-07 | After a return, the vehicle becomes `MAINTENANCE_REQUIRED` if it has an unresolved maintenance record or its battery is under 20 (a `LOW_BATTERY` record is opened). Otherwise it becomes `AVAILABLE`. |
| BR-08 | A telemetry battery reading under 20 on a vehicle that is **not** rented opens a `LOW_BATTERY` record (once) and sets `MAINTENANCE_REQUIRED`. On a rented vehicle, the record is created but the status change waits for the return (decision D6). |
| BR-09 | A `VehicleFaultReported` event opens a `FAULT` record (once per vehicle while unresolved). |
| BR-10 | When the last unresolved record of a `MAINTENANCE_REQUIRED` vehicle is resolved, the vehicle becomes `AVAILABLE`. A `LOW_BATTERY` record is also resolved automatically when telemetry reports battery of 50 or more. |
| BR-11 | Every maintenance change publishes `VehicleMaintenanceChanged`. |
| BR-12 | Telemetry events with a `measuredAt` older than the vehicle's `lastTelemetryAt` are ignored. Events whose eventId is already in processed_events are ignored. Battery updates are ignored if measuredAt is older than lastBatteryAt; location updates are ignored if measuredAt is older than lastLocationAt. lastTelemetryAt is updated as an overall activity timestamp and never gates measurement validity. |
| BR-13 | fleet-management-service is the sole execution authority. ai-planner output remains advisory before and after approval, and approval decisions exclude raw telemetry. Only an operator-approved proposal (RebalancingProposalApproved) triggers backend state transitions, which are audited.|

---

## 6. Configuration constants

| Name | Value | Purpose |
|---|---|---|
| `MIN_RENTABLE_BATTERY_PERCENT` | 20 | Below this, a vehicle cannot be rented (BR-01, BR-07, BR-08) |
| `LOW_BATTERY_RESOLVE_PERCENT` | 50 | Auto-resolve a `LOW_BATTERY` record at or above this (BR-10) |
| `NEARBY_DEFAULT_RADIUS_METERS` | 1000 | Default search radius |
| `NEARBY_MAX_RADIUS_METERS` | 5000 | Upper limit for search radius |
| `LIST_DEFAULT_LIMIT` / `LIST_MAX_LIMIT` | 100 / 500 | List endpoint size |
| `SSE_HEARTBEAT_SECONDS` | 15 | Keeps connections alive |

Put these in `application.yml` so they can be changed without code edits.

---

## 7. Error codes

All errors use this JSON shape: `{ code, message, timestamp, correlationId, details }`.

| HTTP | `code` | When |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Malformed or out-of-range input |
| 400 | `MISSING_IDEMPOTENCY_KEY` | `POST /rentals` without the header |
| 404 | `NOT_FOUND` | Unknown vehicle, rental, maintenance record, proposal or run |
| 409 | `VEHICLE_NOT_AVAILABLE` | Vehicle is rented or in maintenance |
| 409 | `BATTERY_TOO_LOW` | Battery below the rentable minimum |
| 409 | `RIDER_HAS_ACTIVE_RENTAL` | Rider already has an active rental |
| 409 | `IDEMPOTENCY_KEY_REUSED` | Same key, different request |
| 409 | `DUPLICATE_VEHICLE_LABEL` | Label already exists |
| 409 | `INVALID_STATUS_TRANSITION` | e.g. reopening a resolved maintenance record |
| 409 | `PROPOSAL_ALREADY_DECIDED` | Approving a rejected proposal (or the reverse) |
| 500 | `INTERNAL_ERROR` | Unexpected failure |

---

## 8. Concurrency and idempotency summary

| Operation | Protection |
|---|---|
| Start rental | `Idempotency-Key` unique column and a partial unique index (one `ACTIVE` rental per vehicle and per rider). If two requests race, the loser gets `409`. |
| Return rental | State check (`COMPLETED` returns unchanged) |
| Vehicle update from telemetry | Optimistic `version` column plus stale-event check |
| Kafka consumers | `processed_events` table |
| Approve / reject proposal | State check in `ai-planner` (same pattern) |

---

## 9. Known limitations (accepted for v0.1)

- Schema & API migration required: Existing UUID-only DDL, OpenAPI specs, and JSON validators must be updated to accept prefixed string IDs (veh_001, zone_001, rnt_001, mnt_001, evt_001).
- No authentication or authorization (out of scope; Week 5 adds only basic validation boundaries).
- No transactional outbox: a crash between database commit and Kafka publish could lose an event (D19).
- No pricing, payments or rider accounts.
- Zones are circles and may overlap; the nearest center wins.
- No vehicle retirement or delete (D18).

---

## 10. Open questions for the team

| # | Question | For |
|---|---|---|
| Q1 | Agree on the inactivity timeout rule used by the MCP adapter to derive OFFLINE status.| Persons 2, 3, and 5 |
| Q2 | Agree on equal-timestamp (measuredAt == lastBatteryAt / lastLocationAt) tie-breaking and replay behavior. | Persons 1 and 2 |
| Q3 |Confirm VehicleFaultReported description requiredness and Kafka retention, retry, and dead-letter topic policies.| Persons 1 and 2 |
| Q4 | Confirm DemandRecorded ownership (simulator publication vs. MCP derivation from RentalStarted). | Persons 2 and 5 |
| Q5 | Confirm proposal action payload shapes, audit/decision fields, and decision retry behavior (frontend/week-1.md). | Persons 3 and 5 | 
| Q6 | Confirm whether GET /vehicles/nearby needs extra filters (e.g., type) and whether the dashboard needs extra SSE event types. | Persons 3 and 4 |
| Q7 | Port numbers: fleet 8080, telemetry 8081, ai-planner 8082, mcp 8083. OK for everyone? | All |
