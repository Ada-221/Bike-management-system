import { useState } from "react";
import { FleetSnapshot } from "./components/FleetSnapshot";
import { sampleVehicles } from "./data/sampleFleet";

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
          <i className="legend-dot" /> Sample service areas
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
        Preview only. Decisions are unavailable until the proposal API is agreed
        and connected. AI cannot act on its own.
      </p>
    </section>
  );
}

export default function App() {
  const [section, setSection] = useState<Section>("Overview");
  const metrics = [
    ["Total vehicles", sampleVehicles.length, "Across 2 sample areas"],
    [
      "Available",
      sampleVehicles.filter((v) => v.status === "AVAILABLE").length,
      "Ready for a rider",
    ],
    [
      "On a ride",
      sampleVehicles.filter((v) => v.status === "RENTED").length,
      "Active sample rental",
    ],
    [
      "Needs attention",
      sampleVehicles.filter((v) => v.status === "MAINTENANCE_REQUIRED").length,
      "Maintenance required",
    ],
  ] as const;

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
          <span className="version">Week 01 / Interface foundation</span>
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
            Synthetic data
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
            <span className="milestone-tag">Week 1 preview</span>
          </div>
          <div className="preview-notice" role="note">
            <strong>Interface skeleton</strong>
            <span>
              This preview uses fixed synthetic data. Backend and live updates
              are not connected.
            </span>
          </div>
          {section === "Overview" && (
            <>
              <section className="metrics" aria-label="Sample fleet indicators">
                {metrics.map(([label, value, description], index) => (
                  <article className={`metric metric-${index}`} key={label}>
                    <p>{label}</p>
                    <strong>{value.toString().padStart(2, "0")}</strong>
                    <span>{description}</span>
                  </article>
                ))}
              </section>
              <div className="overview-grid">
                <MapPlaceholder />
                <ProposalPreview />
              </div>
              <FleetSnapshot />
            </>
          )}
          {section === "Fleet" && (
            <>
              <FleetSnapshot />
              <section className="panel future-panel">
                <p className="eyebrow">Next milestones</p>
                <h2>From a snapshot to live operations</h2>
                <p>
                  API-backed vehicle lists begin in Week 2. Availability,
                  battery, and maintenance filters and vehicle details follow in
                  Week 3; SSE updates follow in Week 4.
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
                  The proposal transport contract is pending agreement between
                  the backend and AI/MCP owners.
                </p>
              </section>
            </div>
          )}
          <footer className="page-footer">
            <span>CEBP / E-bike & scooter fleet management</span>
            <span>Person 3 · Frontend / Week 1</span>
          </footer>
        </main>
      </div>
    </div>
  );
}
