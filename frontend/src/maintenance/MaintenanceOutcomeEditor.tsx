import { useEffect, useState } from "react";
import { createMaintenanceOutcome, listMaintenanceOutcomes, MaintenanceOperation, MaintenanceOutcome, MaintenanceOutcomeSeverity, MaintenanceOutcomeType } from "./maintenanceApi";
import { TimeDisplay, TimezoneNote } from "../components/TimeDisplay";
const outcomes: MaintenanceOutcomeType[] = ["CONFIRMED_FAILURE", "CORRECTIVE_MAINTENANCE", "PREVENTIVE_MAINTENANCE", "INSPECTION_NORMAL", "FALSE_POSITIVE", "SENSOR_FAULT", "UNRESOLVED_ANOMALY", "UNKNOWN"];
export function MaintenanceOutcomeEditor({ operation, onSaved }: { operation: MaintenanceOperation; onSaved: () => void }) {
  const [deviceId, setDeviceId] = useState(operation.devices[0]?.deviceId ?? "");
  const [outcomeType, setOutcomeType] = useState<MaintenanceOutcomeType>("INSPECTION_NORMAL");
  const localNow = () => { const date = new Date(); return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0,19); };
  const [observed, setObserved] = useState(localNow);
  const [resolved, setResolved] = useState("");
  const [component, setComponent] = useState("");
  const [failureMode, setFailureMode] = useState("");
  const [severity, setSeverity] = useState("");
  const [downtime, setDowntime] = useState("");
  const [notes, setNotes] = useState("");
  const [history, setHistory] = useState<MaintenanceOutcome[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const failureFields = ["CONFIRMED_FAILURE", "CORRECTIVE_MAINTENANCE", "SENSOR_FAULT", "UNRESOLVED_ANOMALY"].includes(outcomeType);
  const validTime = Number.isFinite(Date.parse(observed)) && (!resolved || (Number.isFinite(Date.parse(resolved)) && Date.parse(resolved) >= Date.parse(observed)));
  useEffect(() => { let live = true; listMaintenanceOutcomes(operation.id).then((items) => { if (live) setHistory(items); }).catch((error) => { if (live) setError(error instanceof Error ? error.message : "Outcome history unavailable"); }); return () => { live = false; }; }, [operation.id]);
  return <section className="form-stack"><form onSubmit={async (event) => {
    event.preventDefault(); if (!validTime || busy || !deviceId) return; setBusy(true); setError(null);
    try { const saved = await createMaintenanceOutcome(operation.id, { deviceId, maintenanceWindowId: operation.devices.find((device) => device.deviceId === deviceId)?.windowId, outcomeType, observedAt: new Date(observed).toISOString(), resolvedAt: resolved ? new Date(resolved).toISOString() : null,
      component: failureFields ? component.trim() || null : null, failureMode: failureFields ? failureMode.trim() || null : null, severity: failureFields && severity ? severity as MaintenanceOutcomeSeverity : null, downtimeMinutes: downtime ? Number(downtime) : null, technicianNotes: notes.trim() || null, source: "OPERATOR", confidence: 1 });
      setHistory((current) => [saved, ...(current ?? [])]); onSaved();
    } catch (error) { setError(error instanceof Error ? error.message : "Outcome save failed"); } finally { setBusy(false); }
  }}><h3>Record outcome</h3><p>Operator evidence can become an ML training label. Choose the observed result; this record does not itself diagnose a failure.</p><TimezoneNote />
  <fieldset><legend>Result</legend><div className="form-grid-two"><label>Outcome device<select value={deviceId} onChange={(event) => setDeviceId(event.target.value)}>{operation.devices.map((device) => <option value={device.deviceId} key={device.windowId}>{device.deviceName}</option>)}</select></label><label>Outcome<select value={outcomeType} onChange={(event) => setOutcomeType(event.target.value as MaintenanceOutcomeType)}>{outcomes.map((value) => <option key={value} value={value}>{value.replace(/_/g," ")}</option>)}</select></label></div></fieldset>
  <fieldset><legend>Observation and recovery time</legend><div className="form-grid-two"><label>Observed<input required type="datetime-local" step="1" value={observed} onChange={(event) => setObserved(event.target.value)} /></label><label>Resolved<input type="datetime-local" step="1" value={resolved} aria-invalid={!validTime} onChange={(event) => setResolved(event.target.value)} /></label><label>Downtime minutes<input min="0" type="number" step="1" value={downtime} onChange={(event) => setDowntime(event.target.value)} /></label></div>{!validTime ? <p className="error-message">Resolution time must not precede observation.</p> : null}</fieldset>
  {failureFields ? <fieldset><legend>Failure details</legend><div className="form-grid-two"><label>Component<input value={component} onChange={(event) => setComponent(event.target.value)} /></label><label>Failure mode<input value={failureMode} onChange={(event) => setFailureMode(event.target.value)} /></label><label>Outcome severity<select value={severity} onChange={(event) => setSeverity(event.target.value)}><option value="">Unset</option>{["LOW","MEDIUM","HIGH","CRITICAL"].map((value) => <option key={value}>{value}</option>)}</select></label></div></fieldset> : null}
  <label>Technician notes<textarea value={notes} onChange={(event) => setNotes(event.target.value)} /></label><button className="primary-button" disabled={busy || !validTime || !deviceId}>{busy ? "Recording..." : "Record outcome"}</button></form>
  {error ? <p className="error-message" role="alert">{error}</p> : null}<h3>Saved outcomes</h3>{history === null ? <p>History unavailable or loading.</p> : !history.length ? <p>No recorded outcomes.</p> : history.map((outcome) => <article className="registry-row" key={outcome.id}><div><strong>{outcome.outcomeType.replace(/_/g," ")}</strong><TimeDisplay value={outcome.observedAt} /><p>{operation.devices.find((device) => device.deviceId === outcome.deviceId)?.deviceName ?? "Device context unavailable"}</p><p>{outcome.technicianNotes}</p></div></article>)}</section>;
}
