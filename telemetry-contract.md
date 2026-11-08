# Telemetry Contract — Draft

Status: Draft for team review.

## Topic
Proposed topic: vehicle.telemetry

## Event
VehicleTelemetryReceived

## Common fields
- eventId: Unique message ID. Preserved when the same message is retried.
- eventType: Type of the event; fixed by its specific schema.
- schemaVersion: 1.0.
- timestamp: Measurement time in UTC, ending in Z.
- vehicleId: ID of the vehicle being measured.
- correlationId: Shared tracking ID for related processing steps.
- producer: Name of the service publishing the event.
- payload: Event-specific data, as defined by the event schema.

## Telemetry fields
- batteryPercent: Integer from 0 to 100.
- latitude: Number from -90 to 90.
- longitude: Number from -180 to 180.

## Review needed
- Confirm topic and field names with Backend and AI/MCP owners.
- Confirm timestamp semantics and handling of out-of-order measurements.
- Review and finalize the proposed fault format and fault codes.

## Proposed MCP read-model mapping

- Current draft vehicleId uses the AI/MCP prefixed ID pattern. Backend uses UUIDs; direct integration requires a shared ID decision or an explicit mapping.
- payload.batteryPercent maps to batteryPercent.
- payload.latitude maps to location.latitude.
- payload.longitude maps to location.longitude.
- timestamp represents measurement time and maps to telemetryAt.

## Decisions requiring Backend and AI/MCP review

- Define how vehicle coordinates are assigned to an areaId.
- Define which fleet events supply operationalStatus.
- Define which fleet events establish availableSince.
- Do not use telemetry measurement time as availableSince.
- Confirm the mapping before implementation.

## VehicleFaultReported — Draft

Purpose: Report an observed vehicle fault.
Proposed topic: vehicle.telemetry.

Uses the common event envelope.
For this event, timestamp means the time the fault was observed.

Required payload fields:
- faultCode: String of 1–50 characters identifying the fault type. The limit matches the reviewed Backend database column.
- description: Non-empty string describing the fault.

BRAKE_FAILURE is a synthetic example, not a finalized fault-code list.

A fault report does not itself define a vehicle status change.
Backend rules determine its effect on availability and maintenance.

Open decisions:
- Agree on supported fault codes.
- Define how fault resolution is reported.
- Define how repeated reports of the same fault are handled.

## Integration status

These examples validate against the included draft schemas. They are not yet a finalized Backend-compatible wire contract. See [integration-review.draft.md](integration-review.draft.md) for the concrete differences and owners.
