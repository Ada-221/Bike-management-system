# Person 5 Week 1 traceability

| Implementation-plan requirement | Delivered artifact |
|---|---|
| Define MCP tool contracts | `contracts/ai-mcp/v1/mcp-tools.json` and six standalone schemas |
| Define AI proposal schema | `contracts/ai-mcp/v1/schemas/ai-proposal.schema.json` |
| Define evaluation criteria | `docs/ai-mcp/evaluation-criteria.md` |
| Define test plan | `docs/ai-mcp/test-plan.md` |
| Version and review contracts before implementation | Versioned `v1` layout, `schemaVersion: "1.0"`, examples, invalid fixtures |
| Planner accesses operations through MCP only | Proposal snapshot/evidence references only the three MCP tools |
| Planner creates proposals only | Mandatory human approval and `operationalStateChanged: false` |
| Stable cross-service IDs and UTC | ID patterns and UTC schema definitions in every relevant contract |
| External LLM not required for CI | Deterministic mock identity/seed contract and reproducibility gate |
| Validate malformed or unsafe output | Proposal schema, invalid fixture, hard-rule and approval-safety test cases |
| Audit planner inputs/output/status | Snapshot source hashes, model identity, output hash, validation and decision fields |

This milestone intentionally contains no server, database, Kafka consumer, planner,
framework, or runtime dependency. Those belong to Weeks 2-5 in the implementation
plan.
