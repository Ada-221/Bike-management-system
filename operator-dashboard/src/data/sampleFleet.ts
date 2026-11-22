import type { FleetStatus, Vehicle, Zone } from "../contracts/fleet";
import { FleetApiError, type FleetSource } from "../api/fleet";

// Synthetic, fixed snapshots. No backend, telemetry, or MCP connection.
export const sampleZones = [
  {
    id: "zone_001",
    name: "Campus",
    centerLatitude: 48.21,
    centerLongitude: 16.36,
    radiusMeters: 1000,
  },
  {
    id: "zone_002",
    name: "Central station",
    centerLatitude: 48.1858,
    centerLongitude: 16.3758,
    radiusMeters: 800,
  },
] satisfies Zone[];

export const sampleVehicles = [
  {
    id: "veh_001",
    label: "EB-001",
    type: "E_BIKE",
    status: "AVAILABLE",
    batteryPercent: 92,
    latitude: 48.2102,
    longitude: 16.3598,
    zoneId: sampleZones[0].id,
    rentable: true,
  },
  {
    id: "veh_002",
    label: "ES-002",
    type: "E_SCOOTER",
    status: "RENTED",
    batteryPercent: 64,
    latitude: 48.186,
    longitude: 16.375,
    zoneId: sampleZones[1].id,
    rentable: false,
  },
  {
    id: "veh_003",
    label: "EB-003",
    type: "E_BIKE",
    status: "MAINTENANCE_REQUIRED",
    batteryPercent: 12,
    latitude: 48.211,
    longitude: 16.361,
    zoneId: sampleZones[0].id,
    rentable: false,
  },
  {
    id: "veh_004",
    label: "ES-004",
    type: "E_SCOOTER",
    status: "AVAILABLE",
    batteryPercent: 76,
    latitude: 48.185,
    longitude: 16.376,
    zoneId: sampleZones[1].id,
    rentable: true,
  },
] satisfies Vehicle[];

export function sampleStatus(): FleetStatus {
  return {
    generatedAt: new Date().toISOString(),
    totalVehicles: sampleVehicles.length,
    countByStatus: {
      AVAILABLE: 2,
      RENTED: 1,
      MAINTENANCE_REQUIRED: 1,
    },
    activeRentals: 1,
    lowBatteryCount: 1,
    averageBatteryPercent: 61,
    zones: sampleZones.map((zone) => ({
      zoneId: zone.id,
      zoneName: zone.name,
      available: sampleVehicles.filter(
        (v) => v.zoneId === zone.id && v.status === "AVAILABLE",
      ).length,
      rented: sampleVehicles.filter(
        (v) => v.zoneId === zone.id && v.status === "RENTED",
      ).length,
      maintenanceRequired: sampleVehicles.filter(
        (v) => v.zoneId === zone.id && v.status === "MAINTENANCE_REQUIRED",
      ).length,
    })),
  };
}

// Selected explicitly by the operator; never used as a fallback for API errors.
export const demoFleet: FleetSource = {
  listVehicles: async (_signal, filters = {}) =>
    structuredClone(
      sampleVehicles.filter(
        (vehicle) =>
          (!filters.status || vehicle.status === filters.status) &&
          (filters.maxBattery === undefined ||
            vehicle.batteryPercent <= filters.maxBattery),
      ),
    ),
  getStatus: async () => sampleStatus(),
  getVehicle: async (vehicleId) => {
    const vehicle = sampleVehicles.find((vehicle) => vehicle.id === vehicleId);
    if (!vehicle)
      throw new FleetApiError(
        "The requested fleet resource was not found.",
        "NOT_FOUND",
        404,
      );
    return structuredClone(vehicle);
  },
  getMaintenance: async (vehicleId) =>
    vehicleId === "veh_003"
      ? [
          {
            id: "mnt_demo_001",
            vehicleId,
            reason: "LOW_BATTERY",
            status: "OPEN",
            faultCode: null,
            description: "Battery below the rental threshold.",
            openedAt: "2026-11-18T09:30:00Z",
            resolvedAt: null,
          },
        ]
      : [],
};
