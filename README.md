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

Start the operator dashboard, AI/MCP schema explorer, and MCP service with `docker compose up --build`.

- Operator dashboard: <http://localhost:8081>
- AI/MCP schema explorer: <http://localhost:8082>
- MCP health endpoint: <http://localhost:8083/health>

The schema explorer reads the v1 manifest and schemas from a read-only Docker volume,
so it reflects the contract files in `contracts/ai-mcp/v1` without copying them into
the viewer image.

## Safety boundary

AI output is advisory. The planner may create proposals, but no proposal can
change fleet state without explicit operator approval.
