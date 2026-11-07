import type { VehicleStatus } from "../contracts/fleet";
import { sampleVehicles, sampleZones } from "../data/sampleFleet";

const statusLabels: Record<VehicleStatus, string> = {
  AVAILABLE: "Available",
  RENTED: "On a ride",
  MAINTENANCE_REQUIRED: "Needs maintenance",
};

export function FleetSnapshot() {
  return (
    <section className="panel" aria-labelledby="snapshot-title">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">Fleet snapshot</p>
          <h2 id="snapshot-title">A closer look</h2>
        </div>
        <span className="quiet-label">4 sample vehicles</span>
      </div>
      <div
        className="table-scroll"
        role="region"
        aria-label="Sample fleet table"
        tabIndex={0}
      >
        <table>
          <caption className="sr-only">
            Synthetic vehicle snapshot, not live fleet data
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
            {sampleVehicles.map((vehicle) => (
              <tr key={vehicle.id}>
                <th scope="row">
                  <span className="vehicle-label">{vehicle.label}</span>
                  <span className="vehicle-type">
                    {vehicle.type === "E_BIKE" ? "E-bike" : "E-scooter"}
                  </span>
                </th>
                <td>
                  {sampleZones.find((zone) => zone.id === vehicle.zoneId)
                    ?.name ?? "Unassigned"}
                </td>
                <td>
                  <span
                    className={`battery ${vehicle.batteryPercent < 20 ? "battery-low" : ""}`}
                  >
                    <span className="battery-track" aria-hidden="true">
                      <span style={{ width: `${vehicle.batteryPercent}%` }} />
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
    </section>
  );
}
