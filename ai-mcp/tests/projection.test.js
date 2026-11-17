import assert from "node:assert/strict";
import { readFile, writeFile, rename } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { acquireWriter, createJsonStore } from "../src/store.js";
import { createProjection } from "../src/projection.js";
import { normalizeEvent, DRAFT_CONTRACT, TOPICS } from "../src/events.js";
import { createService } from "../src/service.js";
import { consumeBatch } from "../src/consumer.js";
import { createHttpServer } from "../src/server.js";
import { toolSchemas, validate } from "../src/contract.js";
import { fixture, event, record, NOW, lowArgs, availableArgs } from "./helpers.js";

test("registration, telemetry, rental and maintenance produce schema-valid tools with backend-issued zone changes", async (t) => {
  const f = await fixture(t);
  await f.projection.process(record(event()));
  await f.projection.process(record(event("VehicleBatteryUpdated", { batteryPercent: 20 }), "1"));
  await f.projection.process(record(event("VehicleLocationUpdated", { latitude: 44, longitude: 22 }), "2"));
  await f.projection.process(record(event("RentalStarted", { status: "RENTED" }, { stateRevision: 2 })));
  await f.confirm();
  let low = await f.service.callTool("list_low_battery_vehicles", lowArgs);
  assert.equal(low.vehicles[0].operationalStatus, "IN_USE");
  assert.equal((await f.service.callTool("get_vehicle_availability_by_area", availableArgs)).availableCount, 0);
  await f.projection.process(record(event("VehicleMaintenanceChanged", { status: "MAINTENANCE_REQUIRED" }, { stateRevision: 4 })));
  await f.projection.process(record(event("RentalEnded", { status: "AVAILABLE", zoneId: "zone_002" }, { stateRevision: 3 }), "1"));
  await f.confirm();
  low = await f.service.callTool("list_low_battery_vehicles", lowArgs);
  assert.equal(low.vehicles[0].operationalStatus, "MAINTENANCE");
  assert.equal(low.vehicles[0].areaId, "area_south");
  await f.projection.process(record(event("VehicleStateUpdated", { status: "AVAILABLE" }, { stateRevision: 5 }), "3"));
  await f.confirm();
  const result = await f.service.callTool("get_vehicle_availability_by_area", { ...availableArgs, areaId: "area_south", minimumBatteryPercent: 20 });
  assert.equal(result.availableCount, 1);
  assert.deepEqual(result.vehicles[0].location, { latitude: 44, longitude: 22 });
  assert.equal(result.vehicles[0].availableSince, null);
  assert.equal(result.vehicles[0].batteryMeasuredAt, NOW);
  assert.deepEqual(validate(low, toolSchemas.get("list_low_battery_vehicles").output), []);
  assert.deepEqual(validate(result, toolSchemas.get("get_vehicle_availability_by_area").output), []);
  assert.equal((await f.service.callTool("list_low_battery_vehicles", { ...lowArgs, thresholdPercent: 19 })).vehicles.length, 0);
  assert.equal((await f.service.callTool("get_vehicle_availability_by_area", { ...availableArgs, areaId: "area_south", minimumBatteryPercent: 21 })).availableCount, 0);
});

test("independent measurements and equal-time publication/ID ties replay deterministically, including nanoseconds", async (t) => {
  const events = [event(),
    event("VehicleBatteryUpdated", { batteryPercent: 5 }, { eventId: "evt_tie_a", measuredAt: NOW }),
    event("VehicleBatteryUpdated", { batteryPercent: 8 }, { eventId: "evt_tie_b", measuredAt: NOW }),
    event("VehicleBatteryUpdated", { batteryPercent: 9 }, { publishedAt: "2026-11-16T10:00:00.000000001Z" }),
    event("VehicleBatteryUpdated", { batteryPercent: 99 }, { measuredAt: "2026-11-16T09:59:00Z", publishedAt: "2026-11-16T11:00:00Z" }),
    event("VehicleLocationUpdated", { latitude: 40, longitude: 20 }, { measuredAt: "2026-11-16T09:30:00Z" }),
  ];
  const a = await fixture(t), b = await fixture(t);
  for (const [i, e] of events.entries()) await a.projection.process(record(e, String(i)));
  for (const [i, e] of [...events].reverse().entries()) await b.projection.process(record(e, String(i)));
  await a.confirm(); await b.confirm();
  assert.deepEqual((await a.projection.snapshot()).vehicles, (await b.projection.snapshot()).vehicles);
  const v = (await a.projection.snapshot()).vehicles[0];
  assert.equal(v.batteryPercent, 9); assert.equal(v.latitude, 40);
  assert.equal(v.lastLocationAt, "2026-11-16T09:30:00Z");
});

