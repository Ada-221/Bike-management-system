/**
 * Read shapes from the implemented fleet controllers at bac920b.
 * IDs are opaque strings (currently veh_... and zone_...). The API has no
 * /zones endpoint: display names come from FleetStatus.zones.
 * Optional telemetry fields also accept null. Boundary validation lives in api/.
 */
export type VehicleStatus = "AVAILABLE" | "RENTED" | "MAINTENANCE_REQUIRED";
export type VehicleType = "E_BIKE" | "E_SCOOTER";

export interface VehicleFilters {
  status?: VehicleStatus;
  maxBattery?: number;
}

export interface MaintenanceRecord {
  id: string;
  vehicleId: string;
  reason: "LOW_BATTERY" | "FAULT" | "MANUAL";
  status: "OPEN" | "IN_PROGRESS" | "RESOLVED";
  faultCode?: string | null;
  description?: string | null;
  openedAt: string;
  resolvedAt?: string | null;
}

export interface Vehicle {
  id: string;
  label: string;
  type: VehicleType;
  status: VehicleStatus;
  batteryPercent: number;
  latitude: number;
  longitude: number;
  zoneId?: string | null;
  rentable: boolean;
  lastBatteryAt?: string | null;
  lastLocationAt?: string | null;
  lastTelemetryAt?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface FleetStatus {
  generatedAt: string;
  totalVehicles: number;
  countByStatus: Record<VehicleStatus, number>;
  activeRentals: number;
  lowBatteryCount: number;
  averageBatteryPercent: number;
  zones: ZoneStatus[];
}

export interface ZoneStatus {
  zoneId: string;
  zoneName: string;
  available: number;
  rented: number;
  maintenanceRequired: number;
}

export interface Zone {
  id: string;
  name: string;
  centerLatitude: number;
  centerLongitude: number;
  radiusMeters: number;
}
