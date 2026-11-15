# E-Bike/Scooter Fleet Management System

Academic six-week demonstrator for managing a shared e-bike and scooter fleet.

The project combines rider and operator applications, fleet and telemetry services,
Kafka-backed integration, an independent MCP read model, and human-approved AI
charging and redistribution proposals.

## Documentation

- [Architecture and agent conventions](docs/architecture-and-agent-conventions.md)
- [Six-week implementation plan](docs/project/CEBP_Project2_Implementation_Plan.md)
- [Week 1 implementation summary](docs/project/week-1-implementation.md)
- [AI/MCP Week 1 contracts](contracts/ai-mcp/v1/README.md)
- [AI/MCP Week 2 service](ai-mcp/README.md)
- [AI/MCP evaluation criteria](docs/ai-mcp/evaluation-criteria.md)
- [AI/MCP test plan](docs/ai-mcp/test-plan.md)
- [Person 5 Week 1 traceability](docs/ai-mcp/week-1-traceability.md)

## Local Docker services

Copy `.env.example` to `.env` (`Copy-Item .env.example .env` in PowerShell), then
run `docker compose up --build -d --wait`. This starts PostgreSQL, Kafka, the
fleet backend, dashboard, schema explorer and MCP. The example maps
`area_center` to `zone_001` and `area_south` to `zone_002`, matching development
zone seeds. Override the local database password before using shared hosts.

- Fleet health: <http://localhost:8080/api/v1/health>

- Operator dashboard: <http://localhost:8081>
- AI/MCP schema explorer: <http://localhost:8082>
- MCP health endpoint: <http://localhost:8083/health>

The schema explorer reads the v1 manifest and schemas from a read-only Docker volume,
so it reflects the contract files in `contracts/ai-mcp/v1` without copying them into
the viewer image.

MCP waits for backend health and exposes dependency readiness at
<http://localhost:8083/ready>. Its internal backend URL is
`http://fleet-management-service:8080/api/v1/`; `BACKEND_API_URL` can override it
for standalone deployments. PostgreSQL, Kafka and MCP state have named volumes.

Telemetry is opt-in: see the [telemetry demo](telemetry-ingestion-service/README.md).
The `telemetry` profile uses `kafka:9092`; its simulator stays disabled unless
`TELEMETRY_SIMULATOR_ENABLED=true` is explicitly supplied.

Rider demo mode remains the default. The backend does not implement nearby or
rental endpoints required by the optional rider HTTP adapter. The dashboard
continues to use fixtures. Telemetry consumers, MCP Kafka projection, demand,
OFFLINE rules and operational proposal execution remain deferred.

Run `node scripts/compose-smoke.mjs` for a fresh isolated stack with no host port
collisions. It captures results and logs under `build/week2-smoke-*` and removes
only its own uniquely named containers and test volumes. Existing developer
volumes are preserved. See [Week 2 verification](docs/integration/week-2-verification.md)
for component checks and readiness blockers.

## Safety boundary

AI output is advisory. The planner creates proposals and records human decisions;
approval never executes actions or changes fleet state.
