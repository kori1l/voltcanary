import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { ApiError } from "../api/client";
import { SectionFeedback } from "../components/SectionFeedback";
import { useSectionResource } from "../components/useSectionResource";
import { getLatestTelemetry, type LatestTelemetry } from "../telemetry/telemetryApi";
import { getSimulationStatus, type SimulationRun } from "../simulation/simulationApi";
import type { DataSource, Device } from "./devicesApi";

export function activationRoute(deviceId: string, sourceId: string) { return `/devices/${deviceId}?tab=overview&setup=activation&dataSourceId=${encodeURIComponent(sourceId)}`; }
export function DeviceActivationSummary({ device, source, run }: { device: Device; source: DataSource; run?: SimulationRun }) {
  const scope = `${device.id}:${source.id}`;
  const telemetry = useSectionResource<LatestTelemetry | null>(scope);
  const simulator = useSectionResource<SimulationRun>(scope);
  const simulatorSource = source.sourceType === "SIMULATOR";
  const [stopped, setStopped] = useState(false);
  const observed = telemetry.data && Number.isFinite(Date.parse(telemetry.data.timestamp)) && Object.keys(telemetry.data.readings ?? {}).length > 0;
  const measured = useRef(false); measured.current = Boolean(observed);
  const reading = useRef(false);
  const request = useRef<AbortController | null>(null);
  async function refresh() {
    if (reading.current) return;
    reading.current = true;
    const controller = new AbortController(); request.current = controller;
    const timeout = window.setTimeout(() => controller.abort(), 10_000);
    try {
      await Promise.allSettled([
        telemetry.load(async () => { try { return await getLatestTelemetry(device.id, controller.signal); } catch (error) { if (error instanceof ApiError && error.status === 404) return null; throw error; } }, (data) => !data || !Object.keys(data.readings ?? {}).length),
        ...(simulatorSource ? [simulator.load(() => getSimulationStatus(device.id, controller.signal))] : []),
      ]);
    } finally { window.clearTimeout(timeout); reading.current = false; }
  }
  useEffect(() => {
    measured.current = false; setStopped(false);
    if (run) simulator.setData(run);
    void refresh();
    const started = Date.now();
    const interval = window.setInterval(() => { if (Date.now() - started >= 60_000) { setStopped(true); window.clearInterval(interval); return; } if (!document.hidden && !measured.current) void refresh(); }, 5_000);
    return () => { window.clearInterval(interval); request.current?.abort(); };
  }, [scope]);
  const base = new URL(import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080"); base.username = ""; base.password = ""; base.search = ""; base.hash = "";
  const endpoint = `${base.toString().replace(/\/$/, "")}/ingestion/readings`;
  const sample = JSON.stringify({ ...(source.protocol === "MQTT" ? {} : { deviceId: device.id, sourceId: source.externalId }), timestamp: "<ISO-8601 UTC timestamp>", metrics: { activePowerW: 120 } }, null, 2);
  return <section className="panel device-activation-summary">
    <h2>Connect {device.name}</h2>
    <p>Registered · {device.status}. Source configured · {source.externalId} ({source.protocol}). Registration does not establish connectivity.</p>
    <p role="status">{observed ? `Device measurement observed · ${telemetry.data!.timestamp}` : telemetry.status === "no-access" ? "Measurement access unavailable" : "Awaiting first confirmed measurement"}</p>
    <p className="muted-text">Latest telemetry is device scoped; this endpoint cannot prove which source supplied a reading.</p>
    <SectionFeedback resource={telemetry} label="first measurement" emptyMessage="No measurement confirmed. Connect the sender, then refresh measurements." onRetry={() => void refresh()} />
    <button type="button" className="ghost-button" disabled={telemetry.refreshing} onClick={() => void refresh()}>Refresh measurements</button>
    {stopped && !observed ? <p>Automatic checks paused after one minute. Refresh to check again; no device or source will be recreated.</p> : null}
    {simulatorSource ? <><p>Simulator: {simulator.status === "no-access" ? "Access unavailable" : simulator.data?.status ?? run?.status ?? "Status not confirmed"}{simulator.data?.runId ? ` · ${simulator.data.runId}` : ""}. Generated points: {simulator.data?.generatedPoints ?? "unknown"}.</p><SectionFeedback resource={simulator} label="simulator status" onRetry={() => void refresh()} /><Link to={`/devices/${device.id}?tab=simulation`}>Open simulator controls</Link></> : <>
      <h3>{source.protocol === "MQTT" ? "MQTT sender" : "API sender"}</h3>
      {source.protocol === "MQTT" ? <><p>Use the administrator-provided broker and MQTT authorization settings. Broker connectivity is not checked by this screen.</p><code>voltcanary/sources/{source.externalId}/telemetry</code></> : <><p>POST <code>{endpoint}</code></p><p>Header: <code>Authorization: Bearer &lt;source ingestion token&gt;</code>. Use the source credential after reviewing its impact.</p></>}
      <pre>{sample}</pre>
    </>}
    <p>Issuing or rotating a source credential grants telemetry ingestion to this source and replaces its active token. Existing senders must be updated. The token is not stored in navigation or setup recovery.</p>
    <div className="action-row"><Link className="ghost-button button-link" to={`/devices/${device.id}?tab=configuration`}>Review source credential action</Link><Link className="ghost-button button-link" to={activationRoute(device.id, source.id)}>Open activation continuation</Link></div>
  </section>;
}
