import type { SectionResource } from "./useSectionResource";
import { EvidenceAge } from "./EvidenceAge";

export function SectionFeedback({ resource, label, onRetry, emptyMessage, showFreshness = false, context }: {
  resource: SectionResource<unknown>; label: string; onRetry?: () => void; emptyMessage?: string; showFreshness?: boolean; context?: string;
}) {
  const showTimestamp = Boolean(resource.receivedAt && (showFreshness || resource.status === "stale" || resource.refreshing));
  if (!["idle", "loading", "waiting-dependency"].includes(resource.status) && !resource.error && !showTimestamp && !(resource.status === "empty" && emptyMessage)) return null;
  return <div className="section-feedback" aria-busy={resource.refreshing}>
    {resource.status === "loading" ? <p role="status">Loading {label}…</p> : null}
    {resource.status === "idle" ? <p>{label}: not requested.</p> : null}
    {resource.status === "waiting-dependency" ? <p>{label}: waiting for dependency.</p> : null}
    {showTimestamp ? <small>{label} last refreshed: <EvidenceAge value={resource.receivedAt} />{resource.refreshing ? " · Refreshing…" : ""}</small> : null}
    {resource.error ? <div className={resource.status === "stale" ? "warning-message" : "error-message"} role="alert" aria-label={context ? `${label} for ${context}` : undefined}><strong>{label}: {resource.status === "stale" ? "showing stale data" : "unavailable"}.</strong> {resource.error}{onRetry ? <button type="button" className="ghost-button" aria-label={context ? `Retry ${label} for ${context}` : undefined} onClick={onRetry} disabled={resource.refreshing}>Retry {label}</button> : null}</div> : null}
    {resource.status === "empty" && emptyMessage ? <p className="empty-state" role="status">{emptyMessage}</p> : null}
  </div>;
}
