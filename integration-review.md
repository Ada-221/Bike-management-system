# Event / Telemetry integration review — Draft

This file is the current register for unresolved cross-component integration
decisions. Items remain pending until affected owners or the project lead/team
record an outcome and rationale here. The project-wide map and decision process
are in [architecture and agent conventions](docs/architecture-and-agent-conventions.md).

Based on the teammate drafts reviewed on 2026-11-08. This is a decision list, not team approval. Existing draft examples remain usable for local schema checks only.

| Item | Current telemetry draft | Reviewed teammate contract | Required decision |
|---|---|---|---|
| Vehicle ID | veh_001 and prefixed pattern | Backend UUID; AI/MCP prefixed IDs | Backend + AI/MCP + telemetry: choose shared IDs or an explicit mapping; do not invent conversion rules. |
| Event ID | evt_001; non-empty string | Backend processed_events.event_id is UUID | Backend + telemetry: choose UUID event IDs or change storage; update examples and replay IDs together. |
| Telemetry events | Combined VehicleTelemetryReceived | Backend consumes separate battery/location updates | Backend + telemetry + MCP: decide which events are emitted and consumed; avoid applying one measurement twice. |
| Envelope and measurement time | vehicleId and timestamp at top level | Backend lists vehicleId and measuredAt in payload requirements | Confirm complete wire examples. Clarify measurement time versus publication time before renaming fields. |
| Fault description | Required non-empty string | Backend says optional | Agree requiredness and update schema plus examples together. |
| Producer | telemetry-ingestion-service | Backend says telemetry-ingestion | Agree one producer identifier. |
| Fleet topics | fleet.events proposal | fleet.vehicle, fleet.rental, fleet.maintenance | Backend + telemetry + MCP: choose routing. |
| Read-model status / area | Mapping pending | Backend RENTED / MAINTENANCE_REQUIRED and zone UUID; MCP IN_USE / MAINTENANCE / OFFLINE and area prefixes | Backend + MCP: define explicit mappings and OFFLINE semantics. |
| Freshness | Reject older measurements | Backend uses one last_telemetry_at for separate fields | Backend + telemetry: use an agreed per-field or combined-update strategy; specify equal-time handling. |
| Approval effects | No operational implementation here | Backend applies actions on approval; AI proposal contract says advisory and no direct execution even after approval | Backend + AI/planner: resolve execution boundary; telemetry must not decide it. |

## Applied in this review
- Consistent JSON formatting and final newlines.
- faultCode maximum length 50 to match the reviewed database column.
- Removed the implication that Backend and MCP IDs already agree.
- Fault scenario now references the existing example and schema.
- Added boundary and integration test specifications.

## Still pending before integration
- Final battery/location schemas after event shape agreement.
- Demand event ownership and payload.
- Kafka retention, retry and dead-letter choices.
- Producer/consumer contract agreement and actual integration tests.

Frontend uses the Backend model subset. Mobile explicitly uses provisional fake models. Their screen models do not add new telemetry event requirements.
