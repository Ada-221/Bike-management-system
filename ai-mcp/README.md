# AI/MCP service (Week 2)

Node.js service implementing the v1 stateless MCP Streamable HTTP profile and a
deterministic mock proposal path. It has no runtime dependencies and needs no
external LLM.

## Run locally

From this directory, use Node.js 22.12 or newer:

```powershell
$env:BACKEND_API_URL = "http://localhost:8080/api/v1/"
$env:MCP_AREA_ZONE_MAP_JSON = '{"area_center":"zone_001","area_south":"zone_002"}'
npm.cmd start
```

The backend adapter reads `GET /api/v1/vehicles?limit=500` and preserves the
configured base path with or without a trailing slash. Backend-issued `veh_...`
IDs are used directly. There is no vehicle ID mapping or dependency on `/zones`.
Configure explicit `area_...` to `zone_...` mappings; malformed identifiers and
multiple areas pointing at one zone fail at startup. Unknown query areas return
`UNKNOWN_AREA`; low-battery records without a mapped area fail clearly.
Fleet status translation is confined to the MCP adapter.

Backend `lastBatteryAt` and `lastLocationAt` become `batteryMeasuredAt` and
`locationMeasuredAt`. Missing measurement timestamps fail validation.
`availableSince` is always null because the backend does not expose status-since
data. Availability keeps AVAILABLE status and minimum-battery filtering.
Snapshots containing 500 records are rejected because the backend cap prevents
proving completeness; totals are only reported for smaller snapshots.

Compose defaults to `http://fleet-management-service:8080/api/v1/` for the
combined environment after team integration. Override `BACKEND_API_URL` with
`http://host.docker.internal:8080/api/v1/` for a standalone host backend. This
branch does not merge the backend or telemetry implementations.

## Check and exercise

```powershell
npm.cmd test
npm.cmd run check
```

Health: `GET http://localhost:8083/health`.

Tool discovery and a sample query use the v1 protocol header and metadata:

```powershell
$headers = @{ "MCP-Protocol-Version" = "2026-07-28"; "Content-Type" = "application/json" }
$meta = @{ "io.modelcontextprotocol/protocolVersion" = "2026-07-28"; "io.modelcontextprotocol/clientCapabilities" = @{} }
Invoke-RestMethod http://localhost:8083/mcp -Method Post -Headers $headers -Body (@{ jsonrpc = "2.0"; id = "list-1"; method = "tools/list"; _meta = $meta } | ConvertTo-Json -Depth 10)
$args = @{ schemaVersion = "1.0"; thresholdPercent = 25; pageSize = 50; maxReadModelAgeSeconds = 300 }
Invoke-RestMethod http://localhost:8083/mcp -Method Post -Headers $headers -Body (@{ jsonrpc = "2.0"; id = "call-1"; method = "tools/call"; params = @{ name = "list_low_battery_vehicles"; arguments = $args }; _meta = $meta } | ConvertTo-Json -Depth 10)
```

Run the deterministic planner and inspect its persisted proposal:

```powershell
Invoke-RestMethod http://localhost:8083/api/v1/planner/runs -Method Post
Invoke-RestMethod http://localhost:8083/api/v1/proposals
```

Proposals persist in `MCP_STATE_DIR` (`./data` by default). Approval endpoints
record an operator decision and always keep `operationalStateChanged: false`.
They do not call fleet APIs or publish operational commands.

## Week 2 limits

- A JSON file stores the latest backend snapshot and proposals for this skeleton;
  backend state remains the source of truth. Kafka projection/rebuild and a
  production database adapter are deferred.
- Demand queries return a structured unavailable result until demand event
  ownership and payload are agreed.
- Pagination cursors are process-local, single-use, expire after 15 minutes, and
  become invalid after a restart, query change, or fleet snapshot change.
- The mock planner recommends up to five available vehicles by paging through low-battery
  tool results, recording a hash of each actual schema-valid result as evidence.
- OFFLINE rules, fault description validation, and approval-driven operations
  remain unimplemented pending their integration decisions.
