# Simulator Scenarios — Draft

Status: Draft for team review.
All data is synthetic.

## 1. Normal movement
Send successive measurements for the same vehicle.
Location changes and battery decreases gradually.

## 2. Low battery
Send a measurement with batteryPercent set to 10.
This is a test value, not an agreed business threshold.

## 3. Duplicate message
Send exactly the same event twice, preserving eventId.
Expected: consumers do not apply the same event twice.

## 4. Out-of-order measurements
Deliver a newer measurement before an older measurement.
Expected: the older measurement does not overwrite newer state.

## 5. Invalid telemetry
Send batteryPercent set to 150.
Expected: validation rejects the measurement.

## 6. Vehicle fault
Simulate a vehicle fault.
Use fault-example.draft.json (VehicleFaultReported, BRAKE_FAILURE).
Validate against fault.schema.draft.json. Backend state changes and the final fault-code list still require agreement.

## Reproducibility
Use fixed IDs, measurement times, and starting values.
Repeating a scenario must produce the same input events.

## Concrete replay scenario — Draft

Vehicle: veh_001
Date: 2026-11-05. All times are UTC.

Delivery order:
1. evt_101 — 20:00:00Z — batteryPercent: 70
2. evt_102 — 20:05:00Z — batteryPercent: 65
3. Exact replay of evt_101, with all fields unchanged.
4. evt_103 — 20:02:00Z — batteryPercent: 68

Expected final batteryPercent: 65.
Expected latest measurement time: 20:05:00Z.

The duplicate must not be applied twice.
The older measurement must not overwrite the newer state.

Open decision: define a tie-break rule for different events
with the same measurement timestamp.

## Additional validation cases
- Accept batteryPercent 0 and 100; reject -1, 101, 12.5 and "70".
- Accept latitude -90/90 and longitude -180/180; reject values outside these bounds.
- Reject missing required fields and unexpected payload fields.
- Reject an empty faultCode or a faultCode longer than 50 characters.
- Reject malformed dates and timestamps not ending in Z. Enable date-time format validation in the selected validator.

## Proposed Backend integration cases (after contract agreement)
- Test 19 and 20 percent against the Backend draft rental threshold.
- Test 49 and 50 percent against the Backend draft low-battery resolution threshold, starting with an open LOW_BATTERY record.
- Test low battery during an active rental and the status after return.
- Replay a fault with the same eventId; it must not apply twice.
- Report the same fault with a new eventId while a FAULT record is open; agree whether to update that record or only record the observation.
- Send a battery measurement at 20:05, then a location measurement at 20:04 that is newer than the last location. Agree how independent field freshness is tracked.
- Send different events with equal measurement times; document the agreed tie-break rule before asserting a final value.

These are test specifications, not evidence that a running consumer has passed them.
