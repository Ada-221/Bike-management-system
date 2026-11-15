import assert from "node:assert/strict";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { manifest, toolSchemas, validate, validateProposal } from "../src/contract.js";
import { createJsonStore, createService, statusForMcp, areaUuid, ServiceError } from "../src/service.js";
import { createHttpServer } from "../src/server.js";

const now = new Date("2026-11-10T10:00:00.000Z");
const zone = { id: "00000000-0000-4000-8000-000000000001", name: "Campus" };
const vehicles = [
  { id: "10000000-0000-4000-8000-000000000001", publicId: "veh_001", zoneId: zone.id, status: "AVAILABLE", batteryPercent: 12, latitude: 45.74, longitude: 21.20, lastBatteryMeasuredAt: "2026-11-10T09:59:00Z", lastLocationMeasuredAt: "2026-11-10T09:58:00Z", availableSince: "2026-11-10T09:00:00Z", updatedAt: "2026-11-10T09:59:00Z" },
  { id: "10000000-0000-4000-8000-000000000002", publicId: "veh_002", zoneId: zone.id, status: "RENTED", batteryPercent: 75, latitude: 45.75, longitude: 21.21, lastBatteryMeasuredAt: "2026-11-10T09:58:00Z", lastLocationMeasuredAt: "2026-11-10T09:57:00Z", updatedAt: "2026-11-10T09:58:00Z" },
  { id: "10000000-0000-4000-8000-000000000003", publicId: "veh_003", zoneId: zone.id, status: "MAINTENANCE_REQUIRED", batteryPercent: 9, latitude: 45.76, longitude: 21.22, lastBatteryMeasuredAt: "2026-11-10T09:57:00Z", lastLocationMeasuredAt: "2026-11-10T09:56:00Z", updatedAt: "2026-11-10T09:57:00Z" },
];

async function fixture(t) {
  const directory = await mkdtemp(path.join(os.tmpdir(), "cebp-mcp-"));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const store = createJsonStore(directory);
  const backend = { vehicles: async () => structuredClone(vehicles), zones: async () => [zone] };
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
  }
});

test("status adapter preserves backend vocabulary and rejects unsettled OFFLINE inference", () => {
  assert.equal(statusForMcp("RENTED"), "IN_USE");
  assert.equal(statusForMcp("MAINTENANCE_REQUIRED"), "MAINTENANCE");
  assert.throws(() => statusForMcp("OFFLINE"), ServiceError);
  assert.equal(areaUuid("area_campus", { area_campus: zone.id }, [zone]), zone.id);
  assert.throws(() => areaUuid("area_guess", {}, [zone]), (error) => error.code === "UNKNOWN_AREA");
  assert.throws(() => areaUuid("area_campus", { area_campus: "guessed-uuid" }, [zone]), (error) => error.code === "UNKNOWN_AREA");
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
  const { service, store } = await fixture(t);
  const before = JSON.stringify(await service.loadState());
  const proposal = await service.createProposal();
  const repeatedProposal = await service.createProposal();
  assert.deepEqual(repeatedProposal, proposal);
  assert.deepEqual(validateProposal(proposal), []);
  assert.equal((await store.getProposal(proposal.proposalId)).status, "PENDING_APPROVAL");
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
