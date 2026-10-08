import { createContext, useContext, useEffect, useId, useRef } from "react";
export const NAVIGATION_CANCELLED = "voltcanary.navigation-cancelled";
export const DRAFT_SAVED = "voltcanary.draft-saved";
interface DraftGuard { register: (id: string, discard: () => void) => () => void; registerReset: (id: string, discard: () => void) => () => void; requestNavigation: (action: () => void, boundary: boolean) => void }
export const WorkflowDraftContext = createContext<DraftGuard | null>(null);
export function markWorkflowDraftSaved(id: string) { window.dispatchEvent(new CustomEvent(DRAFT_SAVED, { detail: id })); }
export function useWorkflowDraft(dirty: boolean, discard: () => void, resetWhenClean = true) {
  const guard = useContext(WorkflowDraftContext);
  const id = useId();
  const callback = useRef(discard); callback.current = discard;
  useEffect(() => (dirty || resetWhenClean) ? guard?.registerReset(id, () => callback.current()) : undefined, [dirty, resetWhenClean, guard?.registerReset, id]);
  useEffect(() => dirty && guard ? guard.register(id, () => {}) : undefined, [dirty, guard?.register, id]);
}
