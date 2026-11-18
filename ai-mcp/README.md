# AI/MCP service — Week 3

Node 22.12+ service with KafkaJS, a JSON event projection, frozen v1 MCP tools
and a deterministic mock planner. Tools query only the projection. There is no
runtime dependency on the backend API or fleet database and no REST fallback.

**Shared activation is gated.** The real fleet outbox uses the
[fleet-week3-v1 adapter](../contracts/fleet/v1/README.md). Set
`MCP_BACKEND_EVENT_CONTRACT=fleet-week3-v1` in an isolated demo or after the
affected owners review the contract. The Compose smoke opts in automatically.

## Startup

From the repository root, copy `.env.example` to `.env` and run:

```powershell
docker compose up --build -d --wait
```

Compose initializes `fleet.vehicle`, `fleet.rental`, and `fleet.maintenance`
before MCP starts. MCP depends on Kafka readiness. Local demo topics use
`cleanup.policy=delete` and `retention.ms=-1` to retain the complete replay
history. Existing topics are not reconfigured by `--if-not-exists`; inspect their
retention before rebuild. Keep the backend running for its CRUD APIs, but MCP
queries do not require it.

For host development, from `ai-mcp`:

```powershell
npm.cmd ci
$env:MCP_KAFKA_BROKERS = 'localhost:29092'
$env:MCP_KAFKA_GROUP_ID = 'cebp-mcp-local-v1'
$env:MCP_BACKEND_EVENT_CONTRACT = 'fleet-week3-v1'
$env:MCP_STATE_DIR = './data'
$env:MCP_AREA_ZONE_MAP_JSON = '{"area_center":"zone_001","area_south":"zone_002"}'
npm.cmd start
```

Use a dedicated group and exactly one MCP writer per state directory. The
service and offline rebuild acquire `writer.lock`; concurrent writers fail
clearly. After an unclean kill, stop/verify all writers before manually removing
that lock. Do not share a consumer group between independent projections:
each projection needs all partitions, and partial assignments are rejected.

## Kafka read behavior

Each accepted event atomically stores the projection, ordering metadata, event
fingerprint and next topic/partition offset in `state.json`. The temporary file
is flushed before atomic replacement. Kafka commits happen afterward. On
restart every assignment seeks the durable next offsets, even if group commits
disagree. Failed storage never advances the in-memory or durable checkpoint.
Broker failures retry; storage/integrity failures require repair and restart.
Back up the JSON file with its proposal records. This local-file demo does not
provide distributed storage or a guarantee against every host power-loss mode.

Battery/location measurements order independently by measurement time,
publication time and event ID (including Java nanosecond timestamps). Backend
status and zone updates use per-vehicle state revisions, not topic arrival
order. GPS only changes coordinates; it cannot assign a zone. Updates before
registration persist but block complete fleet results. Duplicate identical IDs
are harmless; conflicting IDs or contradictory same-revision fields halt.

Invalid supported records fail readiness with topic/partition/offset details.
Unrelated named event types are skipped and checkpointed. There is no automatic
dead-letter skipping or inferred OFFLINE behavior. Legacy REST cache is never
used as event bootstrap; initializing/rebuilding preserves proposals.

`/health` is process liveness. `/ready` checks consumer initialization,
all-partition catch-up and integrity. Successful broker offset checks refresh
`readModelAsOf` even on idle topics. Query calls never renew it. Before first
catch-up tools return `READ_MODEL_UNAVAILABLE`. After disconnection the last
complete snapshot remains usable within the caller's `maxReadModelAgeSeconds`,
then tools return `STALE_READ_MODEL`. Readiness stays false until recovery.

Explicit `area_...` → `zone_...` mappings are required. Malformed/ambiguous maps
fail startup. Unknown query areas return `UNKNOWN_AREA`. A vehicle outside
all fleet zones has a null `areaId` in low-battery results. A non-null zone
without a configured mapping fails clearly. Status translation remains `RENTED` → `IN_USE` and
`MAINTENANCE_REQUIRED` → `MAINTENANCE`. Independent measurement timestamps are
preserved; `availableSince` remains null. No 500-vehicle backend cap applies.