test("updates before registration are retained but cannot expose incomplete fleet results", async (t) => {
  const f = await fixture(t);
  await f.projection.process(record(event("VehicleBatteryUpdated", { batteryPercent: 6 })));
  assert.equal(await f.confirm(), false);
  assert.match(f.projection.readiness().diagnostic, /Missing VehicleRegistered/);
  assert.equal((await f.service.callTool("list_low_battery_vehicles", lowArgs)).error.code, "READ_MODEL_UNAVAILABLE");
  await f.projection.process(record(event(), "1")); await f.confirm();
  assert.equal((await f.projection.snapshot()).vehicles[0].batteryPercent, 6);
});

test("duplicates before/after restart checkpoint safely; conflicting event IDs and same-revision values halt", async (t) => {
  const f = await fixture(t), e = event();
  await f.projection.process(record(e)); await f.projection.process(record(e, "1")); await f.confirm();
  const restarted = await createProjection({ store: createJsonStore(f.directory), backendContract: DRAFT_CONTRACT });
  await assert.rejects(restarted.snapshot(), /not caught up/);
  await restarted.process(record(e, "2"));
  assert.equal(Object.keys((await restarted.inspect()).processed).length, 1);
  assert.equal((await restarted.inspect()).checkpoints["fleet.vehicle:0"], "3");
  await assert.rejects(restarted.process(record({ ...e, payload: { ...e.payload, batteryPercent: 1 } }, "3")), /Conflicting reuse/);
  assert.equal((await restarted.inspect()).checkpoints["fleet.vehicle:0"], "3");
  const g = await fixture(t);
  await g.projection.process(record(event()));
  await assert.rejects(g.projection.process(record(event("RentalStarted", { status: "RENTED" }))), /Conflicting status/);
});

test("malformed records, wrong keys/topics, unsupported versions, missing revisions/timestamps and unapproved contracts fail closed", async (t) => {
  const valid = record(event());
  const cases = [{ ...valid, value: "{" }, { ...valid, value: null }, { ...valid, key: "veh_wrong" },
    { ...valid, topic: "fleet.rental" }, record(event("VehicleBatteryUpdated", { batteryPercent: 101 })),
    record(event("VehicleLocationUpdated", { latitude: 91, longitude: 0 })), record(event("VehicleRegistered", undefined, { schemaVersion: "2.0" })),
    record(event("VehicleRegistered", undefined, { stateRevision: undefined })), record(event("VehicleRegistered", { status: "AVAILABLE" })),
    record(event("VehicleBatteryUpdated", { batteryPercent: 12 }, { measuredAt: "2026-02-30T10:00:00Z" })),
  ];
  for (const input of cases) {
    const f = await fixture(t);
    await assert.rejects(f.projection.process(input));
    assert.deepEqual((await f.projection.inspect()).checkpoints, {});
    assert.equal(f.projection.readiness().ready, false);
    assert.equal((await f.service.callTool("list_low_battery_vehicles", lowArgs)).error.code, "READ_MODEL_UNAVAILABLE");
  }
  assert.throws(() => normalizeEvent(valid), /contract is gated/);
  const f = await fixture(t);
  await f.projection.process({ ...valid, key: null, value: '{"eventType":"UnrelatedAuditEvent"}' });
  assert.equal((await f.projection.inspect()).checkpoints["fleet.vehicle:0"], "1");
  assert.deepEqual((await f.projection.inspect()).vehicles, {});
});

function context(e) {
  return { batch: { topic: "fleet.vehicle", partition: 0, messages: [record(e)] }, resolveOffset() {}, heartbeat: async () => {}, isRunning: () => true, isStale: () => false };
}

