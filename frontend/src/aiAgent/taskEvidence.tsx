import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { useLocation } from "react-router-dom";
import { sessionUserId } from "../components/scopedDrafts";

export interface TaskEvidence { names?: { device?: string; dataset?: string; operation?: string; incident?: string; location?: string }; interval?: { from?: string | null; to?: string | null } }
const EvidenceContext = createContext<{ evidence: TaskEvidence; scope: string; publish: (scope: string, evidence: TaskEvidence) => void } | null>(null);
function allowlisted(value: TaskEvidence): TaskEvidence {
  const names = Object.fromEntries(["device", "dataset", "operation", "incident", "location"].flatMap((key) => { const name = value.names?.[key as keyof NonNullable<TaskEvidence["names"]>]; return typeof name === "string" && name.length <= 200 ? [[key, name]] : []; }));
  const interval = Object.fromEntries(["from", "to"].flatMap((key) => { const date = value.interval?.[key as "from" | "to"]; return typeof date === "string" && Number.isFinite(Date.parse(date)) ? [[key, date]] : []; }));
  return { names, interval };
}
export function TaskEvidenceProvider({ children }: { children: ReactNode }) {
  const location = useLocation();
  const scope = `${sessionUserId() ?? "anonymous"}:${location.pathname}:${location.search}`;
  const [stored, setStored] = useState<{ scope: string; evidence: TaskEvidence }>({ scope, evidence: {} });
  const current = useRef(scope); current.current = scope;
  const publish = useCallback((requested: string, evidence: TaskEvidence) => {
    if (requested !== current.current) return;
    const safe = allowlisted(evidence);
    setStored((previous) => previous.scope === requested && JSON.stringify(previous.evidence) === JSON.stringify(safe) ? previous : { scope: requested, evidence: safe });
  }, []);
  return <EvidenceContext.Provider value={{ scope, evidence: stored.scope === scope ? stored.evidence : {}, publish }}>{children}</EvidenceContext.Provider>;
}
export function useTaskEvidence(evidence: TaskEvidence) {
  const context = useContext(EvidenceContext);
  const serialized = JSON.stringify(evidence);
  useEffect(() => { if (context) context.publish(context.scope, JSON.parse(serialized) as TaskEvidence); }, [context?.publish, context?.scope, serialized]);
}
export function useCurrentTaskEvidence() { return useContext(EvidenceContext)?.evidence ?? {}; }
