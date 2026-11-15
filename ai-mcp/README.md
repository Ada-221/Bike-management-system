# AI/MCP service (Week 2)

Node.js service implementing the v1 stateless MCP Streamable HTTP profile and a
deterministic mock proposal path. It has no runtime dependencies and needs no
external LLM.

## Run locally

From this directory, use Node.js 22.12 or newer:

```powershell
$env:BACKEND_API_URL = "http://localhost:8080/api/v1/"
$env:MCP_AREA_ZONE_MAP_JSON = '{"area_campus":"<backend-zone-uuid>"}'
$env:MCP_VEHICLE_ID_MAP_JSON = '{"<backend-vehicle-uuid>":"veh_0001"}'
npm.cmd start
```

The backend adapter reads `GET /vehicles?limit=500` and `GET /zones`. Configure
stable ID maps with backend-issued identifiers. Area mappings are verified against
the backend zone list on each query; unknown mappings return `UNKNOWN_AREA`.
Vehicle public IDs must be explicit `veh_…` identifiers. The service does not
derive IDs or zone UUIDs. Fleet status translation is confined to the MCP adapter.
The MCP v1 contract reports `batteryMeasuredAt` and `locationMeasuredAt`
independently. The backend must expose both fields and a real `availableSince`
value before the availability tool can return a successful response; the
adapter does not substitute vehicle creation or telemetry time.

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
- Pagination cursors are process-local and become invalid after a restart.
- OFFLINE rules, fault description validation, and approval-driven operations
  remain unimplemented pending their integration decisions.
