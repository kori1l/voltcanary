import type { SectionResource } from "./useSectionResource";
import { TimeDisplay } from "./TimeDisplay";

export function SectionFeedback({ resource, label, onRetry, emptyMessage, showFreshness = false }: {
  resource: SectionResource<unknown>; label: string; onRetry?: () => void; emptyMessage?: string; showFreshness?: boolean;
}) {
  const showTimestamp = Boolean(resource.receivedAt && (showFreshness || resource.status === "stale" || resource.refreshing));
  if (resource.status !== "loading" && !resource.error && !showTimestamp && !(resource.status === "empty" && emptyMessage)) return null;
  return <div className="section-feedback" aria-live="polite" aria-busy={resource.refreshing}>
    {resource.status === "loading" ? <p>Loading {label}…</p> : null}
    {showTimestamp ? <small>{label} last refreshed: <TimeDisplay value={resource.receivedAt} />{resource.refreshing ? " · Refreshing…" : ""}</small> : null}
    {resource.error ? <div className={resource.status === "stale" ? "warning-message" : "error-message"} role="alert"><strong>{label}: {resource.status === "stale" ? "showing stale data" : "unavailable"}.</strong> {resource.error}{onRetry ? <button type="button" className="ghost-button" onClick={onRetry} disabled={resource.refreshing}>Retry {label}</button> : null}</div> : null}
    {resource.status === "empty" && emptyMessage ? <p className="empty-state">{emptyMessage}</p> : null}
  </div>;
}
