# Operator dashboard

The Week 2 UI reads backend vehicles and fleet totals by default through
`/api/v1/vehicles?limit=500` and `/api/v1/fleet/status`. Select **Demo** in the
data-source control for a disconnected synthetic demonstration. Failed API
requests never silently fall back to demo data.

In Compose, the team's existing dashboard proxy serves the fleet API on the same
origin. Vite development and production-preview servers now provide the same
route, targeting `http://127.0.0.1:8080` by default.
Playwright uses its installed Chromium by default (`npx playwright install chromium`).
For local Edge, set `PLAYWRIGHT_CHANNEL=msedge` (PowerShell:
`$env:PLAYWRIGHT_CHANNEL='msedge'`) before `npm test`. CI installs Chromium.

Person 3's Week 2 frontend builds on the Week 1 React/TypeScript skeleton and the
integrated backend at `bac920b`. Exact dependency versions remain pinned in
`package.json` and `package-lock.json`; no runtime dependency was added for the
API client. The geographic map provider is still a later decision.

## Run locally

Prerequisites: Node.js 22.12+ (even-numbered LTS recommended), npm, and Git.
Validated locally with Node.js 24.22.0 and npm 11.19.1.

```powershell
cd operator-dashboard
npm.cmd ci
npm.cmd run dev
```

Open the local URL printed by Vite (normally `http://127.0.0.1:5173`). On macOS
and Linux use `npm` instead of `npm.cmd`. On Windows, `.cmd` avoids the
PowerShell script-policy restriction without changing system settings.

**Backend mode requires the fleet service.** From the repository root, the existing
team Compose configuration can start its dependencies and backend:

```powershell
docker compose up -d --build --wait postgres kafka kafka-topics fleet-management-service
```

Keep existing developer database volumes. Follow the backend's documented upgrade
procedure if using an older database. This command uses the shared stack as-is;
the frontend does not change Compose, migrations, or backend code.

To use a different running backend, set the proxy target before starting Vite:

```powershell
$env:FLEET_API_TARGET = 'http://127.0.0.1:8080'
npm.cmd run dev
```

Alternatively copy this folder's `.env.example` to `.env.local` and edit the
target. Restart Vite after changes. The setting is server-side proxy configuration,
not a credential or a browser-bundled backend URL. `npm.cmd run preview` uses it
as well. The Docker build retains the team's Nginx configuration and resolves
the fleet service internally; it does not use this Vite setting.

For the existing Docker dashboard, run `docker compose up -d --build
operator-dashboard` from the repository root and open `http://localhost:8081`.
For an offline presentation select **Demo** after starting Vite. Demo mode needs
no backend, Docker, Kafka, map account, or LLM.

## Validate

```powershell
npm.cmd run format:check
npm.cmd run build
npm.cmd test
```

The build includes strict TypeScript checking and produces ignored `dist/` output.
Tests run against that production build, so run the build first after each change.
Port 4173 must be free. Playwright starts and stops its own preview server.

The suite uses installed Chromium by default, preserving the team's CI behavior.
For local Edge run `$env:PLAYWRIGHT_CHANNEL = 'msedge'` first. To use Chromium,
install it with `npx playwright install chromium`. Tests run headlessly and cover
the client boundary and browser integration with mocked REST responses. Screenshots
and failure traces are in ignored `test-results/browser/`.

The separate real-backend test requires Docker Desktop and a test browser:

```powershell
$env:PLAYWRIGHT_CHANNEL = 'msedge'
npm.cmd run test:live
```

This builds the actual backend and dashboard images and starts PostgreSQL using
the frontend-owned `compose.smoke.yaml`. A unique Compose project and fresh
database volume keep developer data separate; only an ephemeral loopback port
is exposed. The browser checks an empty fleet, registers synthetic test vehicles
through the real proxy/API, refreshes, and verifies the list and fleet totals.
The test stack disables Kafka because this check covers frontend REST integration.
It also loads the actual Vite configuration and checks its development proxy
against the running API. The runner captures logs and then removes only its own
containers and volume.
Evidence is saved under ignored `test-results/frontend-week2-*/`.

`npm.cmd run preview` serves the production build. `npm.cmd run format` formats
the dashboard's source and documentation. These checks do not certify the complete
distributed system, Kafka integration, a real map, SSE, full accessibility
compliance, or cross-browser support.

## Week 2 contents

- Overview, Fleet, and Proposals navigation inside a responsive operator shell.
- Backend vehicle list and fleet summary; an explicit demo source for offline use.
- Independent loading, empty, error/retry, and stale-after-refresh states.
- Manual refresh, ten-second request timeout, cancellation on source changes,
  response-shape validation, and correlation IDs on GET requests.
- Illustrative map layout; no geographic map implementation.
- Non-operational proposal layout with disabled approval/rejection controls.
- Accessible names, status text, focus indicators, keyboard navigation, and a
  skip link. Narrow screens scroll the table within its own region.
- Read-only vehicle and summary types matching the implemented fleet controllers,
  including prefixed IDs and independent battery/location measurement timestamps.

```text
src/
  App.tsx                       Navigation and page skeletons
  api/fleet.ts                  Read-only REST client and boundary validation
  hooks/useFleetResource.ts     Independent data loading and cancellation
  components/FleetSnapshot.tsx   Shared vehicle snapshot from supplied data
  contracts/fleet.ts            Implemented backend read shapes/source revision
  data/sampleFleet.ts           Explicit demo source
  main.tsx                      React entry point
  styles.css                    Layout, components, mobile rules
tests/api.spec.ts               API-client contract/error/cancellation checks
tests/dashboard.spec.ts         Mocked-API browser tests
tests/live-fleet.spec.ts        Real Docker/backend/proxy browser smoke
```

Wireframes, scope, milestones, compatibility decisions, and verification results:
[Week 1 handoff](../docs/frontend/week-1.md) and
[Week 2 handoff](../docs/frontend/week-2.md).

## Planned integration boundaries

Week 2 connects the list and summary so the client path is verifiable. Week 3 adds
availability/battery/maintenance filters and vehicle details. Week 4 adds SSE and
proposal review. Week 5 completes resilience and polish. No EventSource,
proposal persistence, operational actions, authentication, or router dependency
was introduced. Navigation is local state and resets to Overview on reload.

The client reads only vehicles and fleet status. It does not call the draft
`/zones` endpoint: names come from `/fleet/status`; absent names fall back to the
actual zone ID and null zones display as unassigned. Counts come from the full
summary, not the list capped at 500. A full list page is explicitly labelled as
potentially incomplete; the backend has no pagination API yet. Active rentals
are independent of the number of vehicles marked RENTED after a mid-trip fault.

Proposal controls remain disabled. The integrated rule is advisory approval only:
a decision must not automatically execute fleet operations. Proposal REST
integration remains a Week 4 milestone, not part of this change.
