import { useEffect, useMemo, useRef } from "react";
import { VEHICLE_LIMIT, type FleetSource } from "../api/fleet";
import type { ZoneStatus } from "../contracts/fleet";
import { useFleetResource } from "../hooks/useFleetResource";
import { ResourceNotice } from "./ResourceNotice";

const measured = (value?: string | null) =>
  value ? new Date(value).toLocaleString() : "Not reported";
const reasons = {
  LOW_BATTERY: "Low battery",
  FAULT: "Fault",
  MANUAL: "Operator report",
};
const statusNames = {
  AVAILABLE: "Available",
  RENTED: "On a ride",
  MAINTENANCE_REQUIRED: "Needs maintenance",
};

export function VehicleDetails({
  vehicleId,
  source,
  zones,
  demo,
  onClose,
}: {
  vehicleId: string;
  source: FleetSource;
  zones: ZoneStatus[];
  demo: boolean;
  onClose: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const loadVehicle = useMemo(
    () => (signal?: AbortSignal) => source.getVehicle(vehicleId, signal),
    [source, vehicleId],
  );
  const loadMaintenance = useMemo(
    () => (signal?: AbortSignal) => source.getMaintenance(vehicleId, signal),
    [source, vehicleId],
  );
  const vehicle = useFleetResource(loadVehicle);
  const maintenance = useFleetResource(loadMaintenance);
  useEffect(() => {
    dialog.current?.showModal();
  }, []);
  const data = vehicle.data;
  return (
    <dialog
      ref={dialog}
      className="vehicle-dialog"
      aria-labelledby="details-title"
      onClose={onClose}
    >
      <div className="details-heading">
        <div>
          <p className="eyebrow">{demo ? "Demo vehicle" : "Fleet vehicle"}</p>
          <h2 id="details-title">Vehicle details</h2>
        </div>
        <button
          className="action-button secondary-button"
          autoFocus
          onClick={() => dialog.current?.close()}
        >
          Close details
        </button>
      </div>
      <ResourceNotice
        name="vehicle details"
        loading={vehicle.loading}
        error={vehicle.error}
        hasData={data !== null}
        retry={vehicle.refresh}
      />
      {data && (
        <>
          <h3 className="details-vehicle-label">{data.label}</h3>
          <dl className="vehicle-facts">
            <div>
              <dt>Vehicle ID</dt>
              <dd>{data.id}</dd>
            </div>
            <div>
              <dt>Type</dt>
              <dd>{data.type === "E_BIKE" ? "E-bike" : "E-scooter"}</dd>
            </div>
            <div>
              <dt>Status</dt>
              <dd>{statusNames[data.status]}</dd>
            </div>
            <div>
              <dt>Battery</dt>
              <dd>{data.batteryPercent}%</dd>
            </div>
            <div>
              <dt>Rentable</dt>
              <dd>{data.rentable ? "Yes" : "No"}</dd>
            </div>
            <div>
              <dt>Area</dt>
              <dd>
                {!data.zoneId
                  ? "Unassigned"
                  : (zones.find((zone) => zone.zoneId === data.zoneId)
                      ?.zoneName ?? `${data.zoneId} (name unavailable)`)}
              </dd>
            </div>
            <div>
              <dt>Latitude</dt>
              <dd>{data.latitude}</dd>
            </div>
            <div>
              <dt>Longitude</dt>
              <dd>{data.longitude}</dd>
            </div>
            <div>
              <dt>Battery measured</dt>
              <dd>{measured(data.lastBatteryAt)}</dd>
            </div>
            <div>
              <dt>Location measured</dt>
              <dd>{measured(data.lastLocationAt)}</dd>
            </div>
            <div>
              <dt>Last telemetry</dt>
              <dd>{measured(data.lastTelemetryAt)}</dd>
            </div>
            <div>
              <dt>Vehicle updated</dt>
              <dd>{measured(data.updatedAt)}</dd>
            </div>
          </dl>
        </>
      )}
      <section aria-labelledby="maintenance-title">
        <h3 id="maintenance-title">Open maintenance records</h3>
        <ResourceNotice
          name="maintenance records"
          loading={maintenance.loading}
          error={maintenance.error}
          hasData={maintenance.data !== null}
          retry={maintenance.refresh}
        />
        {maintenance.data &&
          (maintenance.data.length === 0 ? (
            <p>No open maintenance records.</p>
          ) : (
            <ul className="maintenance-records">
              {maintenance.data.map((record) => (
                <li key={record.id}>
                  <strong>{reasons[record.reason]}</strong>
                  <span>
                    {record.status === "IN_PROGRESS" ? "In progress" : "Open"}
                  </span>
                  {record.faultCode && <p>Fault code: {record.faultCode}</p>}
                  {record.description && <p>{record.description}</p>}
                  <p className="secondary-text">
                    Opened {measured(record.openedAt)} · {record.id}
                  </p>
                </li>
              ))}
            </ul>
          ))}
        {maintenance.data && maintenance.data.length >= VEHICLE_LIMIT && (
          <p>Only the first {VEHICLE_LIMIT} open records may be shown.</p>
        )}
      </section>
      <p className="secondary-text">
        A vehicle's maintenance status does not tell you whether it still has an
        active rental. These details are read-only.
      </p>
      <button
        className="action-button"
        disabled={vehicle.loading || maintenance.loading}
        onClick={() => {
          vehicle.refresh();
          maintenance.refresh();
        }}
      >
        Refresh details
      </button>
    </dialog>
  );
}
