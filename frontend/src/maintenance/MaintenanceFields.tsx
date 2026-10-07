import { TimezoneNote } from "../components/TimeDisplay";
export interface MaintenanceDraft { title: string; description: string; startAt: string; endAt: string; suppressAlerts: boolean; excludeFromAnalytics: boolean; excludeFromMl: boolean }
export function validMaintenanceInterval(start: string, end: string) { return Number.isFinite(Date.parse(start)) && Number.isFinite(Date.parse(end)) && Date.parse(end) > Date.parse(start); }
export function maintenanceImpact(draft: Pick<MaintenanceDraft, "suppressAlerts" | "excludeFromAnalytics" | "excludeFromMl">) {
  return `${draft.suppressAlerts ? "Alerts suppressed" : "Alerts remain enabled"}; ${draft.excludeFromAnalytics ? "samples excluded from analytics" : "samples included in analytics"}; ${draft.excludeFromMl ? "samples excluded from ML" : "samples included in ML"}. Applies only to selected devices and this interval.`;
}
export function MaintenanceFields({ draft, onChange }: { draft: MaintenanceDraft; onChange: (update: Partial<MaintenanceDraft>) => void }) {
  const valid = validMaintenanceInterval(draft.startAt, draft.endAt);
  return <div className="form-stack"><TimezoneNote /><div className="form-grid-two">
    <label>Title<input required value={draft.title} onChange={(event) => onChange({ title: event.target.value })} /></label><label>Description<input value={draft.description} onChange={(event) => onChange({ description: event.target.value })} /></label>
    <label>Start<input type="datetime-local" step="1" required value={draft.startAt} onChange={(event) => onChange({ startAt: event.target.value })} /></label><label>End<input type="datetime-local" step="1" required value={draft.endAt} aria-invalid={!valid} onChange={(event) => onChange({ endAt: event.target.value })} /></label>
  </div>{!valid ? <p className="error-message" role="alert">Maintenance end must be after start.</p> : Date.parse(draft.startAt) < Date.now() ? <p className="status-note">This interval starts in the past. Review the dates before saving.</p> : null}
  <fieldset><legend>Downstream impact</legend>{([['suppressAlerts', 'Suppress alerts'], ['excludeFromAnalytics', 'Exclude analytics'], ['excludeFromMl', 'Exclude ML']] as const).map(([key, label]) => <label className="checkbox-row" key={key}><input type="checkbox" checked={draft[key]} onChange={(event) => onChange({ [key]: event.target.checked })} />{label}</label>)}</fieldset><p className="status-note">{maintenanceImpact(draft)}</p></div>;
}
