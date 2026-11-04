# CEBP Project 2 - Six-Week Implementation Plan

## 1. Project objective

Deliver an academic demonstrator for an E-Bike/Scooter Sharing Fleet Management System in six weeks with five people. The system must demonstrate:

- Vehicle discovery, simulated rental and return.
- Telemetry ingestion for location, battery, and faults.
- Fleet monitoring with live dashboard updates.
- MCP queries backed by an independently maintained Kafka read model.
- AI-generated charging and redistribution proposals.
- Explicit operator approval or rejection of every proposal before operational state can change.

## 2. Baseline technology

- Android rider mobile app.
- React and TypeScript operator dashboard.
- Spring Boot Java backend services.
- PostgreSQL database.
- Apache Kafka event broker.
- Docker Compose for local deployment.
- MCP server with a Kafka-backed read model.
- OpenAI-compatible LLM endpoint for the nightly AI planning agent.

Authentication, payments, real hardware control, and production cloud deployment are out of scope unless separately required by the course. Telemetry, rider, vehicle, and demand data are synthetic.

## 3. Team responsibilities

| Person | Primary ownership | Secondary responsibility |
|---|---|---|
| Person 1 - Backend/Fleet Lead | Fleet-management service, rental lifecycle, vehicle state, REST APIs | Integration coordination and API contracts |
| Person 2 - Event/Telemetry Lead | Kafka infrastructure, telemetry simulator, ingestion service, event schemas | Observability and integration testing |
| Person 3 - Frontend Lead | React operator dashboard, live map, SSE updates, operator approval workflow | UI integration and usability testing |
| Person 4 - Mobile Lead | Android rider app, vehicle discovery, rental/return flows | Mobile/backend integration |
| Person 5 - AI/MCP/QA Lead | MCP server, read model, AI planner, proposal workflow, end-to-end QA | Documentation, test automation, release verification |

All people contribute to code reviews, integration debugging, demonstrations, and final documentation.

## 4. Architecture and interfaces

### 4.1 Core services

#### `fleet-management-service`

- Owns vehicle inventory and status, rentals, returns, and maintenance state.
- Provides REST APIs for rider and dashboard clients.
- Persists operational state in PostgreSQL.
- Publishes domain events to Kafka.

#### `telemetry-ingestion-service`

- Receives simulated GPS, battery, and fault data.
- Validates telemetry and updates operational state through events or service APIs.
- Publishes normalized telemetry events.

#### `vehicle-simulator`

- Generates deterministic, configurable vehicle movement.
- Simulates battery drain, faults, availability, and location changes.
- Supports reproducible demonstration scenarios.

#### `mcp-server`

- Maintains its own read model from Kafka events.
- Exposes tools for low-battery vehicles, vehicle availability by area, and demand by area and time period.
- Provides a stable HTTP MCP interface for the AI agent.

#### `ai-planner`

- Runs nightly or on demand for demonstrations.
- Queries MCP tools and produces charging and redistribution proposals.
- Stores proposals and their status.
- Never directly changes vehicle state.

### 4.2 Event contracts

Versioned JSON schemas must be defined before parallel implementation. Required event types:

`VehicleRegistered`, `VehicleLocationUpdated`, `VehicleTelemetryReceived`, `VehicleBatteryUpdated`, `VehicleFaultReported`, `RentalStarted`, `RentalEnded`, `VehicleMaintenanceChanged`, `DemandRecorded`, `RebalancingProposalCreated`, `RebalancingProposalApproved`, and `RebalancingProposalRejected`.

Every event includes:

- Event ID.
- Event type and schema version.
- UTC timestamp.
- Vehicle or proposal ID where applicable.
- Correlation ID.
- Payload.
- Producer service.

Events must remain backward-compatible within the active release. Consumers must tolerate duplicate delivery, and the MCP read model must be independently rebuildable from Kafka events.

### 4.3 Required API capabilities

The exact OpenAPI specification is committed during Week 1. At minimum it supports:

- Listing nearby available vehicles.
- Getting vehicle details and battery level.
- Starting a rental.
- Returning a vehicle.
- Getting fleet status.
- Getting maintenance-required vehicles.
- Subscribing to live fleet updates through SSE.
- Listing proposals.
- Approving or rejecting a proposal.
- Triggering an AI planning run for demonstrations.

Rental and proposal operations must be idempotent. Services communicate only through documented REST APIs or Kafka events.

## 5. Six-week delivery plan

### Week 1 - Requirements, architecture, and contracts

**Project milestone:** Freeze architecture, repository layout, technology versions, event schemas, API contracts, local development workflow, and definition of done.

**Deliverables:** Architecture diagram; service ownership map; OpenAPI draft; Kafka topic list and event schemas; PostgreSQL entity model; Docker Compose baseline; shared coding, branching, and AI-agent rules; risk register; and test strategy.

**Individual milestones:**

