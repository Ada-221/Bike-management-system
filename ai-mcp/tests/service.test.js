import assert from "node:assert/strict";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { manifest, toolSchemas, validate, validateProposal } from "../src/contract.js";
import { createJsonStore, createService, statusForMcp, areaZone, validateAreaMap, createBackend, ServiceError } from "../src/service.js";
import { createHttpServer } from "../src/server.js";

const now = new Date("2026-11-10T10:00:00.000Z");
const zone = { id: "zone_campus", name: "Campus" };
const vehicles = [
  { id: "veh_001", zoneId: zone.id, status: "AVAILABLE", batteryPercent: 12, latitude: 45.74, longitude: 21.20, lastBatteryAt: "2026-11-10T09:59:00Z", lastLocationAt: "2026-11-10T09:58:00Z", updatedAt: "2026-11-10T09:59:00Z" },
  { id: "veh_002", zoneId: zone.id, status: "RENTED", batteryPercent: 75, latitude: 45.75, longitude: 21.21, lastBatteryAt: "2026-11-10T09:58:00Z", lastLocationAt: "2026-11-10T09:57:00Z", updatedAt: "2026-11-10T09:58:00Z" },
  { id: "veh_003", zoneId: zone.id, status: "MAINTENANCE_REQUIRED", batteryPercent: 9, latitude: 45.76, longitude: 21.22, lastBatteryAt: "2026-11-10T09:57:00Z", lastLocationAt: "2026-11-10T09:56:00Z", updatedAt: "2026-11-10T09:57:00Z" },
];

async function fixture(t) {
  const directory = await mkdtemp(path.join(os.tmpdir(), "cebp-mcp-"));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const store = createJsonStore(directory);
  const backend = { vehicles: async () => structuredClone(vehicles) };
  return { store, service: createService({ backend, areaMap: { area_campus: zone.id }, store, clock: () => new Date(now) }), directory };
}

test("tool discovery and input validation use the frozen v1 contract", () => {
  assert.deepEqual(manifest.tools.map((tool) => tool.name), [...toolSchemas.keys()]);
  const schema = toolSchemas.get("list_low_battery_vehicles").input;
  assert.equal(validate({ schemaVersion: "1.0", thresholdPercent: 30, pageSize: 10, maxReadModelAgeSeconds: 60 }, schema).length, 0);
  assert.ok(validate({ schemaVersion: "1.0", thresholdPercent: 101, pageSize: 10, maxReadModelAgeSeconds: 60, invented: true }, schema).length > 0);
});

test("settled battery and location examples validate as distinct events", async () => {
  const schema = JSON.parse(await readFile(new URL("../../telemetry.schema.json", import.meta.url), "utf8"));
  for (const file of ["../../telemetry-example.json", "../../location-example.json"]) {
    const example = JSON.parse(await readFile(new URL(file, import.meta.url), "utf8"));
    assert.deepEqual(validate(example, schema), []);
    assert.notEqual(example.measuredAt, example.publishedAt);
    assert.match(example.eventId, /^evt_[0-9a-f-]{36}$/);
  }
});

test("status adapter preserves backend vocabulary and rejects unsettled OFFLINE inference", () => {
  assert.equal(statusForMcp("RENTED"), "IN_USE");
  assert.equal(statusForMcp("MAINTENANCE_REQUIRED"), "MAINTENANCE");
  assert.throws(() => statusForMcp("OFFLINE"), ServiceError);
  assert.equal(areaZone("area_campus", { area_campus: zone.id }), zone.id);
  assert.throws(() => areaZone("area_guess", {}), (error) => error.code === "UNKNOWN_AREA");
  assert.throws(() => validateAreaMap({ area_campus: "guessed-uuid" }));
  assert.throws(() => validateAreaMap({ area_campus: zone.id, area_other: zone.id }));
});

