# Kafka Topics — Draft

Status: Proposed mapping for team review.
Topic names and event routing are not finalized.

## vehicle.telemetry
Normalized vehicle measurements.

Proposed events:
- VehicleTelemetryReceived
- VehicleLocationUpdated
- VehicleBatteryUpdated
- VehicleFaultReported

## fleet.events
Vehicle inventory, rental, maintenance, and demand events.

Proposed events:
- VehicleRegistered
- RentalStarted
- RentalEnded
- VehicleMaintenanceChanged
- DemandRecorded

## rebalancing.proposals
AI proposal lifecycle events.

Proposed events:
- RebalancingProposalCreated
- RebalancingProposalApproved
- RebalancingProposalRejected

## Decisions needed
- Confirm topic names and event routing with affected owners.
- Decide whether each measurement produces one combined telemetry
  event, separate update events, or both.
- Define message keys and ordering rules.
- Define retention, retry, and dead-letter handling.
- Assign the producer and consumers for each event.

## Proposed telemetry message key

For vehicle.telemetry, use vehicleId as the Kafka message key.

The key must match the vehicleId inside the event.
It is Kafka record metadata, separate from the JSON message.

With a stable partition count and consistent partitioning,
events for the same vehicle are routed to the same partition.

Kafka ordering does not guarantee measurement-time ordering.
Consumers must still handle delayed measurements and duplicates.

This choice requires review by the affected service owners.

## Proposed telemetry producers and consumers

Events:
- VehicleTelemetryReceived
- VehicleFaultReported

Proposed producer:
- telemetry-ingestion-service validates simulator input
  and publishes normalized events.

Proposed consumers:
- fleet-management-service applies agreed operational rules.
- mcp-server updates its independent read model.

Requires team agreement:
- Confirm direct consumption by both services.
- Decide whether separate location and battery events are
  published, by which service, and how double application
  of the same measurement is prevented.

## Comparison with the reviewed Backend draft

Backend currently publishes VehicleRegistered on fleet.vehicle, rentals on fleet.rental, and VehicleMaintenanceChanged on fleet.maintenance. This differs from the fleet.events proposal above; these are alternatives, not instructions to publish twice.

Backend currently lists VehicleBatteryUpdated, VehicleLocationUpdated and VehicleFaultReported as telemetry inputs. It does not list VehicleTelemetryReceived. Confirm the final routing and payloads before implementing producers or consumers. The separate battery/location event schemas are pending that decision.

DemandRecorded ownership, payload and routing remain open. Retention, retry and dead-letter settings also remain open; none are configured by this document.
