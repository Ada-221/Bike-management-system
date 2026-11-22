# AI/MCP contracts v1

This directory freezes Person 5's Week 1 public contracts. It specifies interfaces
only; it does not include an MCP server, read model, planner, persistence, or LLM
integration.

## Protocol profile

- MCP protocol version: `2026-07-28`.
- Transport: stateless Streamable HTTP, one `POST` endpoint at `/mcp`.
- Each request carries the protocol version and client capabilities in `_meta` and
  the matching `MCP-Protocol-Version` header. No initialize handshake or MCP
  session ID is used.
- Ordinary results use `resultType: "complete"`.
- A successful tool call returns the contract value as `structuredContent` and a
  byte-equivalent compact JSON serialization in a text content block. The text is
  a compatibility mirror; consumers must prefer `structuredContent`.
- `tools/list` returns the three tools in the order in `mcp-tools.json` and uses
  the read-only, non-destructive, idempotent annotations declared there.

This profile follows the MCP 2026-07-28 [key changes](https://modelcontextprotocol.io/specification/2026-07-28/changelog),
[tool result model](https://modelcontextprotocol.io/specification/2026-07-28/server/tools),
and [Streamable HTTP rules](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http).

## Contract conventions

- Schemas use JSON Schema Draft 2020-12, require `schemaVersion: "1.0"`,
  and reject undocumented object properties.
- Cross-service times are ISO-8601 UTC strings ending in `Z`. Percentages are
  integers in the inclusive range 0-100. Coordinates are WGS84 degrees.
- Stable IDs are opaque strings with `veh_` or `area_` prefixes. Clients must not
  infer meaning from an ID.
- Callers set `maxReadModelAgeSeconds`. Successful results report
  `readModelAsOf` and freshness. If the requested freshness cannot be met, the
  server returns `STALE_READ_MODEL`; it must not silently present stale data as
  fresh.
- Pagination cursors are opaque, stable only for the associated query and read
  model snapshot, and expire. A page contains at most 100 records.
- Result arrays use the deterministic sort specified in their schema description.
  Cursors continue that order without gaps or duplicates.
- Structured failures use the output schema's `error` variant. Protocol/transport
  failures remain JSON-RPC errors.

## Service-level validation

JSON Schema cannot express every temporal, aggregate, or grounding relationship.
The implementation must additionally enforce: `from < to`; demand intervals no
longer than seven days; completed plus unserved trips consistent with recorded
demand; returned batteries meeting the input filter; `availableCount` matching the
full unpaginated query; low-battery results meeting the threshold; and different
source/target areas for redistribution.

Proposal IDs, vehicle IDs, area IDs, and evidence must be grounded in the exact
MCP results identified by `snapshot.sources`. A proposal is an advisory record.
Its `effectPolicy` is always `HUMAN_APPROVAL_REQUIRED`, and
`operationalStateChanged` is always false. Approval records a human decision but
does not itself execute an action; a separately authorized fleet command is a
future contract outside this milestone.

## Layout

- `mcp-tools.json`: transport profile and tool discovery metadata.
- `schemas/`: standalone input/output and proposal schemas.
- `examples/`: valid arguments, results, protocol envelope, and proposal.
- `fixtures/invalid/`: intentionally invalid data and a reason manifest.

## Week 2 implementation reconciliation

The approved Week 2 reconciliation uses backend-issued `veh_...` IDs directly,
explicit validated `area_...` to `zone_...` configuration, and backend
`lastBatteryAt`/`lastLocationAt` measurement timestamps. `availableSince`
now also accepts null, and the Week 2 adapter always returns null. Consumers
must accept null before upgrading; existing timestamp examples remain valid.
The producer emits separate battery/location events with unique `evt_...` IDs.

Pagination uses opaque schema-valid tokens bound to the tool, query, and fleet
snapshot. Snapshots at the backend 500-record cap fail as unavailable rather
than reporting potentially incomplete totals. Planner evidence hashes the actual
low-battery tool result. Approval remains advisory, including after restart.
That paragraph describes historical Week 2 behavior. Week 3 keeps the v1 tool
schemas and semantics while reading only a JSON Kafka projection, with no
500-record cap. Backend producer integration is gated on owner agreement; see
[service/replay](../../../ai-mcp/README.md) and
[verification](../../../docs/ai-mcp/week-3-verification.md). Demand and OFFLINE
rules remain deferred.
