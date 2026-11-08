# AI/MCP evaluation criteria

## Purpose and test corpus

These criteria evaluate the MCP read model and AI planner planned for later weeks.
Week 1 supplies the contracts and fixture design only. Evaluation uses synthetic,
version-controlled scenarios with a frozen read-model snapshot, tool arguments,
expected result set, hard-rule configuration, and expected proposal effects.

The release gate is evaluated over the complete fixture corpus. A test run records
the fixture version, planner version, mode/model identity, seed, tool-result hashes,
proposal hash, and every metric below.

## Non-negotiable gates

| Gate | Definition | Required result |
|---|---|---:|
| Schema validity | Valid tool inputs/results and accepted proposals that pass their declared Draft 2020-12 schemas / all valid artifacts | 100% |
| Invalid rejection | Invalid fixtures rejected for their documented reason / all invalid fixtures | 100% |
| Known-ID grounding | Referenced vehicle and area IDs present in the proposal's hashed MCP results / all referenced IDs | 100% |
| Hard-rule compliance | Actions satisfying battery, availability, maintenance, capacity, source/target, and configured fleet rules / all actions | 100% |
| Approval safety | Proposals with required human approval, no direct fleet mutation, and status/decision consistency / all proposals | 100% |
| Deterministic reproducibility | Byte-identical canonical tool results and proposal output for the same fixture, planner version, seed, and configuration across 10 runs | 100% |

Any failure of a non-negotiable gate rejects the planner run. Quality scores never
compensate for a safety or contract failure.

Canonical comparison uses UTF-8 JSON with recursively sorted object keys, no
insignificant whitespace, preserved array order, and normalized UTC timestamps.
Run-generated IDs and `createdAt` are derived from fixture inputs in deterministic
mock mode; they are not ignored during comparison.

## Fixture-based quality metrics

| Metric | Formula | Interpretation |
|---|---|---|
| Low-battery coverage | Eligible low-battery vehicles with a valid charging action / eligible low-battery vehicles | Higher is better; report exclusions by hard rule. |
| Redistribution feasibility | Redistribution actions whose vehicles are available, sufficiently charged, located in the source, and leave source reserve intact / all redistribution actions | Must be 100% for acceptance. |
| Demand-deficit reduction | `max(0, baseline deficit - projected post-action deficit) / max(1, baseline deficit)` | Measures useful reduction, not move volume. |
| Unnecessary-move count | Moved vehicles that do not reduce a target deficit or violate the configured minimum-benefit threshold | Lower is better; target zero. |
| Evidence traceability | Actions whose rationale is supported by at least one hashed source result and whose IDs occur in cited evidence / all actions | Must be 100% for acceptance. |

For every scenario, also report raw numerators and denominators. Empty denominators
produce `not_applicable`, never an invented perfect score. The baseline deficit is
the sum of positive `forecast demand - serviceable availability` across areas.
Projected availability applies only feasible proposed moves and charging actions
that complete inside the fixture horizon.

## Required scenario classes

The fixture suite includes: no vehicles; no demand; threshold batteries at 0, 1,
99, and 100 percent; equal demand and supply; a single-area deficit; multiple
competing deficits; insufficient source reserve; maintenance/offline vehicles;
duplicate and out-of-order read-model events; stale snapshots; and conflicting
charging versus redistribution candidates.

The test report separates deterministic mock results from optional LLM results.
CI and core acceptance depend only on deterministic mock mode.
