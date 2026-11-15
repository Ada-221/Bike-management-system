# Architecture and agent conventions

This guide describes the combined Week 2 repository. The
[integration register](../integration-review.md) records settled interfaces and
open decisions. Historical Week 1 documents and draft APIs describe intentions;
implemented controllers and migrations define current backend behavior.

## Implemented components

| Component | Current behavior | Details |
|---|---|---|
| Fleet backend | Spring Boot vehicle registration, listing, lookup and updates; PostgreSQL migrations, development zones, health endpoint. IDs use `veh_` and `zone_`; statuses are `AVAILABLE`, `RENTED`, `MAINTENANCE_REQUIRED`. | [Backend](../fleet-management-service/README.md), [domain](backend/domain-model.md), [events](backend/events-owned.md), [ER diagram](backend/er-diagram.md). |
| Telemetry | Java ingestion publishes separate battery/location events to Kafka, keyed by vehicle ID, with distinct `evt_` IDs. Simulator requires explicit opt-in. | [Demo](../telemetry-ingestion-service/README.md), [contract](../telemetry-contract.md), [topics](../kafka-topics.md). |
| Dashboard | React/TypeScript UI uses synthetic fixtures. Proposal controls remain disabled; no live API or SSE integration. | [Dashboard](../operator-dashboard/README.md). |
| Rider Android | Demo repository is the default. An optional HTTP adapter targets draft nearby/rental APIs, which the backend does not implement. Do not enable it for the integrated demo. | [Rider](../rider-app/README.md). |
| MCP | Read-only backend adapter, low-battery and availability tools, validated explicit area mappings, independent battery/location timestamps, pagination safeguards, persisted state. `availableSince` is null. | [Service](../ai-mcp/README.md), [contracts](../contracts/ai-mcp/v1/README.md). |
| Planner | Deterministic mock planner, persisted proposals and human decision records. Approval is advisory and never mutates fleet state. | [Register](../integration-review.md), [MCP verification](ai-mcp/week-2-verification.md). |
| Infrastructure | Compose provides PostgreSQL, Kafka, backend, MCP, dashboard and schema viewer with persistent data volumes. Optional `telemetry` profile packages ingestion. | [Startup](../README.md), [integration verification](integration/week-2-verification.md). |

## Boundaries and deferred work

The backend implements vehicle endpoints and `/api/v1/health`. Nearby vehicle
search, rentals and rental return endpoints remain **unimplemented**. The broader
[OpenAPI](../api/fleet-api.yaml) and [SQL draft](../db/schema.sql) must not be read
as evidence that every described operation exists. Backend migrations are under
`fleet-management-service/src/main/resources/db/migration`.

Telemetry records currently have no fleet consumer. MCP queries the backend;
its Kafka projection, demand observations and OFFLINE rules remain deferred.
There is no operational proposal execution, live rental flow, live dashboard,
or LLM integration. Approval records a decision only, including after restart.

MCP uses backend vehicle IDs directly. Explicit configuration maps `area_center`
to `zone_001` and `area_south` to `zone_002`; invalid or ambiguous maps fail at
startup. MCP translates `RENTED` to `IN_USE` and `MAINTENANCE_REQUIRED` to
`MAINTENANCE`. Battery and location timestamps come from `lastBatteryAt` and
`lastLocationAt` independently; no timestamp is invented for `availableSince`.

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
