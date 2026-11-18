# Architecture and agent conventions

This guide describes the integrated Week 3 baseline. The
[integration register](../integration-review.md) records settled interfaces and
open decisions. Historical Week 1 documents and draft APIs describe intentions;
implemented controllers and migrations define current backend behavior.

## Implemented components

| Component | Current behavior | Details |
|---|---|---|
| Fleet backend | Vehicle registration, nearby discovery, rentals and returns, maintenance, and fleet status. PostgreSQL owns operational state and the Kafka outbox. | [Backend](../fleet-management-service/README.md), [domain](backend/domain-model.md), [events](../contracts/fleet/v1/README.md). |
| Telemetry | Java ingestion publishes separate battery, location, and fault events keyed by vehicle ID. The simulator runs on explicit opt-in. | [Demo](../telemetry-ingestion-service/README.md), [contract](../telemetry-contract.md), [topics](../kafka-topics.md). |
| Dashboard | React/TypeScript fixture UI with a same-origin route to the fleet API. | [Dashboard](../operator-dashboard/README.md). |
| Rider Android | Demo repository by default. An optional HTTP adapter uses live fleet endpoints. | [Rider](../rider-app/README.md). |
| MCP | Durable Kafka projection with low-battery and availability tools, explicit zone mappings, and offline replay. Shared activation of the fleet adapter requires owner review. | [Service](../ai-mcp/README.md), [contracts](../contracts/ai-mcp/v1/README.md). |
| Planner | Deterministic mock planner, persisted proposals and human decision records. Approval is advisory and never mutates fleet state. | [Register](../integration-review.md), [MCP verification](ai-mcp/week-2-verification.md). |
| Infrastructure | Compose provides PostgreSQL, Kafka, backend, MCP, dashboard and schema viewer with persistent data volumes. Optional `telemetry` profile packages ingestion. | [Startup](../README.md), [integration verification](integration/week-2-verification.md). |

## Boundaries and deferred work

The fleet service consumes normalized telemetry and publishes the resulting
fleet transitions. A fault can place a vehicle in maintenance immediately
while leaving the active trip returnable.

Demand, OFFLINE inference, live dashboard updates, LLM integration, and
operational proposal execution remain deferred. Recorded proposal decisions
persist across restarts.

MCP uses backend vehicle IDs directly. Explicit configuration maps `area_center`
to `zone_001` and `area_south` to `zone_002`; invalid or ambiguous maps fail at
startup. MCP translates `RENTED` to `IN_USE` and `MAINTENANCE_REQUIRED` to
`MAINTENANCE`. Battery and location timestamps come from independent event
measurements; no timestamp is invented for `availableSince`.

## Shared conventions and governance

- Inspect component code, contracts, tests and documentation before changing it.
- Preserve backend API behavior, migrations, prefixed IDs and status vocabulary.
- Communicate through documented REST or Kafka interfaces. Planner reads use MCP
  tools only. Never invent mappings, endpoints or events to simulate integration.
- Update affected schemas, examples, documentation and tests together when an
  agreed interface changes. Mark unresolved proposals as drafts.
- Use UTC timestamps and synthetic data. Preserve event IDs across retries;
  separate battery/location events always have distinct `evt_` IDs.
- Report unrun checks as blockers. Do not claim full readiness from unit tests
  alone; the combined acceptance evidence is tracked in the verification report.
- AI proposals cannot change operational state. Any future command execution
  requires a separately agreed interface and authorization.

Affected owners should agree on cross-component changes and record the outcome
in the integration register; unresolved disagreements go to the project lead.
Remaining decisions include OFFLINE evidence/timeouts, demand ownership, fault
validation, Kafka retention/retry/dead-letter policy, equal-time event ordering,
non-telemetry producer/consumer ownership, status-since evidence, operational
execution and future live client APIs.

See the [six-week plan](project/CEBP_Project2_Implementation_Plan.md) for target
milestones and the [Week 1 summary](project/week-1-implementation.md) for history.