- Person 1: Define fleet, vehicle, rental, and maintenance models; publish the initial API contract.
- Person 2: Define Kafka topics, event envelope, telemetry format, and simulator scenarios.
- Person 3: Create dashboard wireframes and frontend skeleton.
- Person 4: Create Android skeleton and rider navigation flow.
- Person 5: Define MCP tool contracts, AI proposal schema, evaluation criteria, and test plan.

**Exit criteria:** Everyone can run the baseline environment; API and event contracts are reviewed and versioned; no implementation begins against undocumented interfaces.

### Week 2 - Infrastructure and vertical skeleton

**Project milestone:** Establish a running end-to-end skeleton in which clients, backend, Kafka, PostgreSQL, and placeholder AI/MCP components communicate.

**Deliverables:** Docker Compose environment; database migrations; Kafka topics; health checks; CI build and test pipeline; skeleton REST, SSE, MCP, mobile, and dashboard components.

**Individual milestones:**

- Person 1: Implement Spring Boot fleet-service structure, migrations, vehicle CRUD, and health endpoint.
- Person 2: Implement Kafka setup, event serializer, telemetry simulator, and ingestion skeleton.
- Person 3: Implement dashboard shell, API client, map placeholder, and vehicle list.
- Person 4: Implement Android screens for vehicle list, details, rental, and return.
- Person 5: Implement MCP skeleton, read-model tables, proposal persistence, and mock planner.

**Exit criteria:** A simulated vehicle event can be published and consumed; the dashboard displays a placeholder vehicle; the mobile app calls a backend endpoint; CI builds all services.

### Week 3 - Core fleet functionality

**Project milestone:** Complete vehicle registration, discovery, rental, return, telemetry update, and persistence.

**Individual milestones:**

- Person 1: Implement rental start/return logic, availability and maintenance rules, rental and vehicle-state events, and unit/integration tests.
- Person 2: Complete telemetry validation and ingestion, location/battery updates, deterministic scenarios, and duplicate/out-of-order event tests.
- Person 3: Display real vehicle data; add availability, battery, and maintenance filters; implement vehicle detail view.
- Person 4: Complete nearby discovery, rental and return flows, battery/status display, and rental errors.
- Person 5: Consume fleet events into the MCP read model; implement low-battery and availability tools; add tool-level tests and sample requests.

**Exit criteria:** A complete mobile rental/return flow works through backend and database; telemetry changes are visible on the dashboard; MCP queries return data independently of the primary fleet database.

### Week 4 - Live operations and AI planning

**Project milestone:** Deliver live dashboard updates and a working AI planning path using realistic simulated data.

**Individual milestones:**

- Person 1: Stabilize behavior and error handling; add concurrency and idempotency protections; support proposal approval/rejection state changes.
- Person 2: Publish telemetry and demand continuously; add fault and low-battery scenarios; verify Kafka retry and dead-letter behavior.
- Person 3: Implement SSE; update map and fleet indicators in real time; implement proposal review interface.
- Person 4: Improve mobile UX and error states; add location/area selection; test rental conflicts and unavailable vehicles.
- Person 5: Implement demand-by-area MCP tool, nightly/on-demand planner, structured proposals, and approval/rejection workflow.

**Exit criteria:** Dashboard updates without manual refresh; the planner uses MCP tools rather than direct database access; proposals are reviewable and traceable; approval changes emit auditable events.

### Week 5 - Integration, resilience, and usability

**Project milestone:** Reach feature complete and validate the distributed workflow under failure and realistic demonstration scenarios.

**Individual milestones:**

- Person 1: Complete API validation and authorization boundaries, fix business-rule defects, and add full fleet-service integration tests.
- Person 2: Test broker restart, consumer retry, duplicate messages, malformed telemetry, metrics, structured logging, and final simulator scenarios.
- Person 3: Complete responsive layout, map markers, filters, loading/error states, SSE reconnect behavior, and operator workflow polish.
- Person 4: Complete rider validation and usability pass; test network loss, duplicate actions, stale data; produce the demo mobile build.
- Person 5: Test MCP accuracy and planner quality; add deterministic mock-LLM mode; execute end-to-end tests; draft technical documentation.

**Exit criteria:** All acceptance scenarios pass in a clean environment; failures are visible and recoverable; the planner works without an external LLM; no critical or high-severity defects remain open.

### Week 6 - Release, demonstration, and final documentation

**Project milestone:** Freeze features, complete release verification, demonstrate the system, and submit the final project package.

**Individual milestones:**

- Person 1: Backend release candidate, API documentation, and migration verification.
- Person 2: Infrastructure release candidate, simulator package, and Kafka operational guide.
- Person 3: Dashboard release candidate, screenshots, and operator guide.
- Person 4: Android release candidate, installation instructions, and rider guide.
- Person 5: Final end-to-end report, AI/MCP documentation, demo script, and release checklist.

