# Person 5 — Week 3: Kafka-backed MCP read model

## Baseline and objective

Implement the full Person 5 Week 3 milestone on `feature/ai-mcp-contracts`, keeping
Node and JSON persistence. On 2026-11-16 the worktree was clean, feature head
`9ae483f` was an ancestor of `integration/week-2` at `6fa162b`, and the feature
branch was fast-forwarded to that integrated baseline without losing commits.
The original 16 MCP tests passed before changes.

Week 2 supplied persisted vehicle CRUD, health, logging-only registration,
separate Kafka battery/location producers, fixture dashboard, rider demo and
draft rental adapter, MCP tools with backend cache/pagination/mock proposals,
Compose and CI. Historical verification is not new Week 3 evidence.

Objective: low-battery and availability tools query a durable Kafka projection
without calling the fleet API or primary database. Proposals remain advisory
before and after approval. Demand, OFFLINE inference, PostgreSQL migration,
live LLMs, operational execution and client features are outside scope.

## Ownership and contract gate

Person 5 owns consumer requirements, normalization, projection, tools and tests.
Persons 1/2 own production publication and shared event contracts. The user
confirmed there is no additional owner agreement; production backend event
integration must remain gated. Record the proposed shapes and outstanding
decisions in [integration-review.md](../../integration-review.md). Do not change
producer behavior or label fixture success as real producer acceptance.

- Registration needs complete query-relevant initial state and actual independent
  battery/location measurement timestamps.
- Rental, maintenance and administrative state changes need authoritative
  resulting status. All status/zone changes carry a monotonically increasing
  backend per-vehicle `stateRevision`, including changes published across topics.
- Zone information comes from the backend. Raw GPS never assigns an MCP area.
- Every supported event needs a stable ID, schema version, vehicle key and
  ordering evidence. Producer retries retain the ID.
- Telemetry orders each field independently by measurement time, then publication
  time, then event ID. This tie rule is a proposal awaiting owner agreement.
- Preserve `fleet.vehicle`, `fleet.rental`, `fleet.maintenance`.

The local backend fixture contract is `person5-week3-draft-v1`; its production
adapter is disabled by default. Opting into fixtures is not owner approval.

## Implementation sequence

1. **Durable store and normalization.** Separate the JSON store, event
   normalization, projection and Kafka client modules. Preserve proposals and
   discard legacy backend cache when creating/resetting projection data. Hold an
   exclusive writer lock for each state directory, including offline rebuilds.
   Add one pinned KafkaJS dependency and lockfile.
2. **Projection.** Store format version, vehicles (including incomplete records),
   field ordering, revision consistency claims, processed event fingerprints,
   content revision, confirmed complete snapshot and next offsets for each
   topic/partition. Serialize processing. Flush and atomically replace the JSON
   file before publishing memory changes or committing Kafka offsets.
3. **Consumption.** Disable automatic commits and batch auto-resolution. Seek
   durable offsets after each assignment rather than trusting group commits.
   Retry broker failures. Stop for storage/integrity errors without advancing
   the failed checkpoint; include topic/partition/offset diagnostics. Skip and
   checkpoint explicitly unrelated event types; malformed supported events,
   unknown supported schema versions and wrong keys fail closed.
4. **Ordering and completeness.** Identical IDs are no-ops; conflicting ID reuse
   fails integrity. Independent measurement ordering includes nanoseconds and
   deterministic ties. Status/zone ordering uses authoritative state revisions;
   contradictory equal-revision field values fail. Updates before registration
   survive and prevent incomplete totals from being exposed.
5. **Tools and readiness.** Use a snapshot-provider interface with no runtime
   REST fallback or 500-record cap. Preserve tool contracts, area mappings,
   thresholds, filtering, sorting, status translation, independent timestamps
   and `availableSince: null`. Keep cursors single-use with 15-minute expiry and
   invalidate on content revision/query/restart. `/health` is process liveness;
   `/ready` requires initialization, all-partition catch-up and integrity.
   Set `readModelAsOf` only after successful broker catch-up checks, including
   idle partitions. Before first catch-up return `READ_MODEL_UNAVAILABLE`;
   during outage serve the last complete snapshot within the requested age,
   then `STALE_READ_MODEL`. Disconnects cannot renew freshness.
6. **Startup/replay.** Configure brokers, dedicated group, state directory and
   explicit area map. Compose initializes all three topics before MCP starts;
   MCP depends on Kafka readiness. Offline rebuild holds the same lock,
   preflights retained history, clears only projection/cache/checkpoints, replays
   from zero and preserves proposals. Require full retained registration and
   subsequent event history; fail clearly when it is unavailable.
7. **Evidence.** Update CI to install the lockfile, run MCP checks and exercise
   the isolated Kafka path. Add Kafka requests/startup/rebuild instructions and
   [Week 3 verification](week-3-verification.md). Keep Week 2 evidence historical.

## Tests and acceptance

Run original MCP regressions adapted to the snapshot interface and focused tests:

- Registration, battery/location, rental, maintenance and admin state changes.
- Independent timestamps, equal-time ties, cross-topic status revisions.
- Duplicates before/after restart, conflicting IDs and contradictory revisions.
- Updates before registration; malformed input, keys and schema versions.
- Atomic storage failure; replay after persistence succeeds and commit fails.
- Unknown/unmapped areas, zones, threshold boundaries, statuses, >500 vehicles.
- Cursor invalidation/expiry/single-use, idle freshness, outage/recovery.
- Rebuild equivalence; approved/rejected proposal preservation and no execution.

Use a fresh uniquely named Compose project and volumes. The runnable consumer
smoke uses clearly identified draft backend fixtures and the real Java telemetry
producer. It verifies schema-valid tools, continued queries with backend stopped,
restart/deduplication, outage/recovery, offline replay equivalence and advisory
approval persistence. It removes only its own containers and volumes.

Final **real producer acceptance remains gated** until Persons 1/2 agree and
provide producers: register/change state through real backend and telemetry,
check both tools, stop backend and query, restart MCP, replay retained history,
compare vehicle results and confirm advisory-only proposals. Fixture-only
success establishes consumer readiness, not complete producer integration.
