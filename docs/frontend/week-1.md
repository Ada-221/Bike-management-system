# Person 3 — Week 1 frontend handoff

## Requirement and scope

The six-week implementation plan assigns Person 3 (Frontend Lead) the React and
TypeScript operator dashboard, live map, SSE, operator approval UI, integration,
and usability testing. Its exact Week 1 milestone is **"Create dashboard
wireframes and frontend skeleton."** The plan uses person numbers, not names.

This deliverable provides the proposed layouts and a runnable, disconnected
skeleton. It does not claim the team's full Week 1 exit criteria are met: baseline
Docker infrastructure, reviewed/versioned interfaces, and the shared technology
freeze require the other owners' work and team review.

| Requirement | Artifact | Review evidence |
| --- | --- | --- |
| Dashboard wireframes | [Desktop](wireframes/desktop.svg), [mobile](wireframes/mobile.svg), screen flow below | Overview, fleet, review layout, narrow-screen order |
| React/TypeScript skeleton | `operator-dashboard/src` | Three navigable sections, strict application type check, production build |
| Fit shared contracts | `src/contracts/fleet.ts`, `src/data/sampleFleet.ts` | Backend field names and enum values; typed synthetic fixtures |
| Reproducible local workflow | Dashboard README, package manifest and lockfile | `npm ci`, build, browser tests |
| No implementation against undocumented interfaces | No network integration or proposal model | Explicit draft issues below; no guessed transport mappings |
| Synthetic data and human approval boundary | Sample-data notice, disabled controls, browser assertions | No API/operational requests or decisions |

## Wireframes and navigation

The SVGs are repository-native, low-fidelity design artifacts; open them directly
in a browser. Their labels describe intended placement, not live capability.

### Overview

Desktop: persistent sidebar; breadcrumb and sample-data indicator; page title and
preview notice; four fleet indicators; map placeholder beside proposal preview;
vehicle snapshot below. Status uses text as well as color. Counts are derived
from the four synthetic fixtures, not copied from an inconsistent API example.

Mobile: brand and horizontal navigation first, then heading and notice; indicators
in two columns; map, proposal panel, and snapshot stacked. The table scrolls in
its own labelled, keyboard-focusable region so the page stays within the viewport.

### Fleet

```text
Sidebar / top navigation
  Fleet heading + synthetic-data notice
  Vehicle snapshot: label/type | area | battery | status
  Future integration note
```

This Week 1 layout shares the static snapshot component. The Week 2 vehicle list
must use the agreed API client. Week 3 adds availability, battery and maintenance
filters plus a detail view containing location, status and telemetry freshness.

### Proposals

```text
Sidebar / top navigation
  Proposals heading + preview notice
  Review panel                         Planned workflow panel
    Layout example / rationale           Inspect suggestion
    Evidence and warnings                Inspect evidence / warnings
    [Approve disabled] [Reject disabled]  Make explicit human decision
    No operational effect                Inspect audit history
```

These are layout placeholders, not persisted proposal records. Real review is a
Week 4 milestone. No button changes fleet state or fabricates a decision.

### Planned connected states

| State | Intended UI behavior | Scheduled work |
| --- | --- | --- |
| Loading | Labelled progress for list and indicators | Week 2 initial client; Week 5 polish |
| Empty fleet | Clear empty-state explanation; no misleading zero-as-error state | Weeks 2–3 |
| API error | Visible failure with retry; preserve clearly marked previous data | Integration; Week 5 resilience |
| SSE disconnected | Visible reconnecting/stale status; resynchronise after reconnect | Weeks 4–5 |
| No proposals | Explain that no suggestions await review | Week 4 |
| Decision submitting/conflict | Prevent repeat taps; show server-confirmed outcome or conflict | Weeks 4–5 |

Only the static preview state is implemented in Week 1. Navigation currently
uses local React state, without deep links or browser-history handling.

## Compatibility review

Read-only source references inspected from the local clone:

| Branch / commit | Existing material | Frontend decision |
| --- | --- | --- |
| `main` / `d6ef2f2` | Six-week plan and README | Start isolated frontend branch from main |
| `feature/fleet-domain-model` / `312eade` | OpenAPI 0.1, SQL schema, domain/ER/event docs | Mirror Vehicle and Zone read shapes; do not merge or edit colleagues' files |
| `feature/ai-mcp-contracts` / `3633333` | MCP tool and proposal JSON schemas, fixtures, evaluation/test plan | Preserve advisory/human-review boundary; defer proposal transport types |
| `rider-mobile-app` / `e83d9b3` | Android navigation, fake repository, unit/UI tests | Separate rider and operator apps; do not reuse provisional mobile models |

`src/contracts/fleet.ts` manually mirrors the draft's required versus optional
fields; `zoneId` and `lastTelemetryAt` can also be null. Strings hold opaque IDs;
fixtures use UUIDs. These types do not perform runtime input validation. Recheck
against the reviewed OpenAPI before Week 2 and replace manual types with an
agreed generation workflow if the team chooses one.

The backend draft uses `E_BIKE` / `E_SCOOTER` and `AVAILABLE` / `RENTED` /
`MAINTENANCE_REQUIRED`. Its `rentable` response field is derived from available
status and battery >=20%. Our labels change only presentation, not wire values.
The mobile branch currently uses provisional `SCOOTER`, `MAINTENANCE`, and
human-readable IDs, so its models are not our transport source.

