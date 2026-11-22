# Person 3 — Week 3 frontend handoff

## Scope and delivery

The implementation plan assigns Person 3 **real vehicle data; availability,
battery and maintenance filters; and a vehicle detail view**. This local milestone
extends the Week 2 API integration without changing backend code or shared files.

- Base: `4fbe17f`, the tested Week 2 frontend commit published on
  `feature/frontend-week-2`. It already includes the latest reviewed integrated
  fleet backend at `bac920b`.
- Delivery branch: `feature/frontend-week-3`. Week 3 is delivered in its own
  frontend commit, separate from the published Week 2 milestone.
- Changes are confined to `operator-dashboard/` and `docs/frontend/`.
  The existing untracked lab presentation is untouched.

## Implemented behavior

| Requirement | Result |
| --- | --- |
| Real vehicle data | Retain backend-by-default mode with an explicit disconnected demo option; no silent fixture fallback |
| Availability filter | Vehicle status selector offers All statuses, Available, On a ride and Needs maintenance |
| Maintenance filter | Needs maintenance sends `status=MAINTENANCE_REQUIRED` using the existing vehicle-list API |
| Battery filter | Optional inclusive upper bound, sent as `maxBattery`; 0 and 100 are valid; fractional/out-of-range values are rejected |
| Combined filters | Apply submits the selected state and battery limit together; Clear returns to the unfiltered list |
| Vehicle detail view | Selecting a vehicle label opens a read-only dialog loaded from the specific vehicle endpoint |
| Maintenance visibility | The dialog independently retrieves unresolved records for that vehicle, showing reason, status, fault code, description and opening time |
| Telemetry visibility | Show coordinates and battery/location timestamps independently; manual Refresh retrieves new snapshots |

Filters are shared between Overview and Fleet, and retained across navigation.
The form initializes from applied values on remount. Editing fields does not
request data until Apply is pressed. Switching Backend/Demo clears filters and
closes details so data from one source cannot be mislabelled as the other.
The full-fleet indicators are never recomputed from a filtered/capped list.
Filtered emptiness displays "No matching vehicles" rather than an API failure
or an empty-fleet claim.

The detail dialog shows the current vehicle ID, label, type, operational status,
rentability, battery, area, coordinates, battery measurement time, location
measurement time, latest telemetry and vehicle update time. Missing measurement
times display "Not reported" rather than borrowing another field's timestamp.
Names come from the existing summary; unavailable names fall back to the zone ID.

Vehicle details and maintenance records have independent loading/error/retry
states. A maintenance failure does not hide successful vehicle details. A 404
is shown as a missing-resource error instead of displaying another vehicle.
Refresh details reloads the dialog; the main Refresh reloads the list/summary.
Each snapshot is independent. Closing details restores keyboard focus and aborts
outstanding requests. The native dialog supports Escape and scrolls on mobile.

## Existing interfaces used

- `GET /api/v1/vehicles?limit=500&status=...&maxBattery=...`, omitting unset filters.
- `GET /api/v1/fleet/status` for global counts and zone display names.
- `GET /api/v1/vehicles/{encodedVehicleId}` for current details.
- `GET /api/v1/maintenance?vehicleId=...&limit=500` for unresolved records. The
  implemented backend excludes RESOLVED by default; no fictional history endpoint
  or query option is introduced.

The client still uses documented backend enums and opaque prefixed IDs.
Responses are validated before rendering; mismatched detail IDs or maintenance
records for another vehicle are rejected. State-independent timestamps and
nullable fields preserve the current Java DTO behavior. API errors, request
timeouts, source cancellation, same-origin proxies and correlation headers from
Week 2 are retained. No package dependency was added.

The server filters before applying its maximum 500-result limit. A full result
page remains labelled as potentially incomplete, because no pagination contract
exists. Open maintenance records are similarly capped. There is no invented
pagination or guarantee of a complete large-fleet listing.

## Boundaries

- The application sends read requests only. It does not register/rent/return
  vehicles, change maintenance, or execute proposals.
