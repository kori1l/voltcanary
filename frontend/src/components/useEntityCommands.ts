import { useEffect, useRef, useState } from "react";
import { ApiError, sessionIdentityRevision } from "../api/client";

export type CommandPhase = "idle" | "reviewing" | "submitting" | "committed" | "unknown-result" | "failed";
export interface CommandOutcome { phase: CommandPhase; label: string; result?: unknown; error?: string; refreshError?: string; code?: string; fieldErrors?: Record<string, string> }
export function commandGuidance(error: unknown) {
  const status = error instanceof ApiError ? error.status : 0;
  if (status === 401) return "Your session changed or expired. Sign in and review the current task.";
  if (status === 403) return "Access denied. Check your permissions for this resource.";
  if ([400, 422].includes(status)) return "The server rejected these values. Review the form fields before submitting.";
  if (status === 409) return "The resource changed or has blocking dependencies. Refresh and review its current state.";
  if (status === 429) return "Too many requests. Wait before trying again.";
  if (status >= 500) return "A required service is unavailable. Refresh the records before trying another command.";
  return "The response was not confirmed. Refresh and review existing records before another command.";
}
export function useEntityCommands() {
  const [outcomes, setOutcomes] = useState<Record<string, CommandOutcome>>({});
  const locked = useRef(new Set<string>());
  const reads = useRef(new Map<string, () => Promise<unknown>>());
  const active = useRef(true);
  useEffect(() => { active.current = true; return () => { active.current = false; }; }, []);
  function publish(key: string, value: CommandOutcome, identity: number) {
    if (active.current && identity === sessionIdentityRevision()) setOutcomes((current) => ({ ...current, [key]: value }));
  }
  async function retryRead(key: string) {
    const refresh = reads.current.get(key); if (!refresh) return;
    const identity = sessionIdentityRevision();
    try {
      const result = await refresh(); if (result === false || result === null) throw new Error("Unavailable read");
      if (active.current && identity === sessionIdentityRevision()) setOutcomes((current) => current[key] ? ({ ...current, [key]: { ...current[key], refreshError: undefined } }) : current);
    } catch { if (active.current && identity === sessionIdentityRevision()) setOutcomes((current) => current[key] ? ({ ...current, [key]: { ...current[key], refreshError: "Records unavailable. Retry this read; the command will not be repeated." } }) : current); }
  }
  async function run<T>(key: string, options: { label: string; review?: () => Promise<boolean>; mutate: () => Promise<T>; onCommitted?: (result: T) => void; refresh?: () => Promise<unknown>; nonIdempotent?: boolean }): Promise<T | null> {
    if (locked.current.has(key)) return null;
    locked.current.add(key);
    const identity = sessionIdentityRevision();
    let outcome: CommandOutcome = { phase: "reviewing", label: options.label };
    let submitted = false;
    publish(key, outcome, identity);
    try {
      if (options.review && !(await options.review())) { publish(key, { phase: "idle", label: options.label }, identity); return null; }
      if (!active.current || identity !== sessionIdentityRevision()) return null;
      publish(key, { phase: "submitting", label: options.label }, identity);
      submitted = true;
      const result = await options.mutate();
      if (!active.current || identity !== sessionIdentityRevision()) return null;
      outcome = { phase: "committed", label: options.label, result };
      publish(key, outcome, identity);
      options.onCommitted?.(result);
      if (options.refresh) {
        reads.current.set(key, options.refresh);
        try { const read = await options.refresh(); if (read === false || read === null) throw new Error("Unavailable read"); }
        catch { outcome = { ...outcome, refreshError: "Saved, but records are unavailable. Retry the read; do not repeat the command." }; publish(key, outcome, identity); }
      }
      return result;
    } catch (error) {
      if (outcome.phase === "committed") { publish(key, { ...outcome, refreshError: "Saved. Local evidence could not be updated; refresh the records." }, identity); return outcome.result as T; }
      const status = error instanceof ApiError ? error.status : 0;
      const unknown = submitted && (status === 0 || [502, 503, 504].includes(status));
      outcome = { phase: unknown ? "unknown-result" : "failed", label: options.label, error: commandGuidance(error), code: error instanceof ApiError ? error.code : undefined, fieldErrors: error instanceof ApiError ? error.fieldErrors : undefined };
      publish(key, outcome, identity);
      if (unknown && options.refresh) { reads.current.set(key, options.refresh); await retryRead(key); }
      if (unknown && options.nonIdempotent) return null; // Retain the lock until the owning workflow reconciles a canonical record.
      return null;
    } finally {
      if (!(outcome.phase === "unknown-result" && options.nonIdempotent)) locked.current.delete(key);
    }
  }
  function reconcile(key: string, result: unknown) {
    locked.current.delete(key);
    setOutcomes((current) => current[key] ? ({ ...current, [key]: { ...current[key], label: "Existing record confirmed", phase: "committed", result, error: undefined, refreshError: undefined } }) : current);
  }
  const isPending = (key: string) => ["reviewing", "submitting"].includes(outcomes[key]?.phase ?? "idle");
  const isBlocked = (key: string) => isPending(key) || outcomes[key]?.phase === "unknown-result";
  return { outcomes, run, retryRead, reconcile, isPending, isBlocked };
}