**Final deliverables:** Tagged source repository; Docker Compose deployment; API and event documentation; migrations and seed data; mobile build; operator dashboard; MCP server and AI planner; test report; architecture diagram; user guides; demonstration script; known limitations; and future work.

## 6. Final acceptance criteria

- A rider can locate an available vehicle, inspect its battery, start a rental, and return it.
- Telemetry updates vehicle location, battery, and fault status.
- Operators can monitor fleet state on a live dashboard.
- Kafka carries asynchronous domain and telemetry events.
- The MCP server answers fleet and demand queries from its own read model.
- The AI planner produces a structured proposal based on MCP results.
- Operators can approve or reject proposals.
- Approved proposals are auditable and cannot bypass operator approval.
- The complete system runs from documented setup instructions.

## 7. AI-agent rules

### Repository and change discipline

1. Read relevant existing code, contracts, tests, and documentation before editing.
2. Never rewrite or delete unrelated user work.
3. Make small, focused changes.
4. Update contracts, consumers, tests, and documentation when public interfaces change.
5. Do not add frameworks, services, dependencies, or data stores without team approval.
6. Follow established formatting, naming, and package conventions.
7. Never commit secrets, API keys, personal data, or machine-specific paths.
8. Identify generated files clearly.
9. Make every change reproducible from a clean checkout.

### Architecture and integration rules

1. Use documented REST APIs or Kafka events between services.
2. The AI planner accesses operational information through MCP tools only.
3. The MCP read model is independently rebuildable from Kafka events.
4. Events are versioned and backward-compatible within the active release.
5. Consumers tolerate duplicate delivery.
6. State-changing commands are idempotent.
7. External LLM availability is never required for CI or core fleet functionality.
8. The planner creates proposals only; it cannot approve, charge, move, rent, or disable vehicles.
9. Human approval is mandatory before a proposal affects operational state.
10. Use UTC timestamps and stable IDs in cross-service data.

### Coding and testing rules

1. Add or update tests for every behavior change.
2. Prefer unit tests for business rules and integration tests for service boundaries.
3. Add contract tests for REST and event interfaces.
4. Cover invalid input, unavailable vehicles, duplicate requests, stale data, broker failure, and LLM failure.
5. Do not mark work complete because code compiles; demonstrate the acceptance scenario.
6. Run formatting, static analysis, unit tests, and relevant integration tests before handoff.
7. Every AI-generated change includes a summary, files changed, tests run, and known limitations.

### AI output and safety rules

1. Treat generated code and plans as untrusted drafts until human review.
2. Do not fabricate test results, API behavior, logs, or references.
3. Label assumptions and unresolved questions.
4. Do not send source code, credentials, private project data, or telemetry to an external model without explicit approval.
5. Use synthetic vehicle, rider, location, and demand data.
6. Do not use AI for unsupervised operational decisions.
7. Prompts and model outputs are not authoritative system state.
8. Validate planner output against a schema and reject malformed or unsafe output.
9. Log planner run ID, input-data version, model identifier, output, and approval status.
10. Hard business rules override AI recommendations.

## 8. Working agreement

- Daily 15-minute coordination meeting.
- Weekly milestone review and demonstration.
- Pull requests require one owner review and one integration/QA review.
- Contract changes require agreement from all affected owners.
- Main branch remains buildable.
- Incomplete features are merged behind a flag.
- Every Friday, create a tagged integration snapshot.
- Escalate blockers older than one working day to the whole team.
- Week 4 is the feature-complete target; Week 5 is reserved for stabilization.
- No new major features are accepted during Week 6.

## 9. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Kafka integration consumes excessive time | Establish topics and schemas in Week 1; use Docker Compose and test fixtures. |
| Mobile delivery slips | Use a narrow rider workflow and complete a working vertical slice by Week 3. |
| Map integration becomes difficult | Use a configurable provider and retain a fallback list view. |
| External LLM is unreliable or unavailable | Implement deterministic mock planner and schema validation. |
| AI agent produces unsafe actions | Restrict it to proposals and require explicit operator approval. |
| Cross-service contract drift | Version schemas and run contract tests in CI. |
| Distributed debugging is difficult | Add correlation IDs, structured logs, health endpoints, and basic metrics. |
| Scope expands beyond six weeks | Freeze requirements in Week 1 and prioritize acceptance criteria. |

## 10. Assumptions and open decisions

- The fifth team member is available for AI/MCP ownership and QA.
- The system is an academic demonstrator, not a production commercial service.
- Vehicle telemetry and demand are simulated.
- A live external LLM is optional for demonstrations; mock mode is mandatory.
- The plan uses the recommended full-stack baseline selected by the team.
- Authentication, payments, real hardware control, and production cloud deployment remain out of scope unless the course requires them.
- The exact technology versions, repository layout, topic names, OpenAPI details, database schema, and map provider are frozen during Week 1.

