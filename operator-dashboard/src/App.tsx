import { useMemo, useState } from "react";
import { FleetSnapshot } from "./components/FleetSnapshot";
import { ResourceNotice } from "./components/ResourceNotice";
import { fleetApi } from "./api/fleet";
import { demoFleet } from "./data/sampleFleet";
import { useFleetResource } from "./hooks/useFleetResource";
import { FleetFilters } from "./components/FleetFilters";
import { VehicleDetails } from "./components/VehicleDetails";
import type { VehicleFilters } from "./contracts/fleet";

const sections = ["Overview", "Fleet", "Proposals"] as const;
type Section = (typeof sections)[number];

function MapPlaceholder() {
  return (
    <section className="panel map-panel" aria-labelledby="map-title">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">Area overview</p>
          <h2 id="map-title">The city, at a glance</h2>
        </div>
        <span className="quiet-label">Map preview</span>
      </div>
      <div className="map-placeholder">
        <div className="map-grid" aria-hidden="true">
          <span className="map-river" />
          <span className="map-zone zone-one">Campus</span>
          <span className="map-zone zone-two">Central station</span>
          <span className="map-dot dot-one" />
          <span className="map-dot dot-two" />
          <span className="map-dot dot-three" />
        </div>
        <div className="map-note">
          <span className="map-note-icon" aria-hidden="true">
            ↗
          </span>
          <strong>Room for a live view</strong>
          <p>
            Illustrative layout. Geographic map and telemetry arrive in later
            milestones.
          </p>
        </div>
      </div>
      <div className="map-footer">
        <span>
          <i className="legend-dot" /> Illustrative service areas
        </span>
        <span>Map provider to be agreed</span>
      </div>
    </section>
  );
}

function ProposalPreview() {
  return (
    <section className="panel proposal-panel" aria-labelledby="proposal-title">
      <p className="eyebrow">Human-led decisions</p>
      <h2 id="proposal-title">Review before action</h2>
      <p>
        Charging and redistribution suggestions will appear here for operator
        review.
      </p>
      <div className="proposal-example">
        <span className="outline-tag">Layout example</span>
        <h3>Charge a low-battery vehicle</h3>
        <p>
          The review will show the rationale, supporting evidence, warnings, and
          human decision history.
        </p>
      </div>
      <div className="decision-buttons">
        <button disabled>Approve</button>
        <button disabled>Reject</button>
      </div>
      <p className="fine-print">
        Preview only. Proposal review is planned for Week 4. Approval records a
        human decision; it does not execute fleet actions. AI cannot act on its
        own.
      </p>
    </section>
  );
}

