# Person 1 Week 3 fleet integration

The fleet service now handles nearby discovery, rental start and recovery,
idempotent return, maintenance, and fleet summaries. During a fault, the
vehicle immediately enters `MAINTENANCE_REQUIRED` and the trip stays returnable.
Resolving the fault during the trip restores `RENTED`. The fleet API counts
active rental records independently of vehicle status.

## Local verification

| Check | Result |
| --- | --- |
| Maven verify with Java 17 and PostgreSQL 16 Testcontainers | Passed 24 unit and 36 integration tests. The tests covered rollback, concurrent starts, retries, mid-trip faults, fault racing return, independent telemetry ordering, returns across zones, and a populated V2-to-V3 upgrade. |
| MCP unit and contract tests | Passed 35 tests and syntax checks. The shared example replays the fault and parked location in reverse arrival order. |
| Dashboard | Production build and all 8 Playwright tests passed using local Chrome. The Compose smoke separately exercised the live API through the dashboard proxy. |
| Isolated Compose smoke | Passed on project `week3-smoke-1794994085046-34716`. Uses fleet REST, the Java telemetry simulator, the dashboard route, and real Kafka events. |
| Android live instrumentation | Added to CI with an isolated fleet service. Local Gradle could start, but there was no Android SDK or emulator. Hosted CI execution is pending. |

The Compose run recovered an active faulted trip over HTTP, returned it twice,
and checked the final vehicle and location in both fleet REST and MCP. It
registered a vehicle while Kafka was offline, watched the outbox drain after
recovery, and rebuilt equivalent MCP state with the fleet service stopped.
The advisory approval recorded earlier in the run survived rebuild. The
runner writes logs and results to `build/week3-smoke-*`.

## Handoff

- Persons 2 and 5 review the [fleet event schema and example](../../contracts/fleet/v1/README.md)
  before enabling `fleet-week3-v1` on a shared stack. The isolated smoke
  selects it automatically.
- Person 3 can call `/api/v1/vehicles`, `/api/v1/vehicles/{id}`, `/api/v1/maintenance`,
  and `/api/v1/fleet/status` from the dashboard origin. The current React UI
  still renders its demo dataset.
- Person 4 can use the [live Android setup](../../rider-app/README.md).
  The CI scenario starts a rental from the UI, opens a fault, recreates the
  activity, resumes the trip, and returns it.
- Follow the [fleet upgrade steps](../../fleet-management-service/README.md)
  for databases initialized in Week 2. Merging into the shared Week 3
  integration branch requires the fleet and QA reviews.
