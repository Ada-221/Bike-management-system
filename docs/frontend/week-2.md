# Person 3 — Week 2 frontend handoff

## Milestone and branch

The implementation plan assigns Person 3 four Week 2 deliverables: **dashboard
shell, API client, map placeholder, and vehicle list**. Week 1 supplied the shell
and visual layout; this change connects a read-only vehicle list and fleet
summary while retaining the illustrative map.

Local branch: `feature/frontend-week-2`. Base: `bac920b` from
`origin/feature/fleet-domain-model`, which already includes the Week 2 integration
branch and subsequent MCP/fleet work. This preserves the team's integrated
Docker, Nginx, and browser-test configuration. The old frontend branch and all
colleagues' branches are unchanged. Week 2 is delivered in its own frontend
commit before starting the separate Week 3 milestone.

Only `operator-dashboard/` and `docs/frontend/` are edited. The existing untracked
`lab-presentation-week-1.md` was left alone and is not part of the Week 2 work.

## Requirement mapping

| Week 2 requirement | Implementation |
| --- | --- |
| Dashboard shell | Existing Overview, Fleet, and Proposals navigation retained; updated data-source status and Week 2 labels |
| API client | Native fetch; same-origin GETs to `/api/v1/vehicles?limit=500` and `/api/v1/fleet/status`; response validation, timeout, cancellation, correlation ID and structured errors |
| Map placeholder | Original illustrative layout retained, explicitly labelled; no real map library or provider added |
| Vehicle list | Shared component renders backend labels, type, battery, status, and zone names; empty/error/loading states and manual refresh |
| Client/backend connection | Vite dev/preview proxy added; team's Nginx Docker proxy preserved; isolated live browser test uses the real fleet service and PostgreSQL |

The broader Week 2 team exit criteria also concern Kafka, mobile, MCP and CI.
This handoff verifies the frontend slice rather than declaring the whole team
milestone complete. Filters/details remain Week 3; automatic SSE and proposals
remain Week 4. Basic loading/error handling is necessary for a usable Week 2
HTTP client and is not a claim that Week 5 resilience work is complete.

## Compatibility decisions

- Read types follow the implemented Java `VehicleResponse` and `FleetStatus`
  controllers at the base revision. IDs are opaque prefixed strings rather than
  UUID-only values. Demo/test fixtures were updated accordingly.
- Keep backend status vocabulary: `AVAILABLE`, `RENTED`, and
  `MAINTENANCE_REQUIRED`, plus `E_BIKE` and `E_SCOOTER` types.
- Battery/location timestamps remain independent and may be null. Unknown
  response properties are tolerated; missing/invalid required display fields,
  percentages, coordinates, timestamps, enums, and duplicate vehicle IDs are
  rejected before rendering. This is focused boundary validation, not a full
  OpenAPI/JSON Schema implementation.
- There is no implemented `/zones` route. Display names come from the fleet
  summary. If that summary fails, vehicles still render using their actual zone
  IDs with a name-unavailable label. Null/absent zones display as unassigned.
- Fleet counts come from `/fleet/status`, not from a truncated vehicle list.
  `activeRentals` is displayed independently from `countByStatus.RENTED`, as a
  faulted vehicle can still have an active, returnable rental.
- Request up to 500 vehicles, the current backend maximum. Warn when the response
  fills that page. There is no invented pagination parameter or claim to show
  every vehicle above this limit. A future backend pagination contract requires
  owner agreement; summary totals remain useful for larger fleets.
- Two independent resources allow partial success. Refresh preserves the last
  successful snapshot on failure and marks it as potentially stale. Source
  changes clear prior-source data and abort outstanding requests.
- Backend mode is the default; demo mode is explicitly selected. Failed calls
  never create successful-looking fixture results. The demo mode remains clearly
  labelled and makes no REST calls after selection has cancelled active loads.
- GETs carry a fresh `X-Correlation-Id`, use JSON Accept and no-store caching,
  and time out after ten seconds. Error codes/status/reference are retained;
  arbitrary backend diagnostics and HTML proxy bodies are not displayed.
- Vite proxies `/api/v1` to `http://127.0.0.1:8080` by default. Optional
  `FLEET_API_TARGET` changes only the local server target. Docker retains the
  existing Nginx route and does not depend on Vite or CORS changes in Java.
