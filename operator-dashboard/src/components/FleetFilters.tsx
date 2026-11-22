import { useState } from "react";
import type { VehicleFilters, VehicleStatus } from "../contracts/fleet";

export function FleetFilters({
  onApply,
  applied,
}: {
  onApply: (filters: VehicleFilters) => void;
  applied: VehicleFilters;
}) {
  const [status, setStatus] = useState(applied.status ?? "");
  const [battery, setBattery] = useState(
    applied.maxBattery === undefined ? "" : String(applied.maxBattery),
  );
  return (
    <form
      className="fleet-filters"
      aria-label="Vehicle filters"
      onSubmit={(event) => {
        event.preventDefault();
        onApply({
          ...(status ? { status: status as VehicleStatus } : {}),
          ...(battery !== "" ? { maxBattery: Number(battery) } : {}),
        });
      }}
    >
      <div>
        <label htmlFor="vehicle-status">Vehicle status</label>
        <select
          id="vehicle-status"
          value={status}
          onChange={(event) => setStatus(event.target.value)}
        >
          <option value="">All statuses</option>
          <option value="AVAILABLE">Available</option>
          <option value="RENTED">On a ride</option>
          <option value="MAINTENANCE_REQUIRED">Needs maintenance</option>
        </select>
      </div>
      <div>
        <label htmlFor="max-battery">Maximum battery (%)</label>
        <input
          id="max-battery"
          type="number"
          min="0"
          max="100"
          step="1"
          placeholder="Any"
          value={battery}
          onChange={(event) => setBattery(event.target.value)}
        />
      </div>
      <button className="action-button" type="submit">
        Apply filters
      </button>
      <button
        className="action-button secondary-button"
        type="button"
        onClick={() => {
          setStatus("");
          setBattery("");
          onApply({});
        }}
      >
        Clear filters
      </button>
    </form>
  );
}
