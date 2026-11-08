import type { Vehicle, Zone } from "../contracts/fleet";

// Synthetic, fixed snapshots. No backend, telemetry, or MCP connection.
export const sampleZones = [
  {
    id: "a0000000-0000-0000-0000-000000000001",
    name: "Campus",
    centerLatitude: 48.21,
    centerLongitude: 16.36,
    radiusMeters: 1000,
  },
  {
    id: "a0000000-0000-0000-0000-000000000002",
    name: "Central station",
    centerLatitude: 48.1858,
    centerLongitude: 16.3758,
    radiusMeters: 800,
  },
] satisfies Zone[];

export const sampleVehicles = [
  {
    id: "b0000000-0000-0000-0000-000000000001",
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
    id: "b0000000-0000-0000-0000-000000000002",
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
    id: "b0000000-0000-0000-0000-000000000003",
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
    id: "b0000000-0000-0000-0000-000000000004",
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
