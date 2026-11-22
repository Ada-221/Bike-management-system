import type {
  FleetStatus,
  MaintenanceRecord,
  Vehicle,
  VehicleFilters,
} from "../contracts/fleet";

export const VEHICLE_LIMIT = 500;

export class FleetApiError extends Error {
  constructor(
    message: string,
    readonly code: string,
    readonly status?: number,
    readonly correlationId?: string,
  ) {
    super(message);
    this.name = "FleetApiError";
  }
}

type JsonObject = Record<string, unknown>;
const isObject = (value: unknown): value is JsonObject =>
  typeof value === "object" && value !== null && !Array.isArray(value);
const isText = (value: unknown): value is string =>
  typeof value === "string" && value.trim().length > 0;
const isNumber = (value: unknown): value is number =>
  typeof value === "number" && Number.isFinite(value);
const isCount = (value: unknown): value is number =>
  isNumber(value) && Number.isInteger(value) && value >= 0;
const isPercent = (value: unknown): value is number =>
  isNumber(value) && value >= 0 && value <= 100;
const isTime = (value: unknown): value is string =>
  isText(value) && value.endsWith("Z") && Number.isFinite(Date.parse(value));
const optionalTime = (value: unknown) =>
  value === undefined || value === null || isTime(value);
const statuses = ["AVAILABLE", "RENTED", "MAINTENANCE_REQUIRED"];

function isVehicle(value: unknown): value is Vehicle {
  if (!isObject(value)) return false;
  return (
    isText(value.id) &&
    isText(value.label) &&
    isText(value.type) &&
    ["E_BIKE", "E_SCOOTER"].includes(value.type) &&
    isText(value.status) &&
    statuses.includes(value.status) &&
    isPercent(value.batteryPercent) &&
    Number.isInteger(value.batteryPercent) &&
    isNumber(value.latitude) &&
    Math.abs(value.latitude) <= 90 &&
    isNumber(value.longitude) &&
    Math.abs(value.longitude) <= 180 &&
    (value.zoneId === undefined ||
      value.zoneId === null ||
      isText(value.zoneId)) &&
    typeof value.rentable === "boolean" &&
    optionalTime(value.lastBatteryAt) &&
    optionalTime(value.lastLocationAt) &&
    optionalTime(value.lastTelemetryAt) &&
    (value.createdAt === undefined || isTime(value.createdAt)) &&
    (value.updatedAt === undefined || isTime(value.updatedAt))
  );
}

function isFleetStatus(value: unknown): value is FleetStatus {
  if (!isObject(value) || !isObject(value.countByStatus)) return false;
  const counts = value.countByStatus;
  return (
    isTime(value.generatedAt) &&
    isCount(value.totalVehicles) &&
    statuses.every((status) => isCount(counts[status])) &&
    isCount(value.activeRentals) &&
    isCount(value.lowBatteryCount) &&
    isPercent(value.averageBatteryPercent) &&
    Array.isArray(value.zones) &&
    value.zones.every(
      (zone) =>
        isObject(zone) &&
        isText(zone.zoneId) &&
        isText(zone.zoneName) &&
        isCount(zone.available) &&
        isCount(zone.rented) &&
        isCount(zone.maintenanceRequired),
    )
  );
}

export interface FleetSource {
  listVehicles(
    signal?: AbortSignal,
    filters?: VehicleFilters,
  ): Promise<Vehicle[]>;
  getStatus(signal?: AbortSignal): Promise<FleetStatus>;
  getVehicle(vehicleId: string, signal?: AbortSignal): Promise<Vehicle>;
  getMaintenance(
    vehicleId: string,
    signal?: AbortSignal,
  ): Promise<MaintenanceRecord[]>;
}

function isMaintenance(value: unknown): value is MaintenanceRecord {
  return (
    isObject(value) &&
    isText(value.id) &&
    isText(value.vehicleId) &&
    isText(value.reason) &&
    ["LOW_BATTERY", "FAULT", "MANUAL"].includes(value.reason) &&
    isText(value.status) &&
    ["OPEN", "IN_PROGRESS", "RESOLVED"].includes(value.status) &&
    (value.faultCode == null || typeof value.faultCode === "string") &&
    (value.description == null || typeof value.description === "string") &&
    isTime(value.openedAt) &&
    optionalTime(value.resolvedAt)
  );
}

