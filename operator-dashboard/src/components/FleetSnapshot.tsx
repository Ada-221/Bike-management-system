import type { Vehicle, VehicleStatus, ZoneStatus } from "../contracts/fleet";
import { VEHICLE_LIMIT } from "../api/fleet";

const statusLabels: Record<VehicleStatus, string> = {
  AVAILABLE: "Available",
  RENTED: "On a ride",
  MAINTENANCE_REQUIRED: "Needs maintenance",
};

export function FleetSnapshot({
  vehicles,
  zones,
  total,
  demo,
  filtered = false,
  onSelect,
}: {
  vehicles: Vehicle[];
  zones: ZoneStatus[];
  total?: number;
  demo: boolean;
  filtered?: boolean;
  onSelect?: (vehicleId: string) => void;
}) {
  return (
    <section className="panel" aria-labelledby="snapshot-title">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">Fleet snapshot</p>
          <h2 id="snapshot-title">A closer look</h2>
        </div>
        <span className="quiet-label">
          {vehicles.length} {demo ? "demo " : ""}vehicles shown
        </span>
      </div>
      {vehicles.length === 0 ? (
        <div className="resource-state" role="status">
          <h3>{filtered ? "No matching vehicles" : "No vehicles yet"}</h3>
          <p>
            {filtered
              ? "Try a different status or battery limit, or clear the filters."
              : "No vehicles were returned by the fleet service. Refresh after a vehicle is registered."}
          </p>
        </div>
      ) : (
        <>
          <div
            className="table-scroll"
            role="region"
            aria-label="Fleet vehicle table"
            tabIndex={0}
          >
            <table>
              <caption className="sr-only">
                {demo
                  ? "Synthetic demo vehicles"
                  : "Vehicles retrieved from the fleet service"}
              </caption>
              <thead>
                <tr>
                  <th scope="col">Vehicle</th>
                  <th scope="col">Area</th>
                  <th scope="col">Battery</th>
                  <th scope="col">Status</th>
                </tr>
              </thead>
              <tbody>
                {vehicles.map((vehicle) => (
                  <tr key={vehicle.id}>
                    <th scope="row">
                      {onSelect ? (
                        <button
                          className="vehicle-link"
                          aria-label={`View ${vehicle.label} details`}
                          onClick={() => onSelect(vehicle.id)}
                        >
                          {vehicle.label}
                        </button>
                      ) : (
                        <span className="vehicle-label">{vehicle.label}</span>
                      )}
                      <span className="vehicle-type">
                        {vehicle.type === "E_BIKE" ? "E-bike" : "E-scooter"}
                      </span>
                    </th>
                    <td>
                      {!vehicle.zoneId
                        ? "Unassigned"
                        : (zones.find((zone) => zone.zoneId === vehicle.zoneId)
                            ?.zoneName ??
                          `${vehicle.zoneId} (name unavailable)`)}
                    </td>
                    <td>
                      <span
                        className={`battery ${vehicle.batteryPercent < 20 ? "battery-low" : ""}`}
                      >
                        <span className="battery-track" aria-hidden="true">
                          <span
                            style={{ width: `${vehicle.batteryPercent}%` }}
                          />
                        </span>
                        {vehicle.batteryPercent}%
                      </span>
                    </td>
                    <td>
                      <span
                        className={`status status-${vehicle.status.toLowerCase()}`}
                      >
                        {statusLabels[vehicle.status]}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
      <p className="list-note">
        {filtered
          ? `Showing ${vehicles.length} matching vehicles.${total !== undefined ? ` Fleet total: ${total}.` : ""}`
          : `Showing ${vehicles.length}${total !== undefined ? ` of ${total} reported` : ""} vehicles.`}{" "}
        {vehicles.length >= VEHICLE_LIMIT
          ? `The API returns at most ${VEHICLE_LIMIT} vehicles per request; this list may be incomplete.`
          : "Refresh to retrieve changes."}
      </p>
    </section>
  );
}