test("low-battery and availability tools query backend state and return schema-valid results", async (t) => {
  const { service, directory } = await fixture(t);
  const low = await service.callTool("list_low_battery_vehicles", { schemaVersion: "1.0", thresholdPercent: 20, pageSize: 10, maxReadModelAgeSeconds: 300 });
  assert.equal(validate(low, toolSchemas.get("list_low_battery_vehicles").output).length, 0);
  assert.deepEqual(low.vehicles.map(({ vehicleId, operationalStatus }) => [vehicleId, operationalStatus]), [["veh_003", "MAINTENANCE"], ["veh_001", "AVAILABLE"]]);
  const available = await service.callTool("get_vehicle_availability_by_area", { schemaVersion: "1.0", areaId: "area_campus", minimumBatteryPercent: 10, pageSize: 10, maxReadModelAgeSeconds: 300 });
  assert.equal(validate(available, toolSchemas.get("get_vehicle_availability_by_area").output).length, 0);
  assert.deepEqual(available.vehicles.map((vehicle) => vehicle.vehicleId), ["veh_001"]);
  assert.equal(available.vehicles[0].batteryMeasuredAt, "2026-11-10T09:59:00Z");
  assert.equal(available.vehicles[0].locationMeasuredAt, "2026-11-10T09:58:00Z");
  assert.ok((await readFile(path.join(directory, "state.json"), "utf8")).includes("readModel"));
});

test("HTTP server exposes health and the agreed MCP JSON-RPC tool interface", async (t) => {
  const { service } = await fixture(t);
  const server = createHttpServer(service);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise((resolve) => server.close(resolve)));
  const base = `http://127.0.0.1:${server.address().port}`;
  assert.deepEqual(await (await fetch(`${base}/health`)).json(), { status: "UP", service: "cebp-mcp-server" });
  const response = await fetch(`${base}/mcp`, { method: "POST", headers: { "content-type": "application/json", "MCP-Protocol-Version": "2026-07-28" }, body: JSON.stringify({ jsonrpc: "2.0", id: "test-1", method: "tools/list", _meta: { "io.modelcontextprotocol/protocolVersion": "2026-07-28" } }) });
  const body = await response.json();
  assert.equal(response.status, 200);
  assert.deepEqual(body.result.tools.map((tool) => tool.name), [...toolSchemas.keys()]);
});

test("mock proposals persist as schema-valid advisory records; decisions never mutate fleet state", async (t) => {
  const { service, store, directory } = await fixture(t);
  const before = JSON.stringify(await service.loadState());
  const proposal = await service.createProposal();
  const repeatedProposal = await service.createProposal();
  assert.deepEqual(repeatedProposal, proposal);
  assert.deepEqual(validateProposal(proposal), []);
  assert.equal((await createJsonStore(directory).getProposal(proposal.proposalId)).status, "PENDING_APPROVAL");
  const approved = await service.decideProposal(proposal.proposalId, "APPROVED", "operator_demo");
  assert.equal(approved.status, "APPROVED");
  assert.equal(approved.operationalStateChanged, false);
  assert.equal(JSON.stringify(await service.loadState()), before);
  assert.ok((await store.listProposals()).some((saved) => saved.proposalId === proposal.proposalId));
});

test("demand tool returns a contract-shaped unavailable result while ownership is unresolved", async (t) => {
  const { service } = await fixture(t);
  const result = await service.callTool("get_demand_by_area", { schemaVersion: "1.0", areaIds: ["area_campus"], from: "2026-11-10T00:00:00Z", to: "2026-11-10T01:00:00Z", bucketMinutes: 60, pageSize: 10, maxReadModelAgeSeconds: 300 });
  assert.equal(result.error.code, "READ_MODEL_UNAVAILABLE");
  assert.equal(validate(result, toolSchemas.get("get_demand_by_area").output).length, 0);
});

