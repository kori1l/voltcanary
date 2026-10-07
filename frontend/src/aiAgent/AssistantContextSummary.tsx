import { useEffect, useState } from "react";
import { Device, getDevice } from "../devices/devicesApi";
import { TimeDisplay } from "../components/TimeDisplay";
import { buildAssistantContext } from "./assistantContext";
export function AssistantContextSummary({ context }: { context: ReturnType<typeof buildAssistantContext> }) {
  const [device, setDevice] = useState<Device | null>(null);
  useEffect(() => { let live = true; setDevice(null); if (context.deviceId) getDevice(context.deviceId).then((device) => { if (live) setDevice(device); }).catch(() => undefined); return () => { live = false; }; }, [context.deviceId]);
  return <section className="status-note" aria-label="Assistant context"><strong>Context: {context.pageLabel}</strong><p>{context.deviceId ? `Device: ${device?.name ?? "Device details unavailable"}` : "Fleet / current page"}{context.searchParams.metric ? ` · Metric: ${context.searchParams.metric}` : ""}</p>{context.searchParams.from || context.searchParams.to ? <p>Interval: <TimeDisplay value={context.searchParams.from} /> – <TimeDisplay value={context.searchParams.to} /></p> : null}{context.deviceId || context.searchParams.alertId ? <details><summary>Context identifiers</summary><p>{context.deviceId}</p><p>{context.searchParams.alertId}</p></details> : null}</section>;
}
