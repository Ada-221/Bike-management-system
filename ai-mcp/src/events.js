import { createHash } from "node:crypto";

export const TOPICS = ["fleet.vehicle", "fleet.rental", "fleet.maintenance"];
export const DRAFT_CONTRACT = "person5-week3-draft-v1";
export const FLEET_CONTRACT = "fleet-week3-v1";
const routes = {
  VehicleRegistered: "fleet.vehicle", VehicleStateUpdated: "fleet.vehicle",
  VehicleBatteryUpdated: "fleet.vehicle", VehicleLocationUpdated: "fleet.vehicle",
  RentalStarted: "fleet.rental", RentalEnded: "fleet.rental",
  VehicleMaintenanceChanged: "fleet.maintenance",
};
export class IntegrityError extends Error { constructor(message) { super(message); this.name = "IntegrityError"; } }
const requireValue = (condition, message) => { if (!condition) throw new IntegrityError(message); };
const id = (v, prefix) => typeof v === "string" && new RegExp(`^${prefix}_[A-Za-z0-9][A-Za-z0-9_-]{2,63}$`).test(v);
const timestamp = (v) => typeof v === "string" && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/.test(v) && Number.isFinite(Date.parse(v)) && new Date(v).toISOString().slice(0, 19) === v.slice(0, 19);
// Java Instant may publish nanoseconds. Normalize before comparison so no
// sub-millisecond ordering evidence is lost to JavaScript Date truncation.
export const timeOrder = (v) => v.slice(0, 19) + "." + (v.slice(19, -1).replace(/^\./, "")).padEnd(9, "0");
const percent = (v) => Number.isInteger(v) && v >= 0 && v <= 100;
const location = (v) => Number.isFinite(v.latitude) && v.latitude >= -90 && v.latitude <= 90 && Number.isFinite(v.longitude) && v.longitude >= -180 && v.longitude <= 180;
const status = (v) => ["AVAILABLE", "RENTED", "MAINTENANCE_REQUIRED"].includes(v);

export function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  if (value && typeof value === "object") return `{${Object.keys(value).sort().map((k) => `${JSON.stringify(k)}:${canonical(value[k])}`).join(",")}}`;
  return JSON.stringify(value);
}
export const fingerprint = (value) => createHash("sha256").update(canonical(value)).digest("hex");

