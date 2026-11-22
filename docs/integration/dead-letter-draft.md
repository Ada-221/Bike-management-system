# Draft: dead-letter policy for `fleet.vehicle` (fleet consumer)

Status: DRAFT, implemented behind `fleet.kafka.dead-letter-enabled` (default `false`).
`integration-review.md` was not in the repository copy I worked from, so this note stands alone;
please link or merge it there. It answers the open Q3 in `docs/backend/domain-model.md`.

Problem: a malformed record made the consumer retry forever (`FixedBackOff(5000, unlimited)`) and
blocked every later record on that partition.

Proposal:

| Failure | Handling |
|---|---|
| Validation / conflicting duplicate (`IllegalArgumentException`) | No retry. Publish to `fleet.vehicle.DLT` (same key and value), continue. |
| Anything else (database, Kafka, lock timeouts) | Retry every 5 s without limit, partition stays blocked. |

Open points for the owners: DLT retention (compose uses `retention.ms=-1`), who looks at the DLT
and how a corrected record is replayed, whether transient retry should be bounded, and whether
telemetry-ingestion must be told about rejected records. Dropping records is not done when the
flag is off.