export default function App() {
  const [section, setSection] = useState<Section>("Overview");
  const [mode, setMode] = useState<"api" | "demo">("api");
  const [filters, setFilters] = useState<VehicleFilters>({});
  const [selectedVehicle, setSelectedVehicle] = useState<string | null>(null);
  const source = mode === "api" ? fleetApi : demoFleet;
  const loadVehicles = useMemo(
    () => (signal?: AbortSignal) => source.listVehicles(signal, filters),
    [source, filters.status, filters.maxBattery],
  );
  const vehicles = useFleetResource(loadVehicles);
  const summary = useFleetResource(source.getStatus);
  const busy = vehicles.loading || summary.loading;
  const demo = mode === "demo";
  const metrics = [
    ["Total vehicles", summary.data?.totalVehicles, "Reported fleet total"],
    [
      "Available",
      summary.data?.countByStatus.AVAILABLE,
      "Vehicles marked available",
    ],
    [
      "Active rentals",
      summary.data?.activeRentals,
      "Includes trips with a fault",
    ],
    [
      "Needs attention",
      summary.data?.countByStatus.MAINTENANCE_REQUIRED,
      "Maintenance required",
    ],
  ] as const;
  const fleetList = (
    <>
      <FleetFilters key={mode} applied={filters} onApply={setFilters} />
      <p className="filter-summary">
        {filters.status
          ? `Status: ${filters.status === "MAINTENANCE_REQUIRED" ? "Needs maintenance" : filters.status === "RENTED" ? "On a ride" : "Available"}`
          : "All statuses"}{" "}
        ·{" "}
        {filters.maxBattery === undefined
          ? "Any battery level"
          : `Battery at or below ${filters.maxBattery}%`}
        . Fleet indicators always show the full fleet.
      </p>
      <ResourceNotice
        name="vehicles"
        loading={vehicles.loading}
        error={vehicles.error}
        hasData={vehicles.data !== null}
        retry={vehicles.refresh}
      />
      {vehicles.data !== null && (
        <FleetSnapshot
          vehicles={vehicles.data}
          zones={summary.data?.zones ?? []}
          total={summary.data?.totalVehicles}
          demo={demo}
          filtered={
            filters.status !== undefined || filters.maxBattery !== undefined
          }
          onSelect={setSelectedVehicle}
        />
      )}
      {vehicles.updatedAt && (
        <p className="snapshot-time">
          Vehicles fetched{" "}
          <time dateTime={vehicles.updatedAt}>
            {new Date(vehicles.updatedAt).toLocaleString()}
          </time>
          .{" "}
          {demo
            ? "Demo data."
            : "Manual refresh; automatic updates are not enabled."}
        </p>
      )}
    </>
  );

  return (
    <div className="app-shell">
      <a className="skip-link" href="#main-content">
        Skip to main content
      </a>
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">
            ↗
          </span>
          <div>
            CEBP<span>Fleet operations</span>
          </div>
        </div>
        <p className="nav-caption">Workspace</p>
        <nav aria-label="Main navigation">
          {sections.map((name, index) => (
            <button
              key={name}
              aria-current={section === name ? "page" : undefined}
              onClick={() => setSection(name)}
            >
              <span aria-hidden="true">{["◫", "◎", "▤"][index]}</span>
              {name}
              {name === section && (
                <span className="nav-indicator" aria-hidden="true" />
              )}
            </button>
          ))}
        </nav>
        <div className="sidebar-footer">
          <span className="sidebar-label">Academic demonstrator</span>
          <p>
            E-bikes & scooters.
            <br />
            One shared view.
          </p>
          <span className="version">Week 03 / Fleet exploration</span>
        </div>
      </aside>
      <div className="workspace">
        <header className="topbar">
          <span>
            Operations <span className="breadcrumb-separator">/</span>{" "}
            <strong>{section}</strong>
          </span>
          <span className="preview-badge">
            <i />
            {demo ? "Demo data" : "Backend data"}
          </span>
        </header>
        <main id="main-content" tabIndex={-1}>
          <div className="page-heading">
            <div>
              <p className="eyebrow">Operator dashboard</p>
              <h1>
                {section === "Overview"
                  ? "Keep the fleet moving."
                  : section === "Fleet"
                    ? "Every vehicle, in view."
                    : "A human makes the call."}
              </h1>
              <p>
                {section === "Overview"
                  ? "A shared picture of vehicles, service areas, and what needs attention."
                  : section === "Fleet"
                    ? "The foundation for vehicle monitoring and maintenance visibility."
                    : "A space to inspect suggestions and record operator decisions."}
              </p>
            </div>
            <span className="milestone-tag">Week 3</span>
          </div>
          <div className="preview-notice" role="note">
            <strong>{demo ? "Demo mode" : "Fleet snapshot"}</strong>
            <span>
              {demo
                ? "Fixed synthetic vehicles for demonstration. This view does not read backend data."
                : "Data is requested from the fleet service. Use Refresh to retrieve changes."}
            </span>
          </div>
          <div className="data-toolbar">
            <label htmlFor="data-source">Data source</label>
            <select
              id="data-source"
              value={mode}
              onChange={(event) => {
                setMode(event.target.value as "api" | "demo");
                setFilters({});
                setSelectedVehicle(null);
              }}
            >
              <option value="api">Backend</option>
              <option value="demo">Demo</option>
            </select>
            <button
              className="action-button"
              disabled={busy}
              onClick={() => {
                vehicles.refresh();
                summary.refresh();
              }}
            >
              {busy ? "Loading…" : "Refresh"}
            </button>
          </div>
          <ResourceNotice
            name="fleet summary"
            loading={summary.loading}
            error={summary.error}
            hasData={summary.data !== null}
            retry={summary.refresh}
          />
          {section === "Overview" && (
            <>
              <section
                className="metrics"
                aria-label="Fleet indicators"
                aria-busy={summary.loading}
              >
                {metrics.map(([label, value, description], index) => (
                  <article className={`metric metric-${index}`} key={label}>
                    <p>{label}</p>
                    <strong>
                      {value === undefined ? "—" : value.toLocaleString()}
                    </strong>
                    <span>{description}</span>
                  </article>
                ))}
              </section>
              <div className="overview-grid">
                <MapPlaceholder />
                <ProposalPreview />
              </div>
              {fleetList}
            </>
          )}
          {section === "Fleet" && (
            <>
              {fleetList}
              <section className="panel future-panel">
                <p className="eyebrow">Next milestones</p>
                <h2>Explore the fleet</h2>
                <p>
                  Apply a status or battery filter, then select a vehicle to
                  inspect its location, measurement times, and open maintenance
                  records. Use Refresh to retrieve changes; automatic SSE
                  updates follow in Week 4.
                </p>
              </section>
            </>
          )}
          {section === "Proposals" && (
            <div className="proposal-layout">
              <ProposalPreview />
              <section className="panel future-panel">
                <p className="eyebrow">Planned review flow · Week 4</p>
                <h2>Evidence, then a decision</h2>
                <ol>
                  <li>Inspect charging or redistribution suggestions.</li>
                  <li>Review source data, rationale, and warnings.</li>
                  <li>Explicitly approve or reject as an operator.</li>
                  <li>See the recorded decision and audit history.</li>
                </ol>
                <p>
                  This layout does not submit decisions or execute fleet
                  actions. Proposal API integration is planned for Week 4.
                </p>
              </section>
            </div>
          )}
          <footer className="page-footer">
            <span>CEBP / E-bike & scooter fleet management</span>
            <span>Person 3 · Frontend / Week 3</span>
          </footer>
        </main>
        {selectedVehicle && (
          <VehicleDetails
            key={`${mode}:${selectedVehicle}`}
            vehicleId={selectedVehicle}
            source={source}
            zones={summary.data?.zones ?? []}
            demo={demo}
            onClose={() => setSelectedVehicle(null)}
          />
        )}
      </div>
    </div>
  );
}