Cursors are process-local, single-use, expire after 15 minutes and invalidate
when query-relevant content changes (including change-and-revert), the query
changes, or the process restarts. Idle broker checks and duplicate redelivery
do not invalidate them.

## Requests and Kafka exercise

The following requests read the Kafka projection. A fresh empty broker has an
empty fleet until complete registration history is available. Run the isolated
smoke below for examples driven by the fleet and telemetry services.

```powershell
$headers = @{ 'MCP-Protocol-Version' = '2026-07-28'; 'Content-Type' = 'application/json' }
$meta = @{ 'io.modelcontextprotocol/protocolVersion' = '2026-07-28'; 'io.modelcontextprotocol/clientCapabilities' = @{} }
$query = @{ schemaVersion = '1.0'; thresholdPercent = 25; pageSize = 50; maxReadModelAgeSeconds = 300 }
Invoke-RestMethod http://localhost:8083/mcp -Method Post -Headers $headers -Body (@{ jsonrpc = '2.0'; id = 'low-1'; method = 'tools/call'; params = @{ name = 'list_low_battery_vehicles'; arguments = $query }; _meta = $meta } | ConvertTo-Json -Depth 10)
$query = @{ schemaVersion = '1.0'; areaId = 'area_center'; minimumBatteryPercent = 20; pageSize = 50; maxReadModelAgeSeconds = 300 }
Invoke-RestMethod http://localhost:8083/mcp -Method Post -Headers $headers -Body (@{ jsonrpc = '2.0'; id = 'available-1'; method = 'tools/call'; params = @{ name = 'get_vehicle_availability_by_area'; arguments = $query }; _meta = $meta } | ConvertTo-Json -Depth 10)
Invoke-RestMethod http://localhost:8083/api/v1/planner/runs -Method Post
Invoke-RestMethod http://localhost:8083/api/v1/proposals
```

The mock planner reads MCP tools only. Approval/rejection records persist;
`operationalStateChanged` always remains false. No fleet commands are published.
Demand stays unavailable until its ownership/schema is agreed.

## Offline rebuild

Stop the writer and pause producers for a stable comparison. Back up `state.json`.
The command first acquires the writer lock and verifies all topic log starts
are zero, then clears only projection/cache/checkpoints/deduplication data and
replays retained events. Proposal decisions are preserved. Group commits do not
control replay. Run with the same owner-approved contract and area config.

```powershell
docker compose stop ai-mcp-service
docker compose run --rm --no-deps ai-mcp-service npm run rebuild
docker compose up -d --no-deps --wait ai-mcp-service
```

Host alternative, after stopping `npm start`: `npm.cmd run rebuild` with the same
environment. Rebuild exits nonzero on missing/truncated history, contract or
integrity errors, missing registrations, broker failure or timeout. Proposals
remain intact on failure, although projection replay may be incomplete. Restore
the complete broker history and rerun; do not turn cached backend state into a
bootstrap. Never compact, delete or recreate demo topics before replay. A zero
log start is a necessary check, not proof that an operator has preserved every
historical event; complete producer coverage and retained history remain owner
acceptance obligations.

## Verification

```powershell
# ai-mcp directory
npm.cmd test
npm.cmd run check
# repository root: fresh project, no host port collisions, own-volume cleanup
node scripts/compose-smoke.mjs
```

The smoke writes evidence under `build/week3-smoke-*`. It uses real fleet REST
and Java telemetry, a recoverable mid-trip fault, broker outage and outbox
recovery, backend shutdown, and offline replay. It verifies that advisory
approval persists.
The old `scripts/backend-smoke.mjs` is historical Week 2 coverage, not the active
acceptance path. See [plan](../docs/ai-mcp/week-3-implementation-plan.md) and
[Week 3 evidence](../docs/ai-mcp/week-3-verification.md).
