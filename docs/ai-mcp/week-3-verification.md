# Person 5 Week 3 verification — 2026-11-16

## Scope and branch

Implemented on `feature/ai-mcp-contracts`. The clean feature branch at `9ae483f`
was an ancestor of `integration/week-2` (`6fa162b`); it was fast-forwarded to the
integrated Week 2 baseline before edits. Existing feature commits were retained.
No producer code, operational APIs, migrations or public MCP tool schemas were
changed. The original 16 MCP tests passed before implementation.

## Executed checks

| Check | Result |
|---|---|
| `npm ci` in `ai-mcp` | PASS; pinned KafkaJS 2.2.4 and lockfile; sole runtime dependency. |
| `npm test` in `ai-mcp` | PASS, 34 tests, zero failures/skips. Host Node 26.7.0. |
| `npm run check` in `ai-mcp` | PASS; syntax checks for source, scripts and tests. |
| `git diff --check` | PASS. |
| Docker builds / isolated Compose | PASS on final run `week3-smoke-1794831505305-25544`, including bounded-age outage assertions; Docker 29.9.2, Node 22 image, Kafka 4.1.1. |
| Hosted CI, Android and unrelated component suites | Not rerun locally for this MCP change. CI workflow updated, hosted execution not claimed. Week 2 reports remain historical. |

Automated coverage retains MCP protocol/discovery, schema examples, explicit
area mapping and errors, status translation, threshold/minimum-battery filters,
independent timestamps, nullable `availableSince`, sorted pagination, cursor
single-use/expiry/restart/query/content invalidation, planner evidence hashes,
multi-page planning and advisory decisions.

New focused coverage checks registration plus telemetry/rental/maintenance/admin
updates; independent measurement ordering and equal-time publication/ID ties
including nanoseconds; authoritative cross-topic revisions; duplicate IDs before
and after restart and conflicting reuse; incomplete records before registration;
malformed JSON/envelopes, wrong keys/topics, unsupported versions and unapproved
backend adapter; atomic replacement failure without memory/disk/checkpoint or
Kafka commit advancement; replay after persistence succeeds but commit fails;
idle broker freshness and frozen outage snapshots; catch-up on all topics;
missing history and in-flight probe races; 501 vehicles; replay equivalence and
preservation of approved/rejected proposals; exclusive writer/rebuild locking
and corrupt-file detection. Runtime tests check disabled automatic commits,
explicit nonzero durable seeks, idle partition checks and transient retries.

## Isolated Kafka evidence

Run from the repository root: `node scripts/compose-smoke.mjs`. Each invocation
uses a unique Compose project, fresh volumes, no host ports, and removes only
its own containers and volumes. Logs/results remain under `build/week3-smoke-*`.
The final completed run above demonstrated (logs and `result.txt` are under
`build/week3-smoke-1794831505305-25544`):

1. Topic initialization and healthy MCP from an empty broker, independent of
   backend health. Draft backend fixtures published as keyed Kafka messages.
2. Schema-valid low-battery/availability tools and advisory proposal approval.
3. Actual Java telemetry simulator publication consumed into the projection:
   battery 74%, location `(48.2104, 16.3600)`, independent measurement timestamps.
4. Backend container stopped; tool calls continued with identical results.
5. MCP graceful restart, persisted checkpoints, seek/replayed duplicate IDs,
   unchanged vehicle results and persisted approved proposal.
6. Broker stopped: process health remained up and readiness failed. Bounded-age
   cached queries succeeded with an unchanged `readModelAsOf`; an age limit of
   zero returned `STALE_READ_MODEL`. Broker restart recovered consumption and
   readiness without a backend.
7. Offline rebuild from retained Kafka history with the same state volume;
   identical availability vehicle results, maintenance status and approved
   advisory proposal preserved after MCP restarted.

The consumer is explicitly stopped on shutdown; Compose grants 60 seconds for
graceful stop so the writer lock is released before restart. Unclean kills still
require verifying no writer remains before removing a stale lock. The smoke
enables the draft contract only in its isolated environment.

## Production integration gate

**Consumer readiness is verified; real backend producer acceptance is not.**
The user confirmed no further owner agreement exists. Backend registration is
still logged, lacks actual measurement timestamps/state revision, and is not
published to Kafka. Rental/maintenance/admin publication and authoritative zone
updates are also outstanding. Persons 1/2 must agree the handoff in
[integration-review.md](../../integration-review.md), implement complete event
coverage and confirm the proposed telemetry tie rule before real integration.
No affected-owner agreement is fabricated by the fixture switch.

Real acceptance must repeat registration/state changes through actual producers,
both schema-valid tools, backend shutdown, MCP restart/duplicate safety and
rebuild equivalence with approved/rejected proposal preservation. Complete
retained registration and subsequent state history is mandatory. Local demo
topics retain history indefinitely; existing topic configuration needs checking.
Missing log starts or registrations fail replay; a zero log start alone cannot
prove operators preserved complete event history.

JSON persistence remains appropriate for this demonstrator: each event rewrites
the file, deduplication history is retained, and one writer owns a state directory.
There is no distributed writer or power-loss guarantee across all host storage
configurations. No REST cache bootstrap/fallback, operational proposal execution,
OFFLINE inference, demand implementation, live LLM or client work was introduced.

Kafka commit/seek behavior follows the client documentation:
[KafkaJS consumption and external offsets](https://kafka.js.org/docs/consuming),
[broker offset queries](https://kafka.js.org/docs/admin), and
[consumer instrumentation](https://kafka.js.org/docs/instrumentation-events).