/** Same-origin requests work through Vite locally and the team's Nginx in Docker. */
export function createFleetApi({
  fetcher = globalThis.fetch,
  timeoutMs = 10000,
}: { fetcher?: typeof fetch; timeoutMs?: number } = {}): FleetSource {
  async function get<T>(
    path: string,
    validate: (value: unknown) => value is T,
    signal?: AbortSignal,
  ): Promise<T> {
    const controller = new AbortController();
    const cancel = () => controller.abort(signal?.reason);
    signal?.addEventListener("abort", cancel, { once: true });
    if (signal?.aborted) cancel();
    let timedOut = false;
    const timer = setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, timeoutMs);
    try {
      const response = await fetcher(`/api/v1${path}`, {
        method: "GET",
        cache: "no-store",
        signal: controller.signal,
        headers: {
          Accept: "application/json",
          "X-Correlation-Id": crypto.randomUUID(),
        },
      });
      let body: unknown;
      try {
        body = await response.json();
      } catch (error) {
        if (controller.signal.aborted) throw error;
        throw new FleetApiError(
          response.ok
            ? "The fleet service returned an unexpected response."
            : "The fleet service is unavailable. Try again.",
          response.ok ? "INVALID_RESPONSE" : "HTTP_ERROR",
          response.status,
        );
      }
      if (!response.ok) {
        const code =
          isObject(body) && isText(body.code) ? body.code : "HTTP_ERROR";
        const correlationId =
          isObject(body) && isText(body.correlationId)
            ? body.correlationId
            : undefined;
        // Do not expose arbitrary server text or an HTML proxy error in the interface.
        const message =
          response.status >= 500
            ? "The fleet service is unavailable. Try again."
            : code === "VALIDATION_ERROR"
              ? "The fleet service could not accept this request."
              : code === "NOT_FOUND"
                ? "The requested fleet resource was not found."
                : "Unable to load fleet data. Try again.";
        throw new FleetApiError(message, code, response.status, correlationId);
      }
      if (!validate(body))
        throw new FleetApiError(
          "The fleet service returned an unexpected response.",
          "INVALID_RESPONSE",
          response.status,
        );
      return body;
    } catch (error) {
      if (signal?.aborted) throw error;
      if (timedOut)
        throw new FleetApiError(
          "The fleet service took too long to respond. Try again.",
          "TIMEOUT",
        );
      if (error instanceof FleetApiError) throw error;
      throw new FleetApiError(
        "Cannot reach the fleet service. Check that the backend is running, then try again.",
        "NETWORK_ERROR",
      );
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener("abort", cancel);
    }
  }
  return {
    listVehicles: (signal, filters = {}) => {
      if (
        (filters.status !== undefined && !statuses.includes(filters.status)) ||
        (filters.maxBattery !== undefined &&
          (!isPercent(filters.maxBattery) ||
            !Number.isInteger(filters.maxBattery)))
      ) {
        return Promise.reject(
          new FleetApiError(
            "Choose a valid status and a whole-number battery limit from 0 to 100.",
            "INVALID_FILTER",
          ),
        );
      }
      const query = new URLSearchParams({ limit: String(VEHICLE_LIMIT) });
      if (filters.status !== undefined) query.set("status", filters.status);
      if (filters.maxBattery !== undefined)
        query.set("maxBattery", String(filters.maxBattery));
      return get(
        `/vehicles?${query}`,
        (value): value is Vehicle[] =>
          Array.isArray(value) &&
          value.every(isVehicle) &&
          new Set(value.map((v) => v.id)).size === value.length,
        signal,
      );
    },
    getStatus: (signal) => get("/fleet/status", isFleetStatus, signal),
    getVehicle: (vehicleId, signal) =>
      get(
        `/vehicles/${encodeURIComponent(vehicleId)}`,
        (value): value is Vehicle => isVehicle(value) && value.id === vehicleId,
        signal,
      ),
    getMaintenance: (vehicleId, signal) =>
      get(
        `/maintenance?${new URLSearchParams({ vehicleId, limit: String(VEHICLE_LIMIT) })}`,
        (value): value is MaintenanceRecord[] =>
          Array.isArray(value) &&
          value.every(
            (record) =>
              isMaintenance(record) &&
              record.vehicleId === vehicleId &&
              record.status !== "RESOLVED",
          ) &&
          new Set(value.map((record) => record.id)).size === value.length,
        signal,
      ),
  };
}

export const fleetApi = createFleetApi();
