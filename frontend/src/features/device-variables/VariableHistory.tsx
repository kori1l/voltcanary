import {useEffect, useState} from "react";
import {apiRequest} from "../../api/client";
import * as api from "./variablesApi";

export function VariableHistory({deviceId, sourceId, desiredGeneration, bindings, refreshKey}: {deviceId: string; sourceId: string; desiredGeneration: number; bindings: api.VariableBinding[]; refreshKey: number}) {
  const [descriptors, setDescriptors] = useState<api.VariableDescriptor[]>([]);
  const [variableId, setVariableId] = useState("");
  const [series, setSeries] = useState<api.VariableSeries | null>(null);
  const [latest, setLatest] = useState<api.VariableSeries | null>(null);
  const [outcomes, setOutcomes] = useState<Awaited<ReturnType<typeof api.variableOutcomes>>>([]);
  const [error, setError] = useState(""), [busy, setBusy] = useState(false), [refresh, setRefresh] = useState(0);
  const [threshold, setThreshold] = useState(8), [condition, setCondition] = useState("ABOVE_THRESHOLD");
  const [from, setFrom] = useState(() => new Date(Date.now() - 86400000).toISOString().slice(0, 16));
  const [to, setTo] = useState(() => new Date().toISOString().slice(0, 16));
  useEffect(() => {
    const controller = new AbortController();
    setDescriptors([]); setSeries(null); setLatest(null); setOutcomes([]); setError("");
    if (sourceId) void api.variableDescriptors(deviceId, controller.signal).then(records => {
      if (!controller.signal.aborted) setDescriptors(records.filter(item => item.dataSourceId === sourceId));
    }).catch(failure => {if (!controller.signal.aborted) setError(String(failure));});
    return () => controller.abort();
  }, [deviceId, sourceId, refreshKey, refresh]);
  const selected = descriptors.find(item => item.variableId === variableId) ?? descriptors[0];
  useEffect(() => {
    let active = true;
    setSeries(null); setLatest(null); setOutcomes([]);
    if (!selected || !sourceId) return;
    if (!Number.isFinite(Date.parse(from + "Z")) || !Number.isFinite(Date.parse(to + "Z"))) return;
    const start = new Date(from + "Z").toISOString(), end = new Date(to + "Z").toISOString();
    const bucketSeconds = Math.max(1, Math.ceil((Date.parse(end) - Date.parse(start)) / 1000 / 1000));
    void Promise.all([api.readVariable(deviceId, selected.variableId, sourceId, start, end, {bucketSeconds}),
      api.latestVariable(deviceId, selected.variableId, sourceId), api.variableOutcomes(deviceId, sourceId)]).then(([history, last, status]) => {
      if (active) {setSeries(history); setLatest(last); setOutcomes(status);}
    }).catch(failure => {if (active) setError(String(failure));});
    return () => {active = false;};
  }, [selected?.variableId, deviceId, sourceId, from, to, refreshKey, refresh, descriptors]);
  const binding = bindings.find(item => item.variableId === selected?.variableId);
  const outcome = outcomes.find(item => item.variableId === selected?.variableId);
  const point = latest?.points[0];
  const state = !binding ? "UNBOUND" : !binding.enabled ? "DISABLED"
    : outcome && outcome.status !== "OK" && outcome.generation === desiredGeneration ? "ERROR"
    : selected && selected.generation >= desiredGeneration && point?.generation === desiredGeneration ? "ACTIVE" : "PROPAGATING";
  async function act(work: () => Promise<void>) {
    setBusy(true); setError("");
    try {await work();} catch (failure) {setError(failure instanceof Error ? failure.message : String(failure));}
    finally {setBusy(false);}
  }
  function rule() {
    if (!selected || !Number.isFinite(threshold)) throw new Error("Choose a variable and finite threshold.");
    return {name: selected.label + " threshold", ruleType: "THRESHOLD", enabled: true, scopeType: "DEVICE", scopeId: deviceId,
      metric: selected.metricToken, conditionType: condition, threshold, thresholdUnit: selected.unit, severity: "WARNING",
      cooldownSeconds: 900, deduplicationWindowSeconds: 900, notificationChannelIds: [], config: {
        variable: {variableId: selected.variableId, dataSourceId: sourceId, unit: selected.unit, maxLiveAgeSeconds: 600}}};
  }
  return <section className="panel form-stack">
    <h2>Variable telemetry</h2>
    {error ? <p role="status">{error}</p> : null}
    <div className="form-grid">
      <label>Stored variable<select value={selected?.variableId ?? ""} onChange={event => setVariableId(event.target.value)}>
        {!descriptors.length ? <option value="">No applied variable definitions</option> : null}
        {descriptors.map(item => <option key={item.variableId} value={item.variableId}>{item.label} · {item.unit}</option>)}
      </select></label>
      <label>From (UTC)<input type="datetime-local" value={from} onChange={event => setFrom(event.target.value)} /></label>
      <label>To (UTC)<input type="datetime-local" value={to} onChange={event => setTo(event.target.value)} /></label>
    </div>
    <button className="ghost-button" type="button" disabled={busy || !sourceId} onClick={() => setRefresh(value => value + 1)}>Refresh activation and telemetry</button>
    <p role="status">{sourceId ? state : "UNBOUND — choose a source"} · requested generation {desiredGeneration} · applied {selected?.generation ?? "unknown"}</p>
    {outcome?.status && outcome.status !== "OK" ? <p>Latest calculation: {outcome.status}</p> : null}
    <p>Latest stored value: <strong>{point ? `${point.value} ${selected?.unit ?? ""}` : "No valid measurement"}</strong>{point ? ` · ${point.timestamp} · v${point.revision}` : ""}</p>
    {series?.points.length ? <VariableChart points={series.points} unit={series.unit} /> : <p>No measurements in this period.</p>}
    {series?.hasMore ? <p>The graph reached its result limit. Narrow the time range.</p> : null}
    <button className="ghost-button" type="button" disabled={busy || !selected} onClick={() => void act(async () => {
      if (!selected) return;
      const records: api.VariablePoint[] = []; let cursor: string | undefined;
      for (let page = 0; page < 20; page++) {
        const batch = await api.readVariable(deviceId, selected.variableId, sourceId, new Date(from + "Z").toISOString(), new Date(to + "Z").toISOString(), {limit: 1000, cursor});
        records.push(...batch.points);
        if (!batch.hasMore) {
          const csv = ["timestamp,value,unit,revision,generation", ...records.map(item => `${item.timestamp},${item.value},${selected.unit},${item.revision},${item.generation}`)].join("\n");
          const url = URL.createObjectURL(new Blob([csv], {type: "text/csv"}));
          const link = document.createElement("a"); link.href = url; link.download = selected.key + ".csv"; link.click(); URL.revokeObjectURL(url); return;
        }
        if (!batch.nextCursor || batch.nextCursor === cursor) throw new Error("Export cursor did not advance.");
        cursor = batch.nextCursor;
      }
      throw new Error("Export exceeds 20000 measurements. Narrow the period.");
    })}>Export raw CSV</button>
    <fieldset disabled={busy || !selected?.enabled}><legend>Threshold alert for this source</legend>
      <div className="form-grid"><label>Condition<select value={condition} onChange={event => setCondition(event.target.value)}><option>ABOVE_THRESHOLD</option><option>BELOW_THRESHOLD</option></select></label>
        <label>Threshold ({selected?.unit ?? ""})<input type="number" step="any" value={threshold} onChange={event => setThreshold(Number(event.target.value))} /></label></div>
      <div className="action-row">
        <button className="ghost-button" type="button" onClick={() => void act(async () => {
          const result = await apiRequest<{evaluationStatus: string; matchCount: number}>("/alert-rules/test", {method: "POST",
            body: JSON.stringify({deviceId, from: new Date(from + "Z").toISOString(), to: new Date(to + "Z").toISOString(), rule: rule()})});
          setError(`Threshold test: ${result.evaluationStatus}; ${result.matchCount} matching observations. No alert was created.`);
        })}>Test against raw history</button>
        <button className="ghost-button" type="button" onClick={() => void act(async () => {
          await apiRequest("/alert-rules", {method: "POST", body: JSON.stringify(rule())});
          setError("Threshold rule created. Eligible new observations can create alerts.");
        })}>Create threshold rule</button>
      </div>
      <small>Live rules use fresh valid observations. Historical testing compares thresholds and does not send notifications.</small>
    </fieldset>
  </section>;
}

