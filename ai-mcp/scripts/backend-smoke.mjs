// Run inside the isolated MCP container; see week-2-verification.md.
import assert from "node:assert/strict";
import { readFile, writeFile } from "node:fs/promises";
const mcp = "http://127.0.0.1:8083";
const backend = process.env.BACKEND_API_URL;
async function request(url, body) {
  const response = await fetch(url, body === undefined ? {} : { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });
  assert.ok(response.ok, `${url}: HTTP ${response.status} ${await response.clone().text()}`);
  return response.json();
}
const savedFile = "/data/smoke-proposal.json";
if (process.argv.includes("verify")) {
  const saved = JSON.parse(await readFile(savedFile, "utf8"));
  const proposal = await request(`${mcp}/api/v1/proposals/${saved.proposalId}`);
  assert.equal(proposal.status, "APPROVED");
  assert.equal(proposal.operationalStateChanged, false);
  assert.deepEqual(await request(`${backend}vehicles?limit=500`), saved.fleet);
  console.log("PASS: approved proposal persisted across container restart; fleet unchanged");
} else {
  assert.equal((await request(`${mcp}/health`)).status, "UP");
  assert.equal((await request(`${mcp}/ready`)).status, "READY");
  for (const [label, batteryPercent] of [["MCP smoke low", 12], ["MCP smoke high", 80]]) {
    const created = await request(`${backend}vehicles`, { label, type: "E_BIKE", batteryPercent, latitude: 48.2100, longitude: 16.3600 });
    assert.match(created.id, /^veh_/);
    assert.equal(created.zoneId, "zone_001");
  }
  async function tool(name, args) {
    const rpc = await request(`${mcp}/mcp`, { jsonrpc: "2.0", id: name, method: "tools/call", _meta: { "io.modelcontextprotocol/protocolVersion": "2026-07-28" }, params: { name, arguments: { schemaVersion: "1.0", pageSize: 10, maxReadModelAgeSeconds: 300, ...args } } });
    assert.equal(rpc.result.isError, false, JSON.stringify(rpc));
    return rpc.result.structuredContent;
  }
  const low = await tool("list_low_battery_vehicles", { thresholdPercent: 20 });
  assert.equal(low.vehicles.length, 1);
  assert.equal(low.vehicles[0].batteryPercent, 12);
  const available = await tool("get_vehicle_availability_by_area", { areaId: "area_center", minimumBatteryPercent: 50 });
  assert.equal(available.availableCount, 1);
  assert.equal(available.vehicles[0].batteryPercent, 80);
  assert.equal(available.vehicles[0].availableSince, null);
  const fleet = await request(`${backend}vehicles?limit=500`);
  const dto = fleet.find(v => v.id === available.vehicles[0].vehicleId);
  assert.equal(available.vehicles[0].batteryMeasuredAt, dto.lastBatteryAt);
  assert.equal(available.vehicles[0].locationMeasuredAt, dto.lastLocationAt);
  const run = await request(`${mcp}/api/v1/planner/runs`, {});
  assert.equal(run.status, "COMPLETED", JSON.stringify(run));
  assert.equal(run.proposal.actions[0].vehicleId, low.vehicles[0].vehicleId);
  const approved = await request(`${mcp}/api/v1/proposals/${run.proposal.proposalId}/approve`, { operatorId: "operator_smoke" });
  assert.equal(approved.status, "APPROVED");
  assert.equal(approved.operationalStateChanged, false);
  assert.deepEqual(await request(`${backend}vehicles?limit=500`), fleet);
  await writeFile(savedFile, JSON.stringify({ proposalId: approved.proposalId, fleet }));
  console.log("PASS: health/readiness, real DTOs, tool queries, planner and advisory approval against PostgreSQL/backend");
}