- Proposal controls remain disabled and advisory-only semantics are preserved.
- Refresh is manual. SSE, automatic map updates, a real map provider and proposal
  integration remain Week 4 or later work.
- A faulted vehicle can have an active rental; the global active-rental count
  remains the backend's `activeRentals`, not the RENTED vehicle count.
- Historical Week 1/2 handoffs remain evidence of those earlier milestones.

## Verification approach

The regular suite retains Week 2 checks and adds combined and cleared filters,
battery bounds, no-match results, applied-state persistence, API path/ID safety,
detail/maintenance errors, refreshed measurements, demo parity, Escape/focus and
a narrow-screen detail dialog.

The frontend-owned smoke stack uses fresh PostgreSQL and Kafka volumes, the
unmodified fleet backend and telemetry simulator, and the actual Docker/Nginx
dashboard build. It has a unique Compose project name and only an ephemeral
loopback dashboard port. Shared Compose files and developer data are untouched.

The live test checks empty and populated data, server-side filtering and detail
records, then runs the actual Java simulator against a registered vehicle. It
waits for the resulting 74% battery/location update through Kafka, refreshes the
dashboard and checks both list and detail data. Synthetic rental/fault fixtures
created through the real REST API then exercise a faulted active trip and the
independent active-rental count. These writes exist only in the disposable test
database; no browser application write control was added.

The runner also checks Vite's development proxy and captures logs/screenshots
before removing only its own containers, networks and volumes. It does not claim
MCP accuracy/rebuild behavior, Android compatibility, broker-failure resilience,
full accessibility certification or a hosted CI run.

Commands are unchanged: `npm.cmd run format:check`, `npm.cmd run build`,
`npm.cmd test` and `npm.cmd run test:live` from `operator-dashboard`.
For local Edge set `$env:PLAYWRIGHT_CHANNEL = 'msedge'`. CI's Chromium default
is preserved.

### Executed checks — 22 November 2026

| Check | Result |
| --- | --- |
| Strict application TypeScript check and Vite production build | Passed locally and in the dashboard Docker image |
| API-client and mocked-REST browser suite | 37 passed, no failures/skips, 14.2 seconds; 16 API-client and 21 browser checks |
| Real backend, PostgreSQL, Kafka, telemetry and Nginx browser test | Passed in 15.6 seconds, including real filtered API responses, maintenance detail records, Java telemetry and a faulted active trip |
| Vite development proxy | Passed against the same real fleet API |
| Formatting and smoke-script syntax | `npm.cmd run format:check` and `node --check scripts/live-smoke.mjs` passed |
| Mobile dialog review | 320px screenshot reviewed; automated dialog sizing, scrolling, Escape and focus restoration checks passed |
| Scope and whitespace | No changes outside frontend code/docs; `git diff --check` passed |

The live run used project `frontend-week3-1795340247644-15320`. The real backend
served an empty fleet and then two test vehicles. Combined status/battery filters
produced both a no-match result and the low-battery maintenance vehicle; details
showed its actual 12% battery and open LOW_BATTERY record. The Java simulator then
published battery/location events for the other vehicle. The test waited for 74%
in the REST response, refreshed the UI, and verified both 74% and latitude
48.2104 in the displayed list/details.

The test subsequently started a synthetic rental and reported a fault using the
real REST API. The UI reported full-fleet counts `2 / 0 / 1 / 2` for total,
available, active rentals and maintenance, and showed the actual `BRAKE_TEST`
fault record in details. This verifies that an active rental is not inferred from
the vehicle's RENTED status. The Vite proxy retrieved the same two-vehicle fleet.

Logs, `result.txt` and the live screenshot are in ignored
`operator-dashboard/test-results/frontend-week3-1795340247644-15320/`.
The runner removed only that project's containers, network and both volumes.
No backend, simulator, shared contract, migration, shared Compose or CI file was
edited. The backend image's existing build skips its Java suite; the frontend
integration result does not claim rerunning all colleague-owned test suites.
