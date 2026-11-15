# Event and telemetry integration register

This register separates settled integration rules from the questions that still
need owner decisions. Settled rules supersede conflicting Week 1 draft wording.
The three Kafka topics remain `fleet.vehicle`, `fleet.rental`, and
`fleet.maintenance`; do not introduce `fleet.events`.

## Settled decisions

| Area | Decision | Implementation notes |
|---|---|---|
| Kafka topics | Keep `fleet.vehicle`, `fleet.rental`, and `fleet.maintenance`. | Route each event to its existing domain topic. No `fleet.events` topic. |
| Telemetry events | Publish separate `VehicleBatteryUpdated` and `VehicleLocationUpdated` events to `fleet.vehicle`, keyed by the Kafka record key `vehicleId`. | Each event represents one measurement. Do not also publish a combined update for the same reading. |
| Event envelope | Both event types carry top-level `eventId`, `eventType`, `schemaVersion`, `vehicleId`, `measuredAt`, `publishedAt`, `correlationId`, `producer`, and `payload`. | `measuredAt` is the sensor measurement time; `publishedAt` is the distinct event publication time. Both timestamps are UTC. |
| Telemetry identifiers | Both event types use unique `evt_` IDs, matching the Java producer. Vehicle IDs use `veh_` identifiers. | Preserve IDs through retries; paired battery/location events have distinct IDs. |
| Telemetry producer | Use `telemetry-ingestion-service` as the producer ID. | Applies to normalized battery and location events. |
| Entity ID boundary | Backend/API vehicle identifiers and cross-service vehicle IDs use a stable `veh_…` identifier; MCP area IDs use `area_…`. Backend zones use `zone_...` identifiers behind the area adapter. | The implemented backend DTO `id` is already `veh_...`; MCP uses it directly without a UUID registry. Historical API/SQL drafts do not override the implemented backend. |
| Backend statuses | Keep backend operational values `AVAILABLE`, `RENTED`, and `MAINTENANCE_REQUIRED`. | The MCP adapter maps `RENTED` → `IN_USE` and `MAINTENANCE_REQUIRED` → `MAINTENANCE`; `AVAILABLE` stays `AVAILABLE`. `OFFLINE` has no agreed producer rule yet and must not be inferred from a missing record. |
| Area lookup | MCP area codes map through explicit validated configuration to backend `zone_...` IDs. The backend has no `/zones` endpoint; mappings are operator-provisioned. | Unknown or ambiguous mappings fail clearly. Reject malformed IDs and multiple areas pointing to one zone at startup. Do not guess by display name. |
| Telemetry freshness | Battery and location are independent measurements with independent freshness. | A newer battery measurement does not suppress an older-but-current location measurement, or vice versa. Each projection field tracks its own `measuredAt`; exact equal-time handling remains open. |
| Authoritative state | The fleet backend is authoritative for operational vehicle and zone state. MCP tools normally query backend-stored state through its read API. | The MCP service may persist its own read model, but must not treat an advisory proposal or cached projection as authoritative operational state. |
| MCP status and freshness | MCP translates backend status values at the adapter boundary and carries battery and location measurement timestamps independently (`batteryMeasuredAt`, `locationMeasuredAt`). | Do not change backend enum values to match MCP vocabulary. Read `lastBatteryAt` and `lastLocationAt` from the implemented backend DTO; leave backend code unchanged. |
| Proposal safety | Planner output is advisory before and after approval. Approval records a human decision; it does not itself execute an action or mutate fleet state. | Any future operational command requires a separately defined and authorized interface. Telemetry is not part of an approval decision. |

### Battery event example

```json
{
  "eventId": "evt_00000000-0000-4000-8000-000000000001",
  "eventType": "VehicleBatteryUpdated",
  "schemaVersion": "1.0",
  "vehicleId": "veh_0001",
  "measuredAt": "2026-11-10T09:15:00Z",
  "publishedAt": "2026-11-10T09:15:02Z",
  "correlationId": "corr_0001",
  "producer": "telemetry-ingestion-service",
  "payload": { "batteryPercent": 72 }
}
```

### Location event example

```json
{
  "eventId": "evt_00000000-0000-4000-8000-000000000002",
  "eventType": "VehicleLocationUpdated",
  "schemaVersion": "1.0",
  "vehicleId": "veh_0001",
  "measuredAt": "2026-11-10T09:15:00Z",
  "publishedAt": "2026-11-10T09:15:03Z",
  "correlationId": "corr_0001",
  "producer": "telemetry-ingestion-service",
  "payload": { "latitude": 45.7489, "longitude": 21.2087 }
}
```

## Open questions

| Question | Owners | Why it remains open |
|---|---|---|
| What timeout and evidence should cause a vehicle to be represented as `OFFLINE` in MCP? | Backend, telemetry, MCP | No timeout or last-seen policy has been agreed. |
| Is a fault description required, optional, or conditionally required, and what length/blank-value rules apply? | Backend, telemetry | Draft schema and database/API drafts disagree. |
| What are Kafka retention, retry, and dead-letter policies? | Telemetry, backend, MCP | No operational policy is settled. |
| Who owns and publishes demand observations, and what is their event payload/routing? | Telemetry, MCP, backend | `DemandRecorded` producer and whether demand is derived from rentals are unsettled. |
| Which service produces each non-telemetry event, and which services consume it? | Backend, telemetry, MCP | Confirm the complete producer/consumer matrix before wiring consumers. |
| What are the exact schemas for each finalized event, including equal-`measuredAt` ordering behavior? | Backend, telemetry, MCP | This register settles the envelope and two telemetry event types, but not every payload or tie-break rule. |
| How will area-to-zone configuration be provisioned across environments? | Backend, MCP | Explicit `area_...` to `zone_...` configuration is required; automatic zone discovery is deferred. Vehicle IDs require no mapping. |
| Which backend field or event establishes `availableSince` for the MCP availability result? | Backend, MCP | The implemented backend has no status-since timestamp. The v1 MCP field now permits null and Week 2 always returns null; no substitute timestamp is inferred. |
| Are proposal lifecycle events retained, and which component owns their eventual publication? | Backend, MCP, AI/planner | Proposal approval does not execute fleet changes; future event ownership is not finalized. |

## Person 5 — Week 2 MCP, AI, and QA plan

| Work item | Week 2 scope |
|---|---|
| MCP server | Implement the v1 stateless Streamable HTTP skeleton, health endpoint, tool discovery/call structure, and contract/schema handling. |
| Read path | Add read-model persistence foundations and an initial adapter path that normally queries backend-stored state. Keep backend status conversion and explicit area-code-to-zone-ID resolution in the MCP adapter; never infer zone IDs. |
| Planner and proposals | Persist proposal records and implement a deterministic mock planner that emits schema-valid advisory proposals. Approval records the decision only and cannot execute actions or change fleet state. |
| QA | Add focused checks for MCP protocol and schema handling, backend status and area mappings, proposal validation, and approval safety. Defer behavior that depends on the open questions above. |
| Build and developer workflow | Add the Person 5 service and its checks to the Week 2 build/CI workflow. Document local startup plus one MCP query and mock proposal run. CI must not require an external LLM. |

## Deferred until decisions close

- Kafka consumer behavior for unsettled producer/consumer routes, retention, retry,
  dead-letter handling, demand ownership, and OFFLINE semantics.
- Fault description validation until requiredness is agreed.
- Any approval-triggered operational action or telemetry-based approval rule.
