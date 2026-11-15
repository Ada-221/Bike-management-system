# Architecture and agent conventions

This is the project entry point for understanding component ownership, documented
interfaces, implementation status, and the rules for AI-assisted changes. Domain
documents remain authoritative for their own details. Cross-component proposals
remain drafts until the affected owners agree and the decision is recorded in the
[integration decision register](../integration-review.md).

## Architecture map

The six-week project plan is the source for intended responsibilities and
milestones. The Week 1 implementation summary checks what exists in this
repository. “Implemented” below describes only the artifacts or behavior listed;
it does not imply that a complete end-to-end service exists.

| Component | Responsibility and owner | Status in this repository | Relationships and detail |
|---|---|---|---|
| Fleet management service | Owns vehicles, zones, rentals, maintenance, REST APIs and operational state. Person 1 — Backend/Fleet Lead. | **Planned, not implemented.** Domain model, OpenAPI draft, SQL schema and event ownership documents exist. | Intended to serve rider and dashboard clients, persist in PostgreSQL, and publish/consume Kafka events. See [domain model](backend/domain-model.md), [fleet API draft](../api/fleet-api.yaml), [database schema draft](../db/schema.sql), and [fleet events](backend/events-owned.md). |
| Telemetry ingestion and vehicle simulator | Person 2 — Event/Telemetry Lead. Validate synthetic measurements, publish normalized telemetry, and provide reproducible scenarios. | **Planned, not implemented.** Schemas, examples, topic proposals and scenario specifications exist. | Intended to publish Kafka events consumed by fleet management and the MCP read model, subject to the pending integration decisions. See [telemetry contract](../telemetry-contract.md), [Kafka topic proposal](../kafka-topics.md), [simulator scenarios](../simulator-scenarios.md), and [fault schema](../fault.schema.json). |
| Operator dashboard | Person 3 — Frontend Lead. Show fleet state and support human review of proposals. | **Partially implemented.** A React/TypeScript client prototype with local navigation and synthetic fixtures is present. It has no fleet API, SSE, live map, or operational proposal integration; proposal controls are disabled. | Intended to read documented fleet APIs and SSE, and to show AI proposals for human decisions. See [frontend handoff](frontend/week-1.md) and [dashboard README](../operator-dashboard/README.md). |
| Rider Android app | Person 4 — Mobile Lead. Vehicle discovery, rental and return user flows. | **Partially implemented.** A Kotlin/Compose skeleton uses an in-memory fake repository and synthetic data; it has no backend or persistent integration. | Intended to call fleet REST APIs for discovery, rental and return. See [rider app README](../rider-app/README.md). |
| MCP server and read model | Person 5 — AI/MCP/QA Lead. Maintain an independent Kafka-backed query model and expose low-battery, availability-by-area and demand-by-area tools. | **Planned, not implemented.** Versioned v1 tool and proposal contracts, schemas, examples and invalid fixtures exist; no server or read model exists. | Intended to consume agreed Kafka events independently of the fleet database and expose read-only tools to the planner. See [AI/MCP v1 contracts](../contracts/ai-mcp/v1/README.md), [evaluation criteria](ai-mcp/evaluation-criteria.md), [test plan](ai-mcp/test-plan.md), and [Week 1 traceability](ai-mcp/week-1-traceability.md). |
| AI planner and proposal workflow | Person 5 — AI/MCP/QA Lead. Query MCP and create auditable charging or redistribution proposals. | **Planned, not implemented.** Proposal schema and evaluation/test plans exist; no planner, proposal persistence, or LLM integration exists. | The planner is intended to use MCP only. Proposals are advisory and require human approval; the effect of approval on fleet state remains pending. See [AI/MCP v1 contracts](../contracts/ai-mcp/v1/README.md) and [integration decision register](../integration-review.md). |
| Kafka and PostgreSQL | Person 2 owns Kafka infrastructure; Person 1 owns fleet persistence. | **Planned, not implemented as project services.** Topic and SQL designs exist. The current Compose file runs the dashboard and schema viewer only. | Kafka carries agreed service events; PostgreSQL stores fleet operational state. The MCP read model is separately owned. See [Kafka topic proposal](../kafka-topics.md), [database schema draft](../db/schema.sql), and [Compose configuration](../compose.yaml). |

