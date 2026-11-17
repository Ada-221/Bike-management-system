# Week 2 verification - 2026-11-15

Historical evidence: this report predates the Week 3 Kafka projection. See
[Week 3 verification](week-3-verification.md) for current evidence and gates.

Delivery branch: `feature/ai-mcp-contracts`, fast-forwarded from `3633333` to
`6d7d282` before reconciliation. `main` and the Week 2 branch remain unchanged.
The backend source was exported from `origin/feature/fleet-domain-model`
(`0b00586`) without checking out or modifying that branch. Telemetry schema and
examples were checked against `TelemetryIngestion.java` and `VehicleEvent.java`
on `origin/feature/event-telemetry` (`4c3333c`); no producer code was changed.

## Results

- All 16 MCP tests passed: protocol/discovery, input/output schemas, producer-shaped
  telemetry examples, DTO fields, API paths, status/area mapping, null
  availableSince, battery filtering, backend errors, incomplete snapshots,
  cursor continuation/expiry/query and data changes/restart, actual tool evidence,
  planner pagination, persisted proposals, and advisory decisions.
- Node syntax checks and `git diff --check` passed.
- `docker build -f ai-mcp/Dockerfile -t cebp-ai-mcp:week2 .` passed.
- Root Compose configuration validation passed.
- Isolated PostgreSQL 16 became healthy; the unchanged backend built and started.
  MCP `/health` returned UP and `/ready` returned READY.
- Registered synthetic E_BIKE vehicles with 12% and 80% battery through the real
  backend. MCP low-battery and minimum-battery availability queries returned the
  expected vehicles, backend-issued IDs and exact independent DTO timestamps.
  Availability returned null for availableSince.
- Planner creation and approval succeeded over HTTP. Fleet DTOs before and after
  approval were identical. After restarting the MCP container, the approved
  proposal remained persisted and fleet DTOs were still identical.
- No environment-blocked acceptance checks. Kafka projection, demand, OFFLINE,
  and combined team-branch integration remain deferred by scope.

## Reproduce the isolated smoke test

Run from the repository root in PowerShell with Docker Desktop running. The
Compose project has no host ports and uses an isolated database. Use a fresh
project/database for the create phase; the script registers two synthetic labels.

```powershell
New-Item -ItemType Directory -Force build/mcp-integration | Out-Null
git archive origin/feature/fleet-domain-model fleet-management-service --output=build/mcp-integration/backend.tar
tar -xf build/mcp-integration/backend.tar -C build/mcp-integration
docker build -f ai-mcp/Dockerfile -t cebp-ai-mcp:week2 .
docker compose -p cebp-mcp-week2-check -f ai-mcp/compose.smoke.yaml up -d --build
# Wait for backend startup, then run both phases:
Get-Content -Raw ai-mcp/scripts/backend-smoke.mjs | docker compose -p cebp-mcp-week2-check -f ai-mcp/compose.smoke.yaml exec -T mcp node --input-type=module -
docker compose -p cebp-mcp-week2-check -f ai-mcp/compose.smoke.yaml restart mcp
Get-Content -Raw ai-mcp/scripts/backend-smoke.mjs | docker compose -p cebp-mcp-week2-check -f ai-mcp/compose.smoke.yaml exec -T mcp node --input-type=module - verify
docker compose -p cebp-mcp-week2-check -f ai-mcp/compose.smoke.yaml down -v
```

The smoke script checks health/readiness before registering vehicles. If startup
is still in progress, wait for backend startup and retry. The verification phase
reads the saved proposal ID from the container's data volume.
