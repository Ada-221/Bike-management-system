// Runs INSIDE ai-mcp-service, on a fresh isolated broker only. Backend events
// are explicit fixtures pending owner agreement; telemetry uses its real Java producer.
import assert from "node:assert/strict";
import { readFile, writeFile } from "node:fs/promises";
import { setTimeout as delay } from "node:timers/promises";
import { Kafka, logLevel } from "kafkajs";
import { toolSchemas, validate } from "../src/contract.js";
import { DRAFT_CONTRACT } from "../src/events.js";

assert.equal(process.env.MCP_BACKEND_EVENT_CONTRACT, DRAFT_CONTRACT, "Fixture smoke requires explicit draft-contract opt-in");
const base = "http://127.0.0.1:8083";
const file = "/data/smoke-evidence.json";
const mode = process.argv[2] ?? "seed";
const kafka = new Kafka({ clientId: "mcp-fixture-smoke", brokers: ["kafka:9092"], logLevel: logLevel.ERROR });
async function call(name, args) {
  const response = await fetch(`${base}/mcp`, { method: "POST", headers: { "content-type": "application/json", "MCP-Protocol-Version": "2026-07-28" }, body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/call", _meta: { "io.modelcontextprotocol/protocolVersion": "2026-07-28" }, params: { name, arguments: { schemaVersion: "1.0", maxReadModelAgeSeconds: 300, pageSize: 100, ...args } } }) });
  const result = (await response.json()).result.structuredContent;
  assert.deepEqual(validate(result, toolSchemas.get(name).output), []);
  return result;
}
async function waitFor(predicate) {
  const deadline = Date.now() + 60000;
  while (Date.now() < deadline) { if (await predicate()) return; await delay(250); }
  throw new Error("Timed out waiting for Kafka projection: " + JSON.stringify(await (await fetch(`${base}/ready`)).json()));
}
async function send(events) {
  const producer = kafka.producer(); await producer.connect();
  try {
    for (const e of events) await producer.send({ topic: e.eventType.startsWith("Rental") ? "fleet.rental" : e.eventType === "VehicleMaintenanceChanged" ? "fleet.maintenance" : "fleet.vehicle", messages: [{ key: e.vehicleId, value: JSON.stringify(e) }] });
  } finally { await producer.disconnect(); }
}
if (mode === "seed") {
  const now = new Date().toISOString();
  const common = { schemaVersion: "1.0", publishedAt: now, correlationId: "corr_smoke", producer: "fleet-management-service" };
  const registration = (vehicleId, batteryPercent) => ({ ...common, eventId: `evt_register_${vehicleId}`, eventType: "VehicleRegistered", vehicleId, stateRevision: 1, payload: { status: "AVAILABLE", zoneId: "zone_001", batteryPercent, latitude: 48.21, longitude: 16.35, batteryMeasuredAt: "2026-11-01T00:00:00Z", locationMeasuredAt: "2026-11-01T00:00:01Z" } });
  const events = [registration("veh_001", 12), registration("veh_002", 5),
    { ...common, eventId: "evt_rental_start", eventType: "RentalStarted", vehicleId: "veh_002", stateRevision: 2, payload: { status: "RENTED" } },
    { ...common, eventId: "evt_maintenance", eventType: "VehicleMaintenanceChanged", vehicleId: "veh_002", stateRevision: 3, payload: { status: "MAINTENANCE_REQUIRED" } }];
  await send(events);
  await waitFor(async () => (await call("list_low_battery_vehicles", { thresholdPercent: 20 })).vehicles?.length === 2);
  const proposalResponse = await fetch(`${base}/api/v1/planner/runs`, { method: "POST" });
  const { proposal } = await proposalResponse.json(); assert.ok(proposal);
  const approved = await (await fetch(`${base}/api/v1/proposals/${proposal.proposalId}/approve`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ operatorId: "operator_smoke" }) })).json();
  assert.equal(approved.operationalStateChanged, false);
  assert.equal(approved.status, "APPROVED");
  const low = await call("list_low_battery_vehicles", { thresholdPercent: 20 });
  assert.equal(low.vehicles.find((v) => v.vehicleId === "veh_001").operationalStatus, "AVAILABLE");
  await writeFile(file, JSON.stringify({ events, proposalId: proposal.proposalId }));
} else {
  const evidence = JSON.parse(await readFile(file));
  if (mode === "telemetry") {
    await waitFor(async () => (await call("get_vehicle_availability_by_area", { areaId: "area_center" })).vehicles?.[0]?.batteryPercent === 74);
    const result = await call("get_vehicle_availability_by_area", { areaId: "area_center" });
    assert.equal(result.availableCount, 1); assert.equal(result.vehicles[0].availableSince, null);
    assert.deepEqual(result.vehicles[0].location, { latitude: 48.2104, longitude: 16.36 });
    evidence.vehicles = result.vehicles; await writeFile(file, JSON.stringify(evidence));
  } else if (mode === "outage") {
    await waitFor(async () => (await fetch(`${base}/ready`)).status === 503);
    const first = await call("get_vehicle_availability_by_area", { areaId: "area_center" });
    assert.deepEqual(first.vehicles, evidence.vehicles);
    await delay(2500);
    const second = await call("get_vehicle_availability_by_area", { areaId: "area_center" });
    assert.equal(second.readModelAsOf, first.readModelAsOf);
    assert.equal((await call("get_vehicle_availability_by_area", { areaId: "area_center", maxReadModelAgeSeconds: 0 })).error.code, "STALE_READ_MODEL");
  } else if (mode === "verify") {
    await send(evidence.events); // IDs preserved, including registration after newer telemetry.
    await waitFor(async () => (await fetch(`${base}/ready`)).ok);
    const result = await call("get_vehicle_availability_by_area", { areaId: "area_center" });
    assert.deepEqual(result.vehicles, evidence.vehicles);
    const proposal = await (await fetch(`${base}/api/v1/proposals/${evidence.proposalId}`)).json();
    assert.equal(proposal.status, "APPROVED"); assert.equal(proposal.operationalStateChanged, false);
    const low = await call("list_low_battery_vehicles", { thresholdPercent: 20 });
    assert.deepEqual(low.vehicles.map((v) => [v.vehicleId, v.operationalStatus]), [["veh_002", "MAINTENANCE"]]);
  } else throw new Error(`Unknown mode ${mode}`);
}
console.log(`PASS Kafka fixture smoke: ${mode}; backend producer integration remains gated.`);
