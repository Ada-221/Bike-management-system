# Integrate Week 2 fleet, telemetry, MCP and rider work

Combines the existing team branches on `integration/week-2` while retaining
vehicle API behavior, telemetry production, rider demo mode and dashboard
fixtures. Compose now runs PostgreSQL, backend, Kafka, dashboard, schema viewer
and MCP together, with backend readiness checks and an optional telemetry image
and profile. The simulator requires explicit opt-in.

Shared Compose conflicts were resolved by combining services and persistent
volumes. Environment examples define the development area/zone mappings.
Documentation distinguishes implemented vehicle endpoints from draft
nearby/rental APIs, repairs moved backend references and consistently requires
distinct `evt_` IDs for battery/location events. CI covers Java, MCP, dashboard,
Android emulator tests and an isolated Compose smoke with captured evidence.

Source commits, merge resolutions, exact validation results and limitations are
recorded in [Week 2 verification](week-2-verification.md). Sources:

- Fleet: `0b00586fb88f7991a538b212e6c6a914d79e242a`
- Telemetry: `4c3333c45d94d1e21ada1d0ffc02bb50fd1d695b`
- Reconciled MCP: `9ae483f2dc7caeeb3b46e1e93d542ff77074ac8e`
- Rider: `0a04af261d98eeebd9490b83cf3162a7d272327c`
- Existing frontend: `7493da1dd23d9d47595e9953b66ff385da2bfb0d`
- MCP Week 2 through reconciliation: `6d7d28260a203deef00b737719c3afdbcb0ec324`

Validation passed: backend Maven verify (24 unit/web + 29 PostgreSQL integration
tests), telemetry packaging, 16 MCP tests and syntax checks, dashboard build and
8 browser tests, all Compose image builds and isolated combined smoke. The smoke
covered readiness, real vehicle registration and MCP semantics, advisory approval,
persistence across restart and four keyed schema-valid distinct telemetry events.

**Not ready to merge:** local Android checks are blocked by a missing SDK/emulator;
hosted CI and remote mergeability require confirmation. Main remains unchanged.

Deferred: live nearby/rental/return APIs, fleet telemetry consumers, MCP Kafka
projection, demand, OFFLINE rules and operational proposal execution. Proposal
approval records a human decision and never mutates fleet state.