function VariableChart({points, unit}: {points: api.VariablePoint[]; unit: string}) {
  const start = Math.min(...points.map(point => Date.parse(point.timestamp))), end = Math.max(...points.map(point => Date.parse(point.timestamp)));
  const minimum = Math.min(...points.map(point => point.minimum)), maximum = Math.max(...points.map(point => point.maximum));
  const x = (point: api.VariablePoint) => 30 + 440 * (Date.parse(point.timestamp) - start) / Math.max(1, end - start);
  const y = (value: number) => 150 - 130 * (value - minimum) / Math.max(1, maximum - minimum);
  return <div><svg viewBox="0 0 500 180" role="img" aria-label={"Recorded variable values in " + unit}>
    <text x="0" y="14">{maximum.toFixed(2)}</text><text x="0" y="165">{minimum.toFixed(2)}</text>
    {points.map((point, index) => <g key={index}><title>{point.timestamp}: {point.value} {unit}; min {point.minimum}, max {point.maximum}; v{point.revision}, generation {point.generation}</title>
      <line x1={x(point)} x2={x(point)} y1={y(point.minimum)} y2={y(point.maximum)} stroke="currentColor" />
      <circle cx={x(point)} cy={y(point.value)} r="2.5" fill="currentColor" /></g>)}
  </svg><small>Sample averages with recorded minimum/maximum ranges. Points are not joined across gaps or formula versions.</small></div>;
}