test("backend adapter preserves API prefixes with either trailing-slash form and propagates failures", async () => {
  for (const base of ["http://fleet/api/v1", "http://fleet/api/v1/"]) {
    const seen = [];
    const backend = createBackend(base, async (url) => { seen.push(url.href); return { ok: true, json: async () => vehicles }; });
    assert.deepEqual(await backend.vehicles(), vehicles);
    assert.deepEqual(seen, ["http://fleet/api/v1/vehicles?limit=500"]);
  }
  await assert.rejects(createBackend("http://fleet/api/v1", async () => ({ ok: false, status: 503 })).vehicles(), (e) => e.code === "READ_MODEL_UNAVAILABLE");
});

const lowArgs = { schemaVersion: "1.0", thresholdPercent: 20, pageSize: 1, maxReadModelAgeSeconds: 300 };
test("pagination tokens satisfy schemas, continue once, and reject changed queries, state and restart", async (t) => {
  const { service, store } = await fixture(t);
  const first = await service.callTool("list_low_battery_vehicles", lowArgs);
  assert.deepEqual(validate(first, toolSchemas.get("list_low_battery_vehicles").output), []);
  assert.match(first.pagination.nextCursor, /^[A-Za-z0-9_-]{1,256}$/);
  const cursor = first.pagination.nextCursor;
  assert.equal((await service.callTool("list_low_battery_vehicles", { ...lowArgs, thresholdPercent: 10, cursor })).error.code, "CURSOR_EXPIRED");
  const second = await service.callTool("list_low_battery_vehicles", { ...lowArgs, cursor });
  assert.equal(second.vehicles[0].vehicleId, "veh_001");
  assert.deepEqual(second.pagination, { hasMore: false });
  assert.equal((await service.callTool("list_low_battery_vehicles", { ...lowArgs, cursor })).error.code, "CURSOR_EXPIRED");
  const restarted = createService({ backend: { vehicles: async () => vehicles }, areaMap: { area_campus: zone.id }, store });
  assert.equal((await restarted.callTool("list_low_battery_vehicles", { ...lowArgs, cursor })).error.code, "CURSOR_EXPIRED");
  const changed = createService({ backend: { vehicles: async () => vehicles.map(v => ({ ...v, batteryPercent: 1 })) }, areaMap: { area_campus: zone.id }, store });
  assert.equal((await changed.callTool("list_low_battery_vehicles", { ...lowArgs, cursor })).error.code, "CURSOR_EXPIRED");
});

test("unknown areas, invalid snapshots, missing timestamps and backend failures fail clearly", async (t) => {
  const { store, service } = await fixture(t);
  assert.equal((await service.callTool("get_vehicle_availability_by_area", { schemaVersion: "1.0", areaId: "area_unknown", pageSize: 10, maxReadModelAgeSeconds: 300 })).error.code, "UNKNOWN_AREA");
  for (const snapshot of [null, Array.from({ length: 500 }, (_, i) => ({ ...vehicles[0], id: `veh_${String(i).padStart(3,"0")}` })), [{ ...vehicles[0], id: "uuid" }], [{ ...vehicles[0], lastBatteryAt: null }], vehicles.map(v => ({ ...v, zoneId: "zone_unmapped" }))]) {
    const s = createService({ backend: { vehicles: async () => snapshot }, areaMap: { area_campus: zone.id }, store });
    assert.ok((await s.callTool("list_low_battery_vehicles", lowArgs)).error);
  }
  const failed = createService({ backend: { vehicles: async () => { throw new Error("connection failed"); } }, store });
  assert.equal((await failed.callTool("list_low_battery_vehicles", lowArgs)).error.code, "READ_MODEL_UNAVAILABLE");
});

test("availability returns null status-since and keeps minimum-battery filtering", async (t) => {
  const { service } = await fixture(t);
  const args = { schemaVersion: "1.0", areaId: "area_campus", pageSize: 10, maxReadModelAgeSeconds: 300 };
  const result = await service.callTool("get_vehicle_availability_by_area", args);
  assert.equal(result.vehicles[0].availableSince, null);
  assert.equal(result.availableCount, 1);
  assert.equal((await service.callTool("get_vehicle_availability_by_area", { ...args, minimumBatteryPercent: 13 })).availableCount, 0);
});

