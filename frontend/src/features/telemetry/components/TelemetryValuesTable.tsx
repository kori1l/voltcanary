import { useEffect, useState, useSyncExternalStore } from "react";
import { sessionIdentityRevision, subscribeSession } from "../../../api/client";
import { SectionFeedback } from "../../../components/SectionFeedback";
import { useSectionResource } from "../../../components/useSectionResource";
import { getTelemetryExportPage, type TelemetryExportPage } from "../../../telemetry/telemetryApi";
import { formatMetricValue } from "../model/telemetry.utils";
interface Cursor { timestamp?: string; sourceId?: string }
export function TelemetryValuesTable({ deviceId, metric, unit, range }: { deviceId: string; metric: string; unit: string; range: { from: string; to: string } }) {
  const actor = useSyncExternalStore(subscribeSession, sessionIdentityRevision);
  const scope = JSON.stringify([actor, deviceId, metric, range.from, range.to]);
  const [position, setPosition] = useState({ scope, index: 0, cursors: [{}] as Cursor[], open: false });
  const current = position.scope === scope ? position : { scope, index: 0, cursors: [{}] as Cursor[], open: false };
  const cursor = current.cursors[current.index];
  const resource = useSectionResource<TelemetryExportPage>(`${scope}:${cursor.timestamp ?? "first"}:${cursor.sourceId ?? ""}`);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    if (!current.open) return;
    const controller = new AbortController();
    void resource.load(async () => {
      const page = await getTelemetryExportPage(deviceId, metric, range.from, range.to, cursor.timestamp, controller.signal, cursor.sourceId);
      if (page.deviceId !== deviceId || page.metric !== metric || !Array.isArray(page.points) || page.points.length > 50 || page.points.some((point) => !Number.isFinite(point.value) || !Number.isFinite(Date.parse(point.timestamp)) || Date.parse(point.timestamp) < Date.parse(range.from) || Date.parse(point.timestamp) > Date.parse(range.to))) throw new Error("Raw values unavailable: invalid response");
      if (page.nextCursor && (!Number.isFinite(Date.parse(page.nextCursor)) || Date.parse(page.nextCursor) < Date.parse(cursor.timestamp ?? range.from) || Date.parse(page.nextCursor) > Date.parse(range.to) || cursor.timestamp === page.nextCursor && cursor.sourceId === page.nextCursorSourceId)) throw new Error("Raw values continuation did not advance");
      return page;
    }, (page) => !page.points.length);
    return () => controller.abort();
  }, [scope, current.open, current.index, attempt]);
  const page = resource.data;
  function next() {
    if (!page?.nextCursor) return;
    setPosition({ ...current, index: current.index + 1, cursors: [...current.cursors.slice(0, current.index + 1), { timestamp: page.nextCursor, sourceId: page.nextCursorSourceId ?? undefined }] });
  }
  return <section aria-label="Raw measurement values"><h3>Raw measurement values</h3>
    {!current.open ? <button className="ghost-button" type="button" onClick={() => setPosition({ ...current, open: true })}>Inspect raw values</button> : <>
      <p>Page {current.index + 1} · {page?.points.length ?? 0} loaded · Total unknown. Values retain {unit || "unspecified unit"}; timestamps are UTC and local ({Intl.DateTimeFormat().resolvedOptions().timeZone}). Each page is read separately; concurrent ingestion can change later pages.</p>
      <SectionFeedback resource={resource} label="raw measurement values" emptyMessage="No raw measurements in this interval." onRetry={() => setAttempt((value) => value + 1)} />
      {page ? <><div className="telemetry-values-table" tabIndex={0} role="region" aria-label="Recorded measurement table"><table><caption>{metric} recorded measurements</caption><thead><tr><th scope="col">UTC timestamp</th><th scope="col">Local timestamp</th><th scope="col">Value ({unit})</th><th scope="col">Source</th><th scope="col">Quality</th></tr></thead><tbody>{page.points.map((point, index) => <tr key={`${point.timestamp}:${point.sourceId ?? index}`}><td><time dateTime={point.timestamp}>{point.timestamp}</time></td><td>{new Date(point.timestamp).toLocaleString()}</td><td>{formatMetricValue(point.value, unit)}</td><td>{point.sourceId ?? "Unknown"}</td><td>{point.quality ?? "Unknown"}</td></tr>)}</tbody></table></div><button type="button" className="ghost-button" onClick={() => { const data = JSON.stringify(page, null, 2); const url = URL.createObjectURL(new Blob([data], { type: "application/json" })); const anchor = document.createElement("a"); anchor.href = url; anchor.download = "telemetry-values-page.json"; anchor.click(); URL.revokeObjectURL(url); }}>Download this values page</button></> : null}
      <div className="action-row"><button type="button" className="ghost-button" disabled={!current.index || resource.refreshing} onClick={() => setPosition({ ...current, index: current.index - 1 })}>Previous values page</button><button type="button" className="ghost-button" disabled={!page?.nextCursor || resource.refreshing || resource.status === "stale"} onClick={next}>Next values page</button></div>
    </>}
  </section>;
}