### REST and SSE integration reference (not implemented)

- Fleet service draft: `http://localhost:8080/api/v1`.
- Reads: `GET /vehicles`, `GET /vehicles/{vehicleId}`, `GET /zones`,
  `GET /fleet/status`, and `GET /maintenance` as needed.
- Vehicle filters: `status`, `zoneId`, `maxBattery`, `limit`. Lists are arrays;
  default limit 100, maximum 500. Plan explicitly for fleets larger than the limit.
- SSE draft: `GET /fleet/stream`, events `vehicle.updated`, `fleet.status`, and
  `heartbeat`. Events missed while disconnected are not replayed; refetch fleet
  status and vehicles on reconnect. Heartbeat is every 15 seconds.
- Proposal changes are not streamed in v0.1; the draft specifies polling.
- Planner/proposal routes are separately served on proposed port 8082 and remain
  draft pending Person 5 confirmation.
- Errors use `code`, `message`, `timestamp`, optional correlation ID and details.
  Render friendly messages but branch logic on documented codes.
- Future browser integration needs agreed CORS or development proxy settings;
  no proxy or deployment routing is invented in this milestone.

### Decisions required before proposal integration

| Topic | Backend draft | AI/MCP v1 | Required agreement |
| --- | --- | --- | --- |
| Proposal identity | `id`, `plannerRunId`, UUIDs | `proposalId`, `runId`, prefixed strings | Public REST representation and ID mapping |
| Fleet/area identity | Vehicle and zone UUIDs | `veh_…` / `area_…` | Explicit mapping or unified cross-service IDs |
| Pending state | `PENDING` | `PENDING_APPROVAL` | One agreed response state |
| Redistribution | `MOVE`, one `vehicleId`, zone fields | `REDISTRIBUTE`, multiple `vehicleIds`, area fields | Action transport shape |
| Decision/audit | Top-level decision fields | Nested approval, evidence, snapshot, audit | Fields needed for operator review |
| Approval effect | Approval event may trigger fleet mutation | Decision alone does not execute; separate fleet command required | Shared approval/execution semantics |
| Retry behavior | Naturally idempotent decision by state | Test plan describes replay with idempotency key | HTTP request/retry contract |

Person 1 and Person 5 need to settle these with Person 3 before client integration.
Do not infer a mapping from ID text. Keep schema versions explicit. The dashboard
will call documented REST APIs; it will not query the primary database or connect
directly to MCP/Kafka. No automatic action is introduced.

## Proposed technology and local layout

React + TypeScript follow the plan. Vite is the proposed minimal local build tool;
plain CSS avoids adding UI/map/state/router libraries before team agreement.
Playwright verifies behavior in the installed Edge browser; Prettier checks
formatting. Exact versions are pinned in the dashboard package manifest and
lockfile. Setup is isolated under `operator-dashboard/`, matching the separate
`rider-app/` layout already used by Person 4. Team review of tooling remains part
of the Week 1 freeze. No production hosting or CI policy is changed here.

## Verification

Verified on 2026-11-07 using Node.js 24.22.0, npm 11.19.1, and installed Microsoft
Edge. Exact dependency versions are recorded in `operator-dashboard/package.json`.

| Check | Result |
| --- | --- |
| `npm.cmd run format:check` | Passed for dashboard source, configuration, tests, and README |
| `npm.cmd run build` | Passed: strict application TypeScript check and Vite production build |
| `npm.cmd test` | 8 tests passed against the production build; pre-commit run completed in 6.1 seconds |
| SVG XML parsing | Both wireframes parsed successfully |
| `git diff --check` | Passed |
| Screenshot inspection | Desktop 1440px and mobile 390px reviewed; automated overflow/navigation checks also cover 320px |

The first browser run passed its assertions but sandboxed Windows process cleanup
stalled. Its specific preview process was stopped, then the complete suite was
rerun outside the sandbox: all eight tests passed and automatic teardown completed
normally. No application change was required for that environment issue.

The suite covers fixture UUIDs, enums, battery/coordinate bounds, zone references,
rentability, overview indicators, section navigation, disabled decisions, no
operational/external requests in the preview flow, overview runtime exceptions,
keyboard skip/navigation, and desktop/mobile overflow. Generated screenshots and
traces remain in ignored test output, not repository source.

Browser coverage is limited to Edge; backend integration, runtime response-schema
validation, live map, SSE, network resilience, and full WCAG compliance are not
certified by these Week 1 checks. This is a scaffold ready for review, not a claim
that later-week acceptance criteria are complete.

## Next milestones

- Week 2: API client, connected shell/list, map placeholder integration.
- Week 3: real vehicle data, status/battery/maintenance filters, detail view.
- Week 4: live map/SSE, proposal review against the agreed contract.
- Week 5: responsive/resilience completion, reconnect behavior, operator polish.
- Week 6: release candidate, screenshots and operator guide.

Generated with AI assistance and intended for human review. No colleagues' files
or branch histories were changed. No services, credentials, payments, production
cloud infrastructure, or real vehicle controls were added.
