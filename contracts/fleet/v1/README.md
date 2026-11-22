# Fleet Week 3 event contract

The fleet service owns operational state and publishes through its PostgreSQL
outbox. The [schema](fleet-event.schema.json) and
[rental, fault, and return example](rental-fault-return.example.json) define
the proposed shared `fleet-week3-v1` adapter. Use it in an isolated demo or
after the backend, telemetry, and MCP owners review the shared contract.

| Topic | Producer | Events |
| --- | --- | --- |
| `fleet.vehicle` | Fleet | `VehicleRegistered`, `VehicleStateUpdated` |
| `fleet.vehicle` | Telemetry | `VehicleBatteryUpdated`, `VehicleLocationUpdated`, `VehicleFaultReported` |
| `fleet.rental` | Fleet | `RentalStarted`, `RentalEnded` |
| `fleet.maintenance` | Fleet | `VehicleMaintenanceChanged` |

All records use the vehicle ID as the Kafka key. Fleet events carry the
resulting vehicle status and zone, where a null zone means the vehicle is
outside assigned zones. The revision increases for each vehicle across topics.
A fault during a ride immediately publishes `MAINTENANCE_REQUIRED`. The
rental remains active until the rider returns it. `RentalEnded` includes
`locationMeasuredAt` when the return replaces the stored location.

Telemetry tracks `(measuredAt, publishedAt, eventId)` independently for
battery, location, and faults. Return locations participate in the location
ordering. A registration baseline loses ties against an actual reading.
Telemetry carries UTC timestamps with up to nine fractional digits. The
database stores the ordering evidence at full precision and exposes the
measurement instant in REST at PostgreSQL timestamp precision. See the
[battery/location contract](../../../telemetry-contract.md) and
[fault schema](../../../fault.schema.json).

The fleet outbox saves the event ID and body in the same transaction as the
fleet change. `publishedAt` records when the fleet event was staged. Retries
preserve the entire event. Consumers must handle duplicate delivery. The
isolated demo retains all three topics without compaction. Use the
[fleet service upgrade notes](../../../fleet-management-service/README.md)
when starting from a Week 2 database and the
[MCP replay guide](../../../ai-mcp/README.md) to rebuild the projection.

An unassigned vehicle can appear in the MCP low-battery result with a null
`areaId`. It does not appear in area-specific availability or area-based
planner actions. A non-null zone without an area mapping remains a configuration
error.
