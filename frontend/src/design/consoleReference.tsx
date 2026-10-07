import { useState } from "react";
import { createRoot } from "react-dom/client";
import { BrandLogo } from "../components/BrandLogo";
import { PageHeader } from "../components/PageHeader";
import { Field } from "../components/Field";
import { Dialog } from "../components/Dialog";
import { TimezoneNote } from "../components/TimeDisplay";
import "../app/styles.css";
import "../app/console.css";

const screens = ["Devices", "Telemetry", "Alerts", "Maintenance", "ML Training"];
const states = ["Normal", "Loading", "Empty", "Error", "Stale"];

function ConsoleReference() {
  const [screen, setScreen] = useState(screens[0]);
  const [state, setState] = useState(states[0]);
  const [dialog, setDialog] = useState(false);
  const [draft, setDraft] = useState("");
  return <div className="app-shell">
    <aside className="sidebar"><BrandLogo /><nav className="nav nav-open" aria-label="Reference screens">{screens.map((name) => <button key={name} className={`nav-link${screen === name ? " active" : ""}`} onClick={() => setScreen(name)}>{name}</button>)}</nav><small>Design fixtures · no API requests</small></aside>
    <main className="main-content">
      <PageHeader title={screen} context="Design reference · October 2026" description="Operational context, readable objects, one primary action per task." actions={<button className="primary-button" onClick={() => setDialog(true)}>{screen === "Devices" ? "Add device" : screen === "Maintenance" ? "Schedule operation" : screen === "ML Training" ? "Create training run" : "Review selection"}</button>} />
      <div className="tab-row" aria-label="State variants">{states.map((name) => <button key={name} className="tab-button" aria-pressed={state === name} onClick={() => setState(name)}>{name}</button>)}</div>
      {state === "Loading" ? <section className="panel" role="status">Loading {screen.toLowerCase()}…</section> : state === "Error" ? <section className="panel"><div className="error-message" role="alert">Could not load this section. Last successful values remain available.</div><button className="ghost-button" onClick={() => setState("Normal")}>Retry</button></section> : state === "Empty" ? <section className="panel empty-state"><strong>No {screen.toLowerCase()} yet</strong><span>Choose a scope or create the first item.</span></section> : <>
        {state === "Stale" ? <div className="warning-message" role="status">Showing data from 10 minutes ago. Refresh to verify current state.</div> : null}
        <section className="content-grid">
          <section className="panel"><h2>{screen === "Devices" ? "Fleet" : screen === "Telemetry" ? "Signals" : screen === "Alerts" ? "Needs attention" : screen === "Maintenance" ? "Upcoming operations" : "Training inputs"}</h2>
            {screen === "ML Training" ? <form className="form-stack"><div className="ml-control-grid"><Field label="Snapshot">{(props) => <select {...props}><option>Kitchen · power · 7 days · Ready</option></select>}</Field><Field label="Model family">{(props) => <select {...props}><option>Failure prediction</option></select>}</Field><Field label="Horizon (days)">{(props) => <input {...props} type="number" defaultValue={7} />}</Field></div><label className="checkbox-label"><input type="checkbox" defaultChecked /> Enforce quality gates</label></form> : <div className="form-stack">{["Kitchen fridge", "Workshop pump", "Office server with a long descriptive name"].map((name) => <article className="panel" key={name}><strong>{name}</strong><p>{screen === "Telemetry" ? "Power · 124 W · 360 samples" : screen === "Alerts" ? "High power · Open · Needs review" : screen === "Maintenance" ? "Inspection · Scheduled · 10:00–11:00" : "Active · Latest signal 2 minutes ago"}</p><button className="ghost-button">View details</button></article>)}</div>}
          </section>
          <section className="panel"><h2>Selected context</h2><p>Kitchen fridge · Building A / Ground floor</p><dl className="detail-list"><div><dt>Data quality</dt><dd>Available · 360 samples</dd></div><div><dt>Last refreshed</dt><dd>6 October 2026, 10:02</dd></div></dl><TimezoneNote /><details className="technical-details"><summary>Technical identifiers</summary><code>fixture-kitchen-fridge</code></details></section>
        </section>
      </>}
      {dialog ? <Dialog title={`Review ${screen.toLowerCase()}`} dirty={!!draft} onClose={() => setDialog(false)} footer={<button className="primary-button" onClick={() => setDialog(false)}>Save fixture</button>}><Field label="Name" required>{(props) => <input {...props} value={draft} onChange={(event) => setDraft(event.target.value)} />}</Field><p>Changes in this reference stay in memory.</p></Dialog> : null}
    </main>
  </div>;
}

createRoot(document.getElementById("root")!).render(<ConsoleReference />);