The [six-week implementation plan](project/CEBP_Project2_Implementation_Plan.md)
describes the full target architecture, schedule, responsibility table, and
acceptance criteria. The [Week 1 implementation summary](project/week-1-implementation.md)
is the repository status reference for what the initial milestone delivered.

## Shared implementation and interface conventions

- Before changing a component, inspect its relevant code, contracts, tests, and
  documentation. Check the ownership and implementation status in this guide
  against the current repository when needed.
- Follow the owning component's documented contract and the current recorded
  decisions. Do not invent cross-component identifiers, fields, topics, mappings,
  or behavior to make a local implementation appear integrated.
- When an interface changes, update the affected schemas, examples,
  producer/consumer documentation, tests, and client types together. Keep version
  and compatibility implications explicit.
- Services communicate through documented REST APIs or Kafka events. The AI
  planner accesses operational information through MCP tools only; the MCP read
  model must be independently rebuildable from Kafka once those services exist.
- Treat drafts as drafts. The v1 AI/MCP contract defines its own stable prefixed
  IDs, while fleet drafts use UUIDs; neither document alone establishes a shared
  cross-service ID contract.
- Use UTC timestamps and synthetic rider, vehicle, location, and demand data as
  specified in the implementation plan. Do not report unrun tests or claim
  planned integration as implemented.
- AI-generated code and decisions remain proposals until reviewed. The planner
  may not change operational state directly; the approval-to-execution boundary
  is still an unresolved cross-component decision.

## Decision governance

Owners of every affected component should seek agreement on an interface or
behavior change first. If they cannot agree, the project lead or team decides.
Record the outcome and rationale in the [integration decision register](../integration-review.md),
then update all affected contracts, examples, code, and documentation together.
Until the decision is recorded, mark the issue pending and do not treat any draft
as an approved shared contract. Component-specific documents may describe their
own proposals, but they do not settle cross-component decisions by themselves.

## Current changes to make

- Reconcile the fleet, telemetry, and AI/MCP drafts into agreed cross-component
  contracts. Track each unresolved point in the [integration decision register](../integration-review.md).
- Update schemas, examples, producer and consumer documentation, and tests that
  are affected by those agreements.
- Confirm component ownership and integration status against the
  [implementation plan](project/CEBP_Project2_Implementation_Plan.md) and the
  current repository. Several services in the target architecture are not yet
  implemented; the [Week 1 summary](project/week-1-implementation.md) records the
  current baseline.

## Decisions still needed

All items below remain **pending**. Their current differences and affected
owners are tracked in the [integration decision register](../integration-review.md).
The register is the current place to record resolution and rationale.

- Vehicle ID format, or an explicit mapping between backend UUIDs and AI/MCP
  prefixed IDs.
- Event ID format and matching storage, deduplication, and replay behavior.
- Telemetry event set and shape, including envelope fields and measurement time
  versus publication time.
- Fault description requiredness and producer identifier naming.
- Kafka topic routing and the battery/location update strategy, including how
  stale and equal-time measurements are handled.
- Fleet-to-MCP status and area mappings, including the meaning of `OFFLINE`.
- Approval boundary: whether fleet state changes after an approved proposal and
  which component performs any resulting state change.
- Demand event ownership: simulator publication or MCP derivation from rental
  events.
- Kafka retention, retry, and dead-letter handling.
- Additional API or UI integration needs raised by the
  [domain-model open questions](backend/domain-model.md), including nearby
  vehicle filters and dashboard SSE event types.
- Proposal integration choices also identified in the
  [frontend handoff](frontend/week-1.md), including proposal IDs/states, action
  shape, decision/audit fields, and decision retry behavior.