- Proposal controls remain non-operational. The integrated advisory-only approval
  rule is reflected in the explanatory text. No writes, Kafka/MCP access, database
  access, or automatic operational actions occur in the browser application.

## Local use

See [dashboard setup](../../operator-dashboard/README.md). Start the existing
backend and its dependencies using the team's configuration, then run
`npm.cmd run dev` in `operator-dashboard`. The default local URL is
`http://127.0.0.1:5173`. Keep the terminal open. Use the **Data source** control to
select Backend or Demo; **Refresh** retrieves new snapshots. There is no polling
or SSE. Loading errors remain visible and have resource-specific retry controls.

For the project's Docker dashboard, the existing port is 8081. A real backend
starts with development zones but no vehicles; an empty list is a valid result.
The frontend does not create vehicles as a hidden startup step.

## Tests and evidence

The test suite exercises the client with deterministic responses, rather than
requiring a live shared developer stack for every browser check. Separate
frontend-owned smoke tooling creates a unique Compose project, a fresh PostgreSQL
volume, the unmodified fleet backend image, and the actual dashboard/Nginx image.
Only the dashboard receives an ephemeral loopback port. Kafka is disabled in that
test because the frontend reads REST; it does not certify event integration.
The browser registers synthetic test vehicles through HTTP and checks empty and
populated list states, backend totals, maintenance status, zone labels and refresh.
Only that unique project's containers and volume are removed; no existing data
or shared Compose/migrations/backend files are changed.

Commands from `operator-dashboard`:

```powershell
npm.cmd run format:check
npm.cmd run build
$env:PLAYWRIGHT_CHANNEL = 'msedge'
npm.cmd test
npm.cmd run test:live
```

Chromium remains the default browser for the team's existing CI workflow. The
Edge override preserves local compatibility without changing that behavior.
Regular test evidence is under ignored `test-results/browser/`; real-stack logs,
screenshots and result records are under `test-results/frontend-week2-*/`.

The results below are local; no hosted CI result is claimed. Full browser/accessibility coverage,
Kafka/SSE resilience, and the unimplemented Week 3 features are outside this test
claim. No new package dependency was added.

### Executed verification — 22 November 2026

Environment: Node.js 24.22.0, npm 11.19.1, Docker Engine 29.9.1 and installed
Microsoft Edge. Existing package versions and the package lockfile are retained.

| Check | Result |
| --- | --- |
| Formatting | `npm.cmd run format:check` passed |
| Application static checking and production build | `npm.cmd run build` passed locally and in the existing dashboard Dockerfile |
| API client and mocked-REST browser suite | 25 passed, no failures/skips, 16.7 seconds; 11 client checks and 14 browser checks |
| Actual backend/PostgreSQL/Nginx browser smoke | Passed on the initial run and final run; final browser test took 4.8 seconds |
| Vite development proxy | Real `/fleet/status` request returned HTTP 200 with the two registered vehicles, using the actual Vite configuration |
| Script syntax | `node --check scripts/live-smoke.mjs` passed |
| Visual review | Desktop 1440px and mobile 390px screenshots reviewed; automated layout checks also cover 320px |
| Scope and whitespace | `git diff --check` passed; changes restricted to frontend code/docs |

Final isolated project: `frontend-week2-1795339242576-2360`. The browser checked
the actual empty fleet, registered two synthetic bikes through Nginx and the
fleet API, refreshed, and observed counts `2 / 1 / 0 / 1` for total, available,
active rentals and maintenance respectively. It verified the low-battery vehicle's
maintenance state, backend zone names and the normal vehicle's 92% battery.
No mocked REST route was used in this smoke. The Vite proxy check then confirmed
the same database-backed total through the local development path.

Evidence: `operator-dashboard/test-results/frontend-week2-1795339242576-2360/`
contains `result.txt`, `compose.log`, and the live browser screenshot. Both test
projects removed their own containers, networks and PostgreSQL volume normally.
The smoke builds the backend with its existing `-DskipTests` Dockerfile; it does
not claim a fresh run of the colleagues' full Java/MCP/Android suites. Their code,
contracts, migrations, shared infrastructure files and branch heads were not edited.
