import { mkdtemp, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { createJsonStore } from "../src/store.js";
import { createProjection } from "../src/projection.js";
import { DRAFT_CONTRACT, TOPICS } from "../src/events.js";
import { createService } from "../src/service.js";

export const NOW = "2026-11-16T10:00:00.000Z";
let sequence = 0;
export function event(eventType = "VehicleRegistered", payload, overrides = {}) {
  const telemetry = ["VehicleBatteryUpdated", "VehicleLocationUpdated"].includes(eventType);
  return {
    eventId: `evt_${String(++sequence).padStart(6, "0")}`, eventType, schemaVersion: "1.0", vehicleId: "veh_001",
    publishedAt: NOW, correlationId: "corr_test", producer: telemetry ? "telemetry-ingestion-service" : "fleet-management-service",
    ...(telemetry ? { measuredAt: NOW } : { stateRevision: 1 }),
    payload: payload ?? { status: "AVAILABLE", zoneId: "zone_001", batteryPercent: 12, latitude: 45.74, longitude: 21.2, batteryMeasuredAt: "2026-11-16T09:00:00Z", locationMeasuredAt: "2026-11-16T09:00:01Z" }, ...overrides,
  };
}
export function record(e, offset = "0", partition = 0, topic) {
  return { topic: topic ?? (e.eventType.startsWith("Rental") ? "fleet.rental" : e.eventType === "VehicleMaintenanceChanged" ? "fleet.maintenance" : "fleet.vehicle"), partition, offset, key: e.vehicleId, value: JSON.stringify(e) };
}
export const lowArgs = { schemaVersion: "1.0", thresholdPercent: 20, pageSize: 100, maxReadModelAgeSeconds: 300 };
export const availableArgs = { schemaVersion: "1.0", areaId: "area_center", pageSize: 100, maxReadModelAgeSeconds: 300 };
export async function fixture(t, options = {}) {
  const directory = await mkdtemp(path.join(os.tmpdir(), "mcp-projection-"));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const store = createJsonStore(directory, options.storage);
  const clock = options.clock ?? (() => new Date(NOW));
  const projection = await createProjection({ store, clock, backendContract: DRAFT_CONTRACT });
  const service = createService({ snapshotProvider: projection, store, clock, areaMap: { area_center: "zone_001", area_south: "zone_002" } });
  async function confirm() {
    const state = await projection.inspect();
    return projection.confirm(TOPICS.map((topic) => ({ topic, partition: 0, low: "0", high: state.checkpoints[`${topic}:0`] ?? "0" })));
  }
  return { directory, store, projection, service, confirm };
}