test("proposal evidence hashes the actual low-battery tool output", async (t) => {
  const { service } = await fixture(t);
  const result = await service.callTool("list_low_battery_vehicles", { schemaVersion: "1.0", thresholdPercent: 29, pageSize: 100, maxReadModelAgeSeconds: 300 });
  const { createHash } = await import("node:crypto");
  const proposal = await service.createProposal();
  assert.equal(proposal.snapshot.sources[0].resultSha256, createHash("sha256").update(JSON.stringify(result)).digest("hex"));
  assert.equal(proposal.actions[0].vehicleId, result.vehicles.find(v => v.operationalStatus === "AVAILABLE").vehicleId);
});

test("cursors expire and reject changed snapshots in the same process", async (t) => {
  const { store } = await fixture(t);
  let current = structuredClone(vehicles);
  let timestamp = now.getTime();
  const service = createService({ backend: { vehicles: async () => current }, areaMap: { area_campus: zone.id }, store, clock: () => new Date(timestamp) });
  const first = await service.callTool("list_low_battery_vehicles", lowArgs);
  current[0].batteryPercent = 11;
  assert.equal((await service.callTool("list_low_battery_vehicles", { ...lowArgs, cursor: first.pagination.nextCursor })).error.code, "CURSOR_EXPIRED");
  const next = await service.callTool("list_low_battery_vehicles", lowArgs);
  timestamp += 15 * 60 * 1000 + 1;
  assert.equal((await service.callTool("list_low_battery_vehicles", { ...lowArgs, cursor: next.pagination.nextCursor })).error.code, "CURSOR_EXPIRED");
});

test("availability pagination sorts backend IDs and retains the complete count", async (t) => {
  const { store } = await fixture(t);
  const service = createService({ backend: { vehicles: async () => [...vehicles].reverse().map(v => ({ ...v, status: "AVAILABLE" })) }, areaMap: { area_campus: zone.id }, store });
  const args = { schemaVersion: "1.0", areaId: "area_campus", pageSize: 2, maxReadModelAgeSeconds: 300 };
  const first = await service.callTool("get_vehicle_availability_by_area", args);
  assert.equal(first.availableCount, 3);
  assert.deepEqual(first.vehicles.map(v => v.vehicleId), ["veh_001", "veh_002"]);
  const last = await service.callTool("get_vehicle_availability_by_area", { ...args, cursor: first.pagination.nextCursor });
  assert.equal(last.availableCount, 3);
  assert.deepEqual(last.vehicles.map(v => v.vehicleId), ["veh_003"]);
});

test("planner follows tool pagination when available candidates occur after the first page", async (t) => {
  const { store } = await fixture(t);
  const fleet = Array.from({ length: 102 }, (_, i) => ({ ...vehicles[0], id: `veh_${String(i).padStart(3, "0")}`, status: i < 100 ? "MAINTENANCE_REQUIRED" : "AVAILABLE", batteryPercent: 10 }));
  const service = createService({ backend: { vehicles: async () => fleet }, areaMap: { area_campus: zone.id }, store });
  const proposal = await service.createProposal();
  assert.deepEqual(proposal.actions.map(v => v.vehicleId), ["veh_100", "veh_101"]);
  assert.equal(proposal.snapshot.sources.length, 2);
  assert.deepEqual(validateProposal(proposal), []);
});

test("published tool examples remain valid after nullable availability change", async () => {
  const names = { "list-low-battery-vehicles.result.json": "list_low_battery_vehicles", "get-vehicle-availability-by-area.result.json": "get_vehicle_availability_by_area", "get-demand-by-area.result.json": "get_demand_by_area", "get-demand-by-area.error-result.json": "get_demand_by_area" };
  for (const [file, tool] of Object.entries(names)) {
    const example = JSON.parse(await readFile(new URL(`../../contracts/ai-mcp/v1/examples/${file}`, import.meta.url), "utf8"));
    assert.deepEqual(validate(example, toolSchemas.get(tool).output), [], file);
  }
});
