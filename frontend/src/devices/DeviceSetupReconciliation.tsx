import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { useSectionResource } from "../components/useSectionResource";
import { SectionFeedback } from "../components/SectionFeedback";
import { EntitySelector } from "../components/EntitySelector";
import { getSimulationConfig, getSimulationRuns } from "../simulation/simulationApi";
import { listDataSources, type Device } from "./devicesApi";
import { DeviceChoiceAdder } from "./DeviceChoiceAdder";
import { loadLocationOptions } from "./fleetEntityOptions";
import type { DeviceSetupProgress } from "./createDeviceFlow";
export function DeviceSetupReconciliation({ progress, externalId, onReviewed, onDevice, onLocation }: { progress: DeviceSetupProgress; externalId: string; onReviewed: () => void; onDevice: (device: Device) => void; onLocation: (id: string) => void }) {
  const stage = progress.unknownStage;
  const resource = useSectionResource<Array<{ id: string; label: string; accept: () => void }>>(`${stage}:${progress.device?.id ?? "unknown"}`);
  const [location, setLocation] = useState("");
  async function readRecords() {
    if (!progress.device || !["source", "credential", "configuration", "run"].includes(stage ?? "")) return;
    await resource.load(async () => {
      const deviceId = progress.device!.id;
      if (stage === "source") return (await listDataSources(deviceId)).filter((source) => source.deviceId === deviceId && source.externalId === externalId).map((source) => ({ id: source.id, label: `${source.externalId} · ${source.protocol}`, accept: () => { progress.dataSource = source; } }));
      if (stage === "configuration" || stage === "credential") { const config = await getSimulationConfig(deviceId); return config.deviceId === deviceId && config.dataSourceId === progress.dataSource?.id && config.hasSourceToken ? [{ id: config.id, label: `Saved simulator configuration · ${config.profile}`, accept: () => { progress.simulationConfig = config; } }] : []; }
      return (await getSimulationRuns(deviceId)).filter((run) => run.runId).map((run) => ({ id: run.runId!, label: `Simulator run · ${run.status}`, accept: () => { progress.simulationRun = run; } }));
    }, (records) => !records.length);
  }
  useEffect(() => { void readRecords(); }, [stage, progress.device?.id]);
  function confirm(action: () => void) { action(); progress.unknownStage = undefined; onReviewed(); }
  return <section className="status-note"><h3>Reconcile setup result</h3><p>The {stage} response was not confirmed. Matching names do not prove which request created a record. Review an authorized canonical record before continuing.</p>
    {stage === "device" ? <DeviceChoiceAdder label="Reconcile registered device" onAdd={(device) => confirm(() => { progress.device = device; onDevice(device); })} /> : null}
    {stage === "location" ? <EntitySelector label="Reconcile location" options={[]} value={location} loadOptions={loadLocationOptions} onChange={(id) => { setLocation(id); if (id) confirm(() => onLocation(id)); }} /> : null}
    <SectionFeedback resource={resource} label="setup reconciliation records" onRetry={() => void readRecords()} emptyMessage="No matching record confirmed. Refresh records or finish configuration in device settings; do not repeat an uncertain create." />
    {(resource.data ?? []).map((record) => <div key={record.id}><span>{record.label} · {record.id}</span><button type="button" className="ghost-button" onClick={() => confirm(record.accept)}>Use reviewed record</button></div>)}
    {progress.device ? <Link to={`/devices/${progress.device.id}?tab=configuration`}>Review existing device and credential configuration</Link> : null}
  </section>;
}
