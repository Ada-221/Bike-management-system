import type {
  FleetStatus,
  Vehicle,
  MaintenanceRecord,
} from "../src/contracts/fleet";

export const apiVehicles: Vehicle[] = [
  {
    id: "veh_101",
    label: "API-BIKE-101",
    type: "E_BIKE",
    status: "AVAILABLE",
    batteryPercent: 92,
    latitude: 48.2102,
    longitude: 16.3598,
    zoneId: "zone_001",
    rentable: true,
    lastBatteryAt: "2026-11-18T09:30:00Z",
    lastLocationAt: null,
    lastTelemetryAt: "2026-11-18T09:30:00Z",
  },
  {
    id: "veh_102",
    label: "API-SCOOTER-102",
    type: "E_SCOOTER",
    status: "RENTED",
    batteryPercent: 64,
    latitude: 48.186,
    longitude: 16.375,
    zoneId: "zone_002",
    rentable: false,
  },
  {
    id: "veh_103",
    label: "API-BIKE-103",
    type: "E_BIKE",
    status: "MAINTENANCE_REQUIRED",
    batteryPercent: 12,
    latitude: 48.211,
    longitude: 16.361,
    zoneId: "zone_001",
    rentable: false,
  },
  {
    id: "veh_104",
    label: "API-SCOOTER-104",
    type: "E_SCOOTER",
    status: "AVAILABLE",
    batteryPercent: 76,
    latitude: 48.185,
    longitude: 16.376,
    zoneId: null,
    rentable: true,
  },
];

export const apiStatus: FleetStatus = {
  generatedAt: "2026-11-18T09:31:00Z",
  totalVehicles: 4,
  countByStatus: { AVAILABLE: 2, RENTED: 1, MAINTENANCE_REQUIRED: 1 },
  // One of these trips has a fault; RENTED vehicle count is not active rental count.
  activeRentals: 2,
  lowBatteryCount: 1,
  averageBatteryPercent: 61,
  zones: [
    {
      zoneId: "zone_001",
      zoneName: "Backend campus",
      available: 1,
      rented: 0,
      maintenanceRequired: 1,
    },
    {
      zoneId: "zone_002",
      zoneName: "Backend station",
      available: 0,
      rented: 1,
      maintenanceRequired: 0,
    },
  ],
};

export const apiMaintenance: MaintenanceRecord[] = [
  {
    id: "mnt_101",
    vehicleId: "veh_103",
    reason: "FAULT",
    status: "OPEN",
    faultCode: "BRAKE_SENSOR",
    description: "Synthetic brake sensor fault.",
    openedAt: "2026-11-18T09:30:00Z",
    resolvedAt: null,
  },
];
