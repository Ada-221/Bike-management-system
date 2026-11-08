# Operator dashboard

Person 3's Week 1 React/TypeScript skeleton for the CEBP operator dashboard.
This is a local academic demonstrator with fixed synthetic data, not a connected
fleet application. React and TypeScript are the implementation-plan baseline;
Vite is the proposed frontend build tool. The team still needs to review the
version freeze and map provider. Exact package versions are in `package.json`
and `package-lock.json`.

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

No environment variables, secrets, backend, Docker, Kafka, map account, or LLM
are needed. No external fonts, images, tiles, or API requests are used.

## Validate

```powershell
npm.cmd run format:check
npm.cmd run build
npm.cmd test
```

The build includes strict TypeScript checking and produces ignored `dist/` output.
Tests run against that production build, so run the build first after each change.
Port 4173 must be free. Playwright starts and stops its own preview server.

The browser suite uses installed Microsoft Edge (`channel: 'msedge'`) in headless
mode; it does not download browsers. Install Edge before testing on another
machine, or change the test channel as a separately reviewed tooling decision.
Tests cover navigation, keyboard access, sample data invariants, disabled proposal
actions, absence of operational/external requests, runtime exceptions on overview,
and layouts at 1440, 390, and 320 pixels. Screenshots and failure traces are in
ignored `test-results/`.

`npm.cmd run preview` serves the production build. `npm.cmd run format` formats
the dashboard's source and documentation. These checks do not validate backend
behavior, a real map, SSE, full accessibility compliance, or cross-browser support.

## Week 1 contents

- Overview, Fleet, and Proposals navigation inside a responsive operator shell.
- An explicitly synthetic fleet snapshot and overview indicators.
- Illustrative map layout; no geographic map implementation.
- Non-operational proposal layout with disabled approval/rejection controls.
- Accessible names, status text, focus indicators, keyboard navigation, and a
  skip link. Narrow screens scroll the table within its own region.
- A read-only `Vehicle`/`Zone` type subset matching Person 1's draft field names.

```text
src/
  App.tsx                       Navigation and page skeletons
  components/FleetSnapshot.tsx   Shared static vehicle snapshot
  contracts/fleet.ts            Backend draft read shapes and source revision
  data/sampleFleet.ts           Deterministic synthetic fixtures
  main.tsx                      React entry point
  styles.css                    Layout, components, mobile rules
tests/dashboard.spec.ts         Contract-fixture and browser checks
```

Wireframes, scope, milestones, compatibility decisions, and verification results:
[Week 1 handoff](../docs/frontend/week-1.md).

## Planned integration boundaries

Week 2 adds the HTTP client and backend-backed list using documented REST
endpoints. Week 3 adds real data, filters, and details. Week 4 adds SSE and proposal
review. Week 5 completes resilience and polish. The skeleton contains no fetch,
EventSource, proposal persistence, operational actions, authentication, or router
dependency. Navigation is local component state and resets to Overview on reload.

Proposal schemas are intentionally not implemented or silently translated: the
backend and AI/MCP branches disagree on IDs, states, actions, and execution
semantics. Those owners must agree the transport contract before integration.
