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
- [AI/MCP evaluation criteria](docs/ai-mcp/evaluation-criteria.md)
- [AI/MCP test plan](docs/ai-mcp/test-plan.md)
- [Person 5 Week 1 traceability](docs/ai-mcp/week-1-traceability.md)

## Safety boundary

AI output is advisory. The planner may create proposals, but no proposal can
change fleet state without explicit operator approval.