test("atomic replacement failure never advances memory/disk/checkpoint or Kafka commits; corrected storage replays", async (t) => {
  let fail = false, commits = 0;
  const f = await fixture(t, { storage: { replace: async (...args) => { if (fail) throw new Error("disk full"); return rename(...args); } } });
  const e = event(); await f.projection.process(record(e)); const before = await f.projection.inspect();
  fail = true;
  await assert.rejects(consumeBatch(context(event("VehicleBatteryUpdated", { batteryPercent: 4 })), { projection: f.projection, consumer: { commitOffsets: async () => { commits++; } } }), /storage failed/);
  assert.equal(commits, 0);
  assert.deepEqual(await f.projection.inspect(), before);
  assert.deepEqual(await f.store.loadProjection(), before);
  fail = false;
  const restarted = await createProjection({ store: f.store, backendContract: DRAFT_CONTRACT });
  await restarted.process(record(event("VehicleBatteryUpdated", { batteryPercent: 4 }), "1"));
  assert.equal((await restarted.inspect()).vehicles.veh_001.data.batteryPercent, 4);
});

test("persistence followed by failed Kafka commit replays as a duplicate after restart", async (t) => {
  const f = await fixture(t), e = event();
  await assert.rejects(consumeBatch(context(e), { projection: f.projection, consumer: { commitOffsets: async () => { throw new Error("broker down after persistence"); } } }), /broker down/);
  const restarted = await createProjection({ store: createJsonStore(f.directory), backendContract: DRAFT_CONTRACT });
  let committed;
  await consumeBatch(context(e), { projection: restarted, consumer: { commitOffsets: async (offsets) => { committed = offsets; } } });
  assert.deepEqual(committed, [{ topic: "fleet.vehicle", partition: 0, offset: "1" }]);
  assert.equal(Object.keys((await restarted.inspect()).processed).length, 1);
});

test("broker checks refresh idle topics; outage freezes asOf, ages snapshots, and recovers without a backend", async (t) => {
  let ms = Date.parse(NOW);
  const f = await fixture(t, { clock: () => new Date(ms) });
  assert.equal((await f.service.callTool("list_low_battery_vehicles", lowArgs)).error.code, "READ_MODEL_UNAVAILABLE");
  await f.projection.process(record(event())); await f.confirm();
  ms += 10000; await f.confirm();
  const asOf = (await f.service.callTool("list_low_battery_vehicles", lowArgs)).readModelAsOf;
  assert.equal(asOf, new Date(ms).toISOString());
  f.projection.disconnect(); ms += 20000;
  assert.equal(f.projection.readiness().ready, false);
  assert.equal((await f.service.callTool("list_low_battery_vehicles", lowArgs)).readModelAsOf, asOf);
  ms += 301000;
  assert.equal((await f.service.callTool("list_low_battery_vehicles", lowArgs)).error.code, "STALE_READ_MODEL");
  await f.confirm(); assert.equal(f.projection.readiness().ready, true);
  assert.ok((await f.service.callTool("list_low_battery_vehicles", lowArgs)).vehicles);
});

test("all partitions must catch up; missing retained history fails with actionable diagnostics", async (t) => {
  const f = await fixture(t);
  const bounds = TOPICS.map((topic) => ({ topic, partition: 0, low: "0", high: topic === "fleet.rental" ? "1" : "0" }));
  assert.equal(await f.projection.confirm(bounds), false);
  assert.equal(f.projection.readiness().ready, false);
  await assert.rejects(f.projection.confirm(bounds.map((b) => ({ ...b, low: "1", high: "2" }))), /Required history unavailable/);
});

test("events persisted during a broker probe do not look like truncated history", async (t) => {
  const f = await fixture(t);
  await f.projection.process(record(event()));
  const before = (await f.projection.inspect()).checkpoints;
  const capturedBounds = TOPICS.map((topic) => ({ topic, partition: 0, low: "0", high: topic === "fleet.vehicle" ? "1" : "0" }));
  await f.projection.process(record(event("VehicleBatteryUpdated", { batteryPercent: 7 }), "1"));
  assert.equal(await f.projection.confirm(capturedBounds, { checkpointsBeforeProbe: before }), true);
  assert.equal((await f.projection.snapshot()).vehicles[0].batteryPercent, 7);
});

