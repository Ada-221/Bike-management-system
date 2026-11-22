// Runs inside an isolated Compose stack. All fleet mutations use the real REST and telemetry producers.
import assert from "node:assert/strict";
import { readFile, writeFile } from "node:fs/promises";
import { setTimeout as delay } from "node:timers/promises";
import { toolSchemas, validate } from "../src/contract.js";
import { FLEET_CONTRACT } from "../src/events.js";

assert.equal(process.env.MCP_BACKEND_EVENT_CONTRACT, FLEET_CONTRACT);
const mcp = "http://127.0.0.1:8083";
const fleet = "http://fleet-management-service:8080/api/v1";
const dashboard = "http://operator-dashboard/api/v1";
const file = "/data/smoke-evidence.json";
const mode = process.argv[2];
const key = "fleet-smoke-rental-1";
const riderId = "rider-smoke";
const center = { areaId: "area_center" };

async function request(base, path, { method = "GET", body, expected = 200, headers = {} } = {}) {
  const response = await fetch(base + path, {
    method,
    headers: { "content-type": "application/json", ...headers },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  const text = await response.text();
  assert.equal(response.status, expected, `${method} ${path}: ${text}`);
  return JSON.parse(text);
}

async function call(name, args) {
  const rpc = await request(mcp, "/mcp", {
    method: "POST", headers: { "MCP-Protocol-Version": "2026-07-28" },
    body: { jsonrpc: "2.0", id: 1, method: "tools/call",
      _meta: { "io.modelcontextprotocol/protocolVersion": "2026-07-28" },
      params: { name, arguments: { schemaVersion: "1.0", maxReadModelAgeSeconds: 300, pageSize: 100, ...args } } },
  });
  const result = rpc.result?.structuredContent;
  assert.ok(result, JSON.stringify(rpc));
  assert.deepEqual(validate(result, toolSchemas.get(name).output), []);
  return result;
}

async function waitFor(predicate) {
  const deadline = Date.now() + 60000;
  while (Date.now() < deadline) {
    if (await predicate()) return;
    await delay(250);
  }
  throw new Error("Timed out waiting for integrated state: " + await (await fetch(mcp + "/ready")).text());
}

const availability = (args = {}) => call("get_vehicle_availability_by_area", { ...center, ...args });
const low = (thresholdPercent = 100) => call("list_low_battery_vehicles", { thresholdPercent });
const vehicle = (id, base = fleet) => request(base, "/vehicles/" + id);
const register = (label, batteryPercent, latitude = 48.21, longitude = 16.36) => request(fleet, "/vehicles", {
  method: "POST", expected: 201, body: { label, batteryPercent, type: "E_BIKE", latitude, longitude },
});
const save = (value) => writeFile(file, JSON.stringify(value));
const find = (result, id) => result.vehicles?.find((v) => v.vehicleId === id);
async function assertParkedLocation(id) {
  const { projection } = JSON.parse(await readFile("/data/state.json"));
  const parked = projection.confirmed.vehicles.find((v) => v.id === id);
  assert.equal(parked.zoneId, null);
  assert.deepEqual([parked.latitude, parked.longitude], [48.24, 16.40]);
}

if (mode === "seed") {
  const working = await register("SMOKE-RENT", 92);
  assert.equal(working.id, "veh_001");
  const charging = await register("SMOKE-CHARGE", 12);
  const plannerCandidate = await register("SMOKE-PLANNER", 25, 48.1858, 16.3758);
  assert.equal(charging.status, "MAINTENANCE_REQUIRED");
  await waitFor(async () => find(await availability(), working.id)?.batteryPercent === 92
    && find(await low(20), charging.id)?.operationalStatus === "MAINTENANCE"
    && find(await low(29), plannerCandidate.id)?.operationalStatus === "AVAILABLE");

  const { proposal } = await request(mcp, "/api/v1/planner/runs", { method: "POST", expected: 202 });
  assert.ok(proposal);
  const approved = await request(mcp, `/api/v1/proposals/${proposal.proposalId}/approve`, {
    method: "POST", body: { operatorId: "operator_smoke" },
  });
  assert.equal(approved.status, "APPROVED");
  assert.equal(approved.operationalStateChanged, false);
  await save({ workingId: working.id, chargingId: charging.id, proposalId: proposal.proposalId });
} else {
  const evidence = JSON.parse(await readFile(file));
  const { workingId, chargingId } = evidence;
  if (mode === "rent") {
    await waitFor(async () => {
      const [api, model] = await Promise.all([vehicle(workingId, dashboard), availability()]);
      return api.batteryPercent === 74 && api.latitude === 48.2104 && find(model, workingId)?.batteryPercent === 74;
    });
    const nearby = await request(fleet, "/vehicles/nearby?lat=48.21&lon=16.36");
    assert.deepEqual(nearby.map((v) => v.id), [workingId]);
    const options = { method: "POST", body: { vehicleId: workingId, riderId }, headers: { "Idempotency-Key": key } };
    const rental = await request(fleet, "/rentals", { ...options, expected: 201 });
    assert.deepEqual(await request(fleet, "/rentals", options), rental);
    await waitFor(async () => find(await low(), workingId)?.operationalStatus === "IN_USE");
    assert.equal(find(await availability(), workingId), undefined);
    await save({ ...evidence, rentalId: rental.id });
  } else if (mode === "fault-return") {
    await waitFor(async () => (await vehicle(workingId, dashboard)).status === "MAINTENANCE_REQUIRED"
      && find(await low(), workingId)?.operationalStatus === "MAINTENANCE");
    const afterReplay = await vehicle(workingId, dashboard);
    assert.equal(afterReplay.batteryPercent, 65);
    assert.equal(afterReplay.latitude, 48.2105);
    assert.equal(afterReplay.longitude, 16.3605);
    assert.equal(find(await low(), workingId)?.batteryPercent, 65);
    assert.equal((await request(dashboard, "/fleet/status")).activeRentals, 1);
    assert.deepEqual(await request(fleet, "/vehicles/nearby?lat=48.21&lon=16.36"), []);
    const recovered = await request(fleet, `/rentals?riderId=${riderId}&status=ACTIVE`);
    assert.deepEqual(recovered.map((trip) => trip.id), [evidence.rentalId]);
    const path = `/rentals/${evidence.rentalId}/return`;
    const returned = await request(fleet, path, { method: "POST", body: { latitude: 48.24, longitude: 16.40 } });
    assert.equal(returned.status, "COMPLETED");
    assert.deepEqual(await request(fleet, path, { method: "POST", body: { latitude: 48.21, longitude: 16.36 } }), returned);
    const parked = await vehicle(workingId);
    assert.equal(parked.zoneId, null);
    assert.equal(parked.status, "MAINTENANCE_REQUIRED");
    assert.equal((await request(dashboard, "/fleet/status")).activeRentals, 0);
    await waitFor(async () => {
      const result = find(await low(), workingId);
      return result?.areaId === null && result.operationalStatus === "MAINTENANCE";
    });
    await assertParkedLocation(workingId);
    evidence.vehicleState = (await low()).vehicles;
    await save(evidence);
  } else if (mode === "outage") {
    await waitFor(async () => (await fetch(mcp + "/ready")).status === 503);
    const first = await availability();
    assert.deepEqual(first.vehicles, []);
    await delay(2500);
    assert.equal((await availability()).readModelAsOf, first.readModelAsOf);
    assert.equal((await availability({ maxReadModelAgeSeconds: 0 })).error.code, "STALE_READ_MODEL");
    const queued = await register("SMOKE-OUTBOX", 85);
    await save({ ...evidence, queuedId: queued.id });
  } else if (mode === "verify") {
    await waitFor(async () => (await fetch(mcp + "/ready")).ok
      && (!evidence.queuedId || find(await availability(), evidence.queuedId)?.batteryPercent === 85));
    const result = await low();
    assert.deepEqual(result.vehicles.filter((v) => v.vehicleId !== evidence.queuedId), evidence.vehicleState);
    await assertParkedLocation(workingId);
    assert.equal(find(result, chargingId)?.operationalStatus, "MAINTENANCE");
    const proposal = await request(mcp, "/api/v1/proposals/" + evidence.proposalId);
    assert.equal(proposal.status, "APPROVED");
    assert.equal(proposal.operationalStateChanged, false);
  } else throw new Error(`Unknown mode ${mode}`);
}
console.log(`PASS real fleet Kafka smoke: ${mode}`);
