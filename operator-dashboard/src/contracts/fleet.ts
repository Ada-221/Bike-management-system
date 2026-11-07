/**
 * Read-only subset of Person 1's OpenAPI v0.1 Vehicle and Zone schemas.
 * Source: origin/feature/fleet-domain-model @ 312eade, api/fleet-api.yaml.
 * Optional/null fields follow the draft; IDs are opaque to the UI.
 * These types describe data, not runtime validation or an HTTP client.
 */
export type VehicleStatus = "AVAILABLE" | "RENTED" | "MAINTENANCE_REQUIRED";
export type VehicleType = "E_BIKE" | "E_SCOOTER";

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
  lastTelemetryAt?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface Zone {
  id: string;
  name: string;
  centerLatitude: number;
  centerLongitude: number;
  radiusMeters: number;
}