test("more than 500 vehicles paginate without a backend cap; cursors survive idle checks and invalidate on content changes", async (t) => {
  const f = await fixture(t);
  for (let i = 0; i < 501; i++) await f.projection.process(record(event("VehicleRegistered", undefined, { vehicleId: `veh_${String(i).padStart(4, "0")}` }), String(i)));
  await f.confirm();
  const first = await f.service.callTool("get_vehicle_availability_by_area", availableArgs);
  assert.equal(first.availableCount, 501); assert.equal(first.vehicles.length, 100);
  await f.confirm();
  const next = await f.service.callTool("get_vehicle_availability_by_area", { ...availableArgs, cursor: first.pagination.nextCursor });
  assert.equal(next.vehicles.length, 100);
  await f.projection.process(record(event("VehicleBatteryUpdated", { batteryPercent: 1 }, { vehicleId: "veh_0000" }), "501")); await f.confirm();
  assert.equal((await f.service.callTool("get_vehicle_availability_by_area", { ...availableArgs, cursor: next.pagination.nextCursor })).error.code, "CURSOR_EXPIRED");
});

test("rebuild replays to equivalent vehicle results and preserves approved/rejected proposals; legacy REST cache is never bootstrap", async (t) => {
  const f = await fixture(t);
  await writeFile(path.join(f.directory, "state.json"), JSON.stringify({ proposals: {}, readModel: { asOf: NOW, vehicles: [{ id: "veh_legacy" }] } }));
  const cold = await createProjection({ store: f.store, backendContract: DRAFT_CONTRACT });
  assert.deepEqual((await cold.inspect()).vehicles, {});
  const events = [event(), event("VehicleBatteryUpdated", { batteryPercent: 8 })];
  for (const [i, e] of events.entries()) await f.projection.process(record(e, String(i)));
  await f.confirm();
  const approved = await f.service.createProposal(); await f.service.decideProposal(approved.proposalId, "APPROVED", "operator_test");
  events.push(event("VehicleBatteryUpdated", { batteryPercent: 6 })); await f.projection.process(record(events[2], "2")); await f.confirm();
  const rejected = await f.service.createProposal(); await f.service.decideProposal(rejected.proposalId, "REJECTED", "operator_test");
  const before = (await f.projection.snapshot()).vehicles, proposals = await f.store.listProposals();
  assert.deepEqual(proposals.map((p) => p.status).sort(), ["APPROVED", "REJECTED"]);
  assert.ok(proposals.every((p) => p.operationalStateChanged === false));
  await f.store.resetProjection();
  const rebuilt = await createProjection({ store: f.store, backendContract: DRAFT_CONTRACT });
  for (const [i, e] of events.entries()) await rebuilt.process(record(e, String(i)));
  await rebuilt.process(record(events[0], "3"));
  await rebuilt.confirm(TOPICS.map((topic) => ({ topic, partition: 0, low: "0", high: topic === "fleet.vehicle" ? "4" : "0" })));
  assert.deepEqual((await rebuilt.snapshot()).vehicles, before);
  assert.deepEqual(await f.store.listProposals(), proposals);
  assert.equal("readModel" in JSON.parse(await readFile(path.join(f.directory, "state.json"))), false);
});

test("writer lock excludes startup/rebuild competitors and corrupt projection files fail closed", async (t) => {
  const f = await fixture(t), release = await acquireWriter(f.directory);
  await assert.rejects(acquireWriter(f.directory), /already locked/); await release(); await (await acquireWriter(f.directory))();
  await f.projection.process(record(event()));
  const disk = JSON.parse(await readFile(path.join(f.directory, "state.json")));
  disk.projection.vehicles.veh_001.data.batteryPercent = 99;
  await writeFile(path.join(f.directory, "state.json"), JSON.stringify(disk));
  await assert.rejects(createProjection({ store: f.store }), /checksum mismatch/);
});

test("HTTP liveness is independent of Kafka readiness and includes actionable diagnostics", async (t) => {
  const f = await fixture(t), server = createHttpServer(f.service);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise((resolve) => server.close(resolve)));
  const url = `http://127.0.0.1:${server.address().port}`;
  assert.equal((await fetch(`${url}/health`)).status, 200);
  assert.equal((await fetch(`${url}/ready`)).status, 503);
  await f.confirm(); assert.equal((await fetch(`${url}/ready`)).status, 200);
  f.projection.disconnect("broker unavailable");
  const response = await fetch(`${url}/ready`); assert.equal(response.status, 503);
  assert.match((await response.json()).diagnostic, /broker unavailable/);
});
