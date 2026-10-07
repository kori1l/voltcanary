import { useDeviceMaintenanceActions } from "./useDeviceMaintenanceActions";
import { MaintenanceDraft, MaintenanceFields, validMaintenanceInterval } from "../../maintenance/MaintenanceFields";
import { TimeDisplay } from "../../components/TimeDisplay";
export function DeviceMaintenancePanel({ workspace }: { workspace: ReturnType<typeof useDeviceMaintenanceActions> }) {
  const draft: MaintenanceDraft = { title: workspace.maintenanceTitle, description: workspace.maintenanceDescription, startAt: workspace.maintenanceStartAt, endAt: workspace.maintenanceEndAt, suppressAlerts: workspace.maintenanceSuppressAlerts, excludeFromAnalytics: workspace.maintenanceExcludeAnalytics, excludeFromMl: workspace.maintenanceExcludeMl };
  function update(values: Partial<MaintenanceDraft>) {
    if (values.title !== undefined) workspace.setMaintenanceTitle(values.title);
    if (values.description !== undefined) workspace.setMaintenanceDescription(values.description);
    if (values.startAt !== undefined) workspace.setMaintenanceStartAt(values.startAt);
    if (values.endAt !== undefined) workspace.setMaintenanceEndAt(values.endAt);
    if (values.suppressAlerts !== undefined) workspace.setMaintenanceSuppressAlerts(values.suppressAlerts);
    if (values.excludeFromAnalytics !== undefined) workspace.setMaintenanceExcludeAnalytics(values.excludeFromAnalytics);
    if (values.excludeFromMl !== undefined) workspace.setMaintenanceExcludeMl(values.excludeFromMl);
  }
  return <section className="maintenance-layout">{workspace.confirmationDialog}<section className="panel"><h2>Schedule maintenance for this device</h2>{workspace.maintenanceMessage ? <p className="status-note" role="status">{workspace.maintenanceMessage}</p> : null}<form onSubmit={(event) => { event.preventDefault(); void workspace.submitMaintenanceWindow(); }}><MaintenanceFields draft={draft} onChange={update} /><button className="primary-button" disabled={workspace.isMaintenanceBusy || !validMaintenanceInterval(draft.startAt, draft.endAt)}>Schedule maintenance</button></form></section><section className="panel"><h2>Windows</h2><button className="ghost-button" onClick={() => void workspace.refreshMaintenanceWindows()} disabled={workspace.isMaintenanceBusy}>Refresh windows</button>{workspace.sortedMaintenanceWindows.map((window) => <article className="registry-row" key={window.id}><div><strong>{window.title}</strong><span>{window.status}</span><TimeDisplay value={window.startAt} /> – <TimeDisplay value={window.endAt} /><p>Alerts {window.suppressAlerts ? "suppressed" : "enabled"}; analytics {window.excludeFromAnalytics ? "excluded" : "included"}; ML {window.excludeFromMl ? "excluded" : "included"}</p></div><div className="action-row">{window.status === "ACTIVE" ? <button className="ghost-button" disabled={workspace.isMaintenanceBusy} onClick={() => void workspace.finishWindowNow(window.id)}>Finish now</button> : null}{["ACTIVE", "SCHEDULED"].includes(window.status) ? <button className="ghost-button" disabled={workspace.isMaintenanceBusy} onClick={() => void workspace.cancelWindow(window.id)}>Cancel window</button> : null}</div></article>)}</section></section>;
}
