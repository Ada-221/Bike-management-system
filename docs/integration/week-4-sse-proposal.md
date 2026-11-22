# Proposal: what should the Week 4 SSE stream listen to? (T19, decision needed)

Facts: the fleet service emits `VehicleStateUpdated` only when a location update changes the
zone. Applied battery and location readings that change nothing else emit no fleet event.
The raw telemetry events already flow on `fleet.vehicle`. No code changes until the owners agree.

## Option A: SSE fed from the telemetry topic plus fleet events

| Aspect | Effect |
|---|---|
| Event volume | No new events. The SSE process reads `fleet.vehicle`, `fleet.rental` and `fleet.maintenance`. |
| Ordering | Telemetry has no `stateRevision`; fleet events do. The SSE layer has to merge two orderings and cannot tell whether fleet discarded a stale or unregistered-vehicle reading, so the stream can show readings the database rejected. |
| State shown | SSE would need to re-derive status, zone and maintenance rules, or show raw readings only. Risk of drifting from the real rules. |
| MCP impact | None. |

## Option B: fleet emits a state event for every applied reading

| Aspect | Effect |
|---|---|
| Event volume | Roughly doubles `fleet.vehicle` traffic (one fleet event per applied reading) plus outbox rows. |
| Ordering | One monotonic `stateRevision` per vehicle across topics, and only applied readings appear. Simple for consumers. |
| State shown | Each event carries the resulting status and zone, so SSE stays a thin relay. |
| MCP impact | The MCP projection receives many more `VehicleStateUpdated` events and must handle them (idempotently, ordered by revision). The v1 contract would need an agreed addition, e.g. battery and location in the payload. |

## Middle option
Emit a state event only when something consumers show changes materially (status, zone, battery
bucket or a minimum interval). Lower volume, more rules to specify.

## Questions for the owners
1. Must the dashboard show every reading, or only meaningful state changes?
2. May `VehicleStateUpdated` payload grow (battery, location)? Is that backward compatible for MCP?
3. Who owns the SSE endpoint (`/fleet/stream`, currently only in `api/fleet-api.yaml`)?
