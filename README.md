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
- [AI/MCP Week 3 service and replay](ai-mcp/README.md)
- [Week 3 fleet integration and verification](docs/integration/week-3-fleet-verification.md)
- [Person 5 Week 3 implementation plan](docs/ai-mcp/week-3-implementation-plan.md)
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

MCP waits for Kafka and topic initialization and exposes projection readiness at
<http://localhost:8083/ready>. It reads only its durable JSON Kafka projection.
Configure `MCP_KAFKA_BROKERS` and a dedicated `MCP_KAFKA_GROUP_ID`. For an
isolated local demo, set `MCP_BACKEND_EVENT_CONTRACT=fleet-week3-v1` in `.env`
before registering vehicles. Shared activation requires the owner review
recorded in the [integration register](integration-review.md).

Telemetry is opt-in: see the [telemetry demo](telemetry-ingestion-service/README.md).
The `telemetry` profile uses `kafka:9092`; its simulator stays disabled unless
`TELEMETRY_SIMULATOR_ENABLED=true` is explicitly supplied.

The rider app can use nearby and rental endpoints by setting the
[backend URL](rider-app/README.md). The dashboard's fixture UI has a same-origin
route to the live fleet API at `http://localhost:8081/api/v1/` for its data
integration. Demand, OFFLINE rules, and operational proposal execution remain
deferred.

Run `node scripts/compose-smoke.mjs` for a fresh isolated stack with no host port
collisions. It uses the actual fleet REST and telemetry producers, captures evidence
under `build/week3-smoke-*`, and removes
only its own uniquely named containers and test volumes. Existing developer
volumes are preserved.

## Safety boundary

AI output is advisory. The planner creates proposals and records human decisions;
approval never executes actions or changes fleet state.
