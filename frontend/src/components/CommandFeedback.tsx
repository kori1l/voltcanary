import type { CommandOutcome } from "./useEntityCommands";
export function CommandFeedback({ outcomes, onRetryRead }: { outcomes: Record<string, CommandOutcome>; onRetryRead: (key: string) => Promise<void> }) {
  const visible = Object.entries(outcomes).filter(([, outcome]) => outcome.phase !== "idle");
  if (!visible.length) return null;
  return <div className="command-feedback" aria-live="polite">{visible.map(([key, outcome]) => <div key={key} className={outcome.phase === "committed" ? "success-message" : "status-note"} role="status">
    <strong>{outcome.label}: </strong>{outcome.phase === "committed" ? "Saved." : outcome.phase === "unknown-result" ? "Result unknown." : outcome.phase === "failed" ? "Not accepted." : outcome.phase === "reviewing" ? "Review required." : "Submitting…"}
    {outcome.error ? <p>{outcome.error}</p> : null}{outcome.refreshError ? <p>{outcome.refreshError}</p> : null}
    {outcome.fieldErrors ? <ul>{Object.entries(outcome.fieldErrors).map(([field, message]) => <li key={field}>{field}: {message}</li>)}</ul> : null}
    {outcome.code ? <details><summary>Diagnostics</summary><code>{outcome.code}</code></details> : null}
    {outcome.phase === "committed" && outcome.result && typeof outcome.result === "object" && "id" in outcome.result && typeof outcome.result.id === "string" && /^[a-zA-Z0-9_-]{1,128}$/.test(outcome.result.id) ? <p>Record ID: <code>{outcome.result.id}</code></p> : null}
    {outcome.phase === "unknown-result" || outcome.refreshError ? <button type="button" className="ghost-button" onClick={() => void onRetryRead(key)}>Refresh records</button> : null}
  </div>)}</div>;
}