// Backend shapes below are a consumer fixture proposal, never a silent adapter
// for the Week 2 producer. Production opt-in requires affected-owner agreement.
export function normalizeEvent({ topic, key, value }, { backendContract = "" } = {}) {
  requireValue(TOPICS.includes(topic), `Unexpected topic ${topic}`);
  let event;
  try { event = JSON.parse(Buffer.isBuffer(value) ? value.toString("utf8") : value); }
  catch { throw new IntegrityError("Record value must be JSON with an eventType (tombstones are unsupported)"); }
  requireValue(event && typeof event === "object" && !Array.isArray(event) && typeof event.eventType === "string", "Record requires an eventType");
  if (!Object.hasOwn(routes, event.eventType)) return null; // Explicit unrelated-event checkpoint policy.
  requireValue(routes[event.eventType] === topic, `${event.eventType} must use ${routes[event.eventType]}`);
  requireValue(id(event.eventId, "evt") && id(event.vehicleId, "veh"), "Invalid eventId or vehicleId");
  requireValue((Buffer.isBuffer(key) ? key.toString("utf8") : key) === event.vehicleId, "Kafka record key must equal vehicleId");
  requireValue(event.schemaVersion === "1.0", `Unsupported schemaVersion ${event.schemaVersion}`);
  requireValue(timestamp(event.publishedAt), "publishedAt must be a UTC timestamp with up to nanosecond precision");
  requireValue(typeof event.correlationId === "string" && event.correlationId.length > 0, "correlationId is required");
  const telemetry = ["VehicleBatteryUpdated", "VehicleLocationUpdated"].includes(event.eventType);
  requireValue(event.producer === (telemetry ? "telemetry-ingestion-service" : "fleet-management-service"), "Unexpected producer");
  const p = event.payload;
  requireValue(p && typeof p === "object" && !Array.isArray(p), "payload must be an object; Week 2 flat backend events are not supported");
  if (telemetry) {
    requireValue(timestamp(event.measuredAt), "measuredAt must be a UTC measurement timestamp");
    requireValue(Object.keys(event).every((k) => ["eventId", "eventType", "schemaVersion", "vehicleId", "publishedAt", "measuredAt", "correlationId", "producer", "payload"].includes(k)), "Unexpected telemetry envelope field");
    if (event.eventType === "VehicleBatteryUpdated") requireValue(percent(p.batteryPercent) && Object.keys(p).length === 1, "Battery payload requires only batteryPercent (integer 0..100)");
    else requireValue(location(p) && Object.keys(p).length === 2, "Location payload requires only valid latitude and longitude");
  } else {
    const current = backendContract === FLEET_CONTRACT;
    requireValue(current || backendContract === DRAFT_CONTRACT, `Backend event contract is gated; configure ${FLEET_CONTRACT} after owner review`);
    requireValue(Number.isSafeInteger(event.stateRevision) && event.stateRevision >= 0, "stateRevision must be a nonnegative authoritative per-vehicle integer");
    requireValue(Object.keys(event).every((k) => ["eventId", "eventType", "schemaVersion", "vehicleId", "publishedAt", "stateRevision", "correlationId", "producer", "payload"].includes(k)), "Unexpected backend envelope field");
    const validZone = id(p.zoneId, "zone") || (current && p.zoneId === null);
    if (event.eventType === "VehicleRegistered") {
      requireValue(status(p.status) && validZone && percent(p.batteryPercent) && location(p) && timestamp(p.batteryMeasuredAt) && timestamp(p.locationMeasuredAt), "Registration requires full initial status, zone, battery, location and actual measurement timestamps");
      requireValue(Object.keys(p).every((k) => ["status", "zoneId", "batteryPercent", "latitude", "longitude", "batteryMeasuredAt", "locationMeasuredAt"].includes(k)), "Unexpected registration payload field");
    } else {
      requireValue(status(p.status), "State events require authoritative resulting status");
      if (current || p.zoneId !== undefined) requireValue(validZone, "zoneId must be backend issued or explicitly null");
      if (current) validateFleetDetails(event.eventType, p);
      else requireValue(Object.keys(p).every((k) => ["status", "zoneId"].includes(k)), "Unexpected state payload field");
    }
  }
  return { ...event, digest: fingerprint({ topic, event }) };
}

function validateFleetDetails(type, p) {
  let details = [];
  if (type === "RentalStarted" || type === "RentalEnded") {
    requireValue(id(p.rentalId, "rnt") && typeof p.riderId === "string" && p.riderId.length > 0
      && timestamp(p.startedAt), "Invalid rental identity or start timestamp");
    details = ["rentalId", "riderId", "startedAt"];
    if (type === "RentalStarted") {
      requireValue(location({ latitude: p.startLatitude, longitude: p.startLongitude }) && percent(p.startBatteryPercent), "Invalid rental start snapshot");
      details.push("startLatitude", "startLongitude", "startBatteryPercent");
    } else {
      requireValue(location({ latitude: p.endLatitude, longitude: p.endLongitude }) && percent(p.endBatteryPercent)
        && timestamp(p.endedAt) && Number.isSafeInteger(p.durationSeconds) && p.durationSeconds >= 0, "Invalid rental return snapshot");
      if (Object.hasOwn(p, "locationMeasuredAt")) requireValue(timestamp(p.locationMeasuredAt), "Invalid return location timestamp");
      details.push("endLatitude", "endLongitude", "endBatteryPercent", "endedAt", "durationSeconds", "locationMeasuredAt");
    }
  }
  if (type === "VehicleMaintenanceChanged") {
    const statuses = ["OPEN", "IN_PROGRESS", "RESOLVED"];
    requireValue(id(p.maintenanceId, "mnt") && ["FAULT", "MANUAL", "LOW_BATTERY"].includes(p.reason)
      && (p.previousStatus === null || statuses.includes(p.previousStatus)) && statuses.includes(p.newStatus)
      && (p.faultCode === null || typeof p.faultCode === "string") && timestamp(p.changedAt), "Invalid maintenance transition");
    details = ["maintenanceId", "reason", "previousStatus", "newStatus", "faultCode", "changedAt"];
  }
  requireValue(Object.keys(p).every((k) => ["status", "zoneId", ...details].includes(k)), "Unexpected state payload field");
}
