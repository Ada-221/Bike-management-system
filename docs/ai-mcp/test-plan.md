# AI/MCP test plan

## Scope

This plan covers the three read-only MCP tools, read-model boundary behavior, AI
proposal validation, planner failure handling, approval safety, and audit evidence.
It is a design for later implementation; Week 1 has no runtime MCP or planner.

Tests use synthetic data and a fixed UTC clock. Contract tests run against the
schemas in `contracts/ai-mcp/v1/schemas`. Integration tests run the future MCP
server against its own seeded read-model store, never the fleet service database.

## Contract and protocol tests

| ID | Test | Expected result |
|---|---|---|
| MCP-C01 | Validate every schema with a Draft 2020-12 meta-schema. | All schemas valid. |
| MCP-C02 | Parse every JSON artifact and validate each valid call/result/proposal against its declared schema. | 100% pass. |
| MCP-C03 | Validate every invalid fixture using its manifest mapping. | Every fixture fails for the documented keyword/path. |
| MCP-C04 | Add an undocumented property at each object level. | Rejected by `additionalProperties: false`. |
| MCP-C05 | Exercise percentages at -1, 0, 100, 101 and coordinates around WGS84 bounds. | Only inclusive valid bounds pass. |
| MCP-C06 | Exercise page sizes 0, 1, 100, 101; malformed/expired cursors; `hasMore` cursor consistency. | Boundary values and structured errors match contracts. |
| MCP-C07 | Use offsets, missing zones, malformed dates, and UTC `Z` timestamps. | Only valid UTC `Z` timestamps pass. |
| MCP-C08 | Call all tools twice against the same snapshot and arguments. | Responses are identical and no state changes occur. |
| MCP-C09 | Inspect `tools/list` annotations and ordering. | Exactly three tools, fixed order, read-only/non-destructive/idempotent. |
| MCP-C10 | Exercise `/mcp` POST metadata, headers, `resultType`, JSON/SSE responses, and absent session/initialize behavior. | Matches MCP 2026-07-28 profile. |
| MCP-C11 | Compare `structuredContent` with parsed mirrored text. | Deep equality; structured content validates. |
| MCP-C12 | Send a mismatched/unsupported protocol version. | Defined JSON-RPC header/version error; no tool execution. |

## Tool and read-model behavior

| ID | Test | Expected result |
|---|---|---|
| MCP-T01 | Low-battery data below, equal to, and above the requested threshold. | Inclusive filtering; battery then vehicle-ID order. |
| MCP-T02 | Availability with battery filter and mixed statuses. | Only available, in-area, qualifying vehicles; vehicle-ID order. |
| MCP-T03 | Demand for one/many areas, allowed bucket sizes, empty buckets, and seven-day boundary. | Correct aggregates; area then bucket-time order. |
| MCP-T04 | Empty read model and valid empty queries. | Successful empty arrays/counts, not errors. |
| MCP-T05 | Snapshot age equal to and one second beyond requested maximum. | Boundary accepted; stale data returns `STALE_READ_MODEL`. |
| MCP-T06 | Unknown stable area IDs. | `UNKNOWN_AREA`; no partial result. |
| MCP-T07 | Duplicate Kafka event IDs and repeated/out-of-order updates. | Idempotent projection and deterministic latest valid state. |
| MCP-T08 | Multi-page query while newer events arrive. | Cursor remains bound to the original snapshot; no gaps/duplicates. |
| MCP-T09 | Corrupt projection or unavailable store. | Retryable structured `READ_MODEL_UNAVAILABLE`; no fabricated data. |
| MCP-T10 | Aggregates inconsistent with source observations. | Consistency check fails and defect is observable. |

## Planner and proposal behavior

| ID | Test | Expected result |
|---|---|---|
| AI-P01 | Valid charging, redistribution, and combined proposals. | Schema-valid discriminated actions with required evidence/warnings. |
| AI-P02 | Unknown vehicle/area IDs or evidence absent from hashed tool results. | Proposal rejected before persistence/visibility. |
| AI-P03 | Duplicate vehicle, same source/target, unavailable/maintenance vehicle, low-battery move, or source reserve breach. | Hard-rule validation rejects unsafe action/proposal. |
| AI-P04 | Malformed JSON, extra properties, wrong action discriminator, out-of-range value, or status/decision mismatch. | Schema validation rejects output and records a safe failure. |
| AI-P05 | Stale or empty MCP data. | No unsafe proposal; run records stale/no-action reason. |
| AI-P06 | LLM timeout, authentication failure, rate limit, invalid response, or endpoint unavailable. | Bounded retries where appropriate; no fleet change; deterministic mock remains available. |
| AI-P07 | Same deterministic fixture, configuration, planner version, fixed clock, and seed across 10 runs. | Byte-identical canonical proposal and tool results. |
| AI-P08 | Proposed action conflicts with a hard business rule. | Hard rule wins; action rejected with auditable reason. |
| AI-P09 | Attempt to set auto-execution or direct mutation indicators. | Schema/safety validation rejects proposal. |
| AI-P10 | Pending, approved, and rejected lifecycle transitions. | Only an authenticated human decision changes proposal status; decision is immutable and traceable. |
| AI-P11 | Replay approval/rejection request with same idempotency key. | One decision/event; identical response. Conflicting replay is rejected. |
| AI-P12 | Approved proposal without a separately authorized fleet command. | Fleet state remains unchanged. |

## Audit and traceability

For every planner run, verify persistence and log correlation of `runId`,
`proposalId`, correlation ID, creation time, snapshot ID/as-of time, each tool name
and result hash, planner/model identity, mode, seed when applicable, prompt template
version, raw output hash, validation outcomes, proposal status, and human decision
identity/time/comment. Recompute hashes from retained synthetic artifacts and fail
on mismatch.

Trace one action end to end: read-model source events -> deterministic MCP result ->
snapshot hash -> proposal evidence -> validation decision -> operator decision.
Logs must not contain secrets, credentials, private data, or unredacted external
model payloads. Audit records are append-only; duplicate delivery must not create
duplicate decisions or events.

## Execution stages and exit criteria

1. Pre-commit: JSON parsing, schema meta-validation, examples, invalid fixtures.
2. Component CI: projection tests, tool filtering/order/pagination, planner rules.
3. Integration CI: Kafka rebuild, MCP protocol, persistence, mock planner lifecycle.
4. Release: full fixture evaluation and end-to-end approval audit trace.

Exit requires every non-negotiable evaluation gate, all high-priority tests above,
and no unresolved critical/high safety defect. Optional live-LLM quality is reported
separately and cannot block deterministic core operation.

## Week 2 adapter acceptance

Historical adapter evidence; Week 3 uses the snapshot-provider interface and
Kafka path described in [the implementation plan](week-3-implementation-plan.md).
Focused runnable coverage is in `ai-mcp/tests/projection.test.js` and
`ai-mcp/tests/consumer.test.js`; `node scripts/compose-smoke.mjs` exercises
Kafka/restart/outage/rebuild with draft backend fixtures and real Java telemetry.
Real backend producer acceptance remains gated on Persons 1/2 agreement.

Run `npm.cmd test --prefix ai-mcp` and `npm.cmd run check --prefix ai-mcp`.
Tests cover real DTO names and prefixed IDs, retained API paths, explicit zone
mappings, null availableSince, status/battery filters, backend errors, cursor
continuation/rejection, the 500-record cap, actual tool-result evidence hashes,
and proposal persistence and approval without fleet changes. Docker and backend
smoke-test results are recorded in `week-2-verification.md`.
