# Week 2 integration verification

Verified on 2026-11-15 from `integration/week-2`. Readiness is **blocked** until
Android and hosted CI checks pass and PR delivery is confirmed.

## Source history

Remote refs were refreshed successfully before integration. `origin/main` was
`d3c03e6fca28d899d3ba97ba11a2bf4ea47f305b`; a subsequent remote lookup confirmed
the same main commit. The working tree was initially clean.

| Source | Commit |
|---|---|
| feature/fleet-domain-model | `0b00586fb88f7991a538b212e6c6a914d79e242a` |
| feature/event-telemetry | `4c3333c45d94d1e21ada1d0ffc02bb50fd1d695b` |
| feature/ai-mcp-contracts | `9ae483f2dc7caeeb3b46e1e93d542ff77074ac8e` |
| rider-mobile-app | `0a04af261d98eeebd9490b83cf3162a7d272327c` |
| feature/frontend-week-1 (already included) | `7493da1dd23d9d47595e9953b66ff385da2bfb0d` |
| feature/ai-mcp-week-2 (through reconciled MCP branch) | `6d7d28260a203deef00b737719c3afdbcb0ec324` |

Branches were integrated in the approved order, preserving history. Fleet was a
fast-forward; subsequent branches have merge commits. Only `compose.yaml`
conflicted: both resolutions combined contributions, retaining PostgreSQL,
backend, Kafka, dashboard, schema viewer, MCP and their volumes. No source branch
was deleted; main was not modified. All listed sources are ancestors of the
integration result.

## Results

| Check | Result / evidence |
|---|---|
| Backend Maven verify | PASS: 24 unit/web tests and 29 PostgreSQL/Testcontainers integration tests, no failures, errors or skips. Reports: `fleet-management-service/target/{surefire,failsafe}-reports`. |
| Telemetry packaging | PASS: Maven package in telemetry Docker build. |
| MCP | PASS: all 16 tests and `npm run check`. |
| Dashboard | PASS: `npm ci`, `npm run build`, all 8 Playwright tests, final exit 0 using explicit local `PLAYWRIGHT_CHANNEL=msedge`. CI installs Chromium and leaves channel unset. |
| Android build/unit tests | BLOCKED: Gradle starts, but no Android SDK is configured or installed at the standard local path. `assembleDebug testDebugUnitTest -Prider.backendUrl=` fails with SDK location not found. |
| Android demo instrumentation | BLOCKED: no SDK/emulator available. CI provisions API 35 and runs demo-mode instrumentation with an empty backend URL. |
| Compose configuration | PASS: `docker compose --env-file .env.example config --quiet`. |
| All five Compose images | PASS: backend, telemetry, MCP, dashboard, schema viewer built. |
| Combined smoke | PASS twice: `node scripts/compose-smoke.mjs`; final project `week2-smoke-1794766886633-44640`, results/logs under `build/week2-smoke-1794766886633-44640/`. Final rerun explicitly pinned the smoke backend URL/mappings against inherited shell overrides. |

The combined smoke used fresh project-scoped volumes and no published host
ports. PostgreSQL and Kafka became healthy, backend started with development
zones, MCP health/readiness passed, and dashboard/schema-viewer returned HTTP
success. Two synthetic vehicles were registered through the real backend.
MCP low-battery and availability filters, direct vehicle IDs, independent
measurement timestamps and null `availableSince` matched backend DTOs. The mock
planner created a proposal; advisory approval did not change fleet state. Both
the approved proposal and unchanged fleet were verified after MCP restart.

The explicitly enabled simulator published four fresh records on the fresh
broker. Every record validated against `telemetry.schema.json`, used vehicle ID
as its Kafka key and had a unique `evt_` ID: two battery and two location events.
Fleet state remained unchanged after publication. Logs were captured before
cleanup. Cleanup removed only the unique smoke project's containers and volumes;
existing developer volumes were preserved.

## Repeat locally

```bash
cd ai-mcp && npm test && npm run check
cd ../operator-dashboard && npm ci && npx playwright install chromium
npm run build && npm test
cd ../fleet-management-service && mvn -B verify
cd ../telemetry-ingestion-service && mvn -B package
cd ../rider-app && ./gradlew assembleDebug testDebugUnitTest -Prider.backendUrl=
./gradlew connectedDebugAndroidTest -Prider.backendUrl=
cd .. && node scripts/compose-smoke.mjs
```

On Windows use `gradlew.bat`. Instrumentation needs a running emulator. Modern
Docker requires an explicit compatible API for this backend's docker-java
version: set `JAVA_TOOL_OPTIONS=-Dapi.version=1.44` for Maven (included in CI).
The initial attempt with only `DOCKER_API_VERSION` failed Docker detection; the
JVM property retry passed all tests. Local verification used Maven's JDK 17
container with the Docker socket mounted and
`TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`. An initial sandboxed browser
run completed assertions but hung in teardown; its task processes were stopped
and a normal-permission rerun passed and exited cleanly.

## Deferred functionality and delivery gates

Nearby/rental/return endpoints are unimplemented. Rider demo mode stays the
default; dashboard fixtures remain its data source. No fleet telemetry consumer,
MCP Kafka projection, demand, OFFLINE inference or operational proposal execution
was introduced. Backend application code and applied migrations are unchanged.

GitHub CLI is unavailable and a noninteractive credential lookup did not provide
an authenticated GitHub API credential. The approved push was attempted with
`git push --set-upstream origin integration/week-2` and failed because Git could
not read a GitHub username with prompts disabled. The branch is committed locally
but **not pushed**, and no PR was created. Hosted CI and GitHub mergeability cannot
be claimed verified. GitHub authentication is required to finish delivery.

After signing in, push the branch and use this
[PR creation link](https://github.com/Ada-221/Bike-management-system/compare/main...integration/week-2?expand=1)
with the prepared [PR description](week-2-pr.md). Keep the PR draft until Android
and hosted checks pass. Fetch main again, merge it into the integration branch if
it advanced, and rerun affected checks before declaring readiness.
