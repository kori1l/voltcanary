import { useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { UNSAFE_DataRouterContext, useBlocker, useLocation, useNavigate } from "react-router-dom";
import { isAuthenticated } from "../api/client";
import { crossesWorkflowBoundary } from "../app/consoleRouteContext";
import { Dialog } from "./Dialog";
import { DRAFT_SAVED, NAVIGATION_CANCELLED, WorkflowDraftContext } from "./workflowDraftContext";

function DataNavigationGuard({ hasDirty, discard }: { hasDirty: () => boolean; discard: () => void }) {
  const blocker = useBlocker(({ currentLocation, nextLocation }) => isAuthenticated() && hasDirty() && crossesWorkflowBoundary(currentLocation, nextLocation));
  if (blocker.state !== "blocked") return null;
  const keep = () => { blocker.reset(); window.dispatchEvent(new Event(NAVIGATION_CANCELLED)); };
  return <Dialog title="Unsaved changes" onClose={keep}><p>Your current workflow has unsaved changes.</p><div className="action-row">
    <button className="ghost-button" type="button" onClick={keep}>Keep editing</button>
    <button className="danger-button" type="button" onClick={() => { discard(); blocker.proceed(); }}>Discard changes</button>
  </div></Dialog>;
}

export function WorkflowDraftProvider({ children }: { children: ReactNode }) {
  const dataRouter = useContext(UNSAFE_DataRouterContext);
  const location = useLocation();
  const navigate = useNavigate();
  const entries = useRef(new Map<string, () => void>());
  const resets = useRef(new Map<string, () => void>());
  const forms = useRef(new Map<HTMLFormElement, { id: string; baseline: string | null }>());
  const [, setCount] = useState(0);
  const [pending, setPending] = useState<(() => void) | null>(null);
  const hasDirty = useCallback(() => entries.current.size > 0, []);
  const register = useCallback((id: string, discard: () => void) => {
    entries.current.set(id, discard); setCount(entries.current.size);
    return () => { entries.current.delete(id); setCount(entries.current.size); };
  }, []);
  const registerReset = useCallback((id: string, action: () => void) => { resets.current.set(id, action); return () => { resets.current.delete(id); }; }, []);
  const discard = useCallback(() => { for (const action of entries.current.values()) action(); for (const action of resets.current.values()) action(); entries.current.clear(); setCount(0); }, []);
  const requestNavigation = useCallback((action: () => void, boundary: boolean) => {
    if (dataRouter || !boundary || !hasDirty() || !isAuthenticated()) action();
    else setPending((previous) => previous ? () => { previous(); action(); } : action);
  }, [dataRouter, hasDirty]);
  useEffect(() => {
    const unload = (event: BeforeUnloadEvent) => { if (hasDirty()) { event.preventDefault(); event.returnValue = ""; } };
    const saved = (event: Event) => { queueMicrotask(() => { for (const [form, entry] of forms.current) if (entry.id === (event as CustomEvent<string>).detail) { entries.current.delete(entry.id); entry.baseline = snapshot(form); } setCount(entries.current.size); }); };
    const observer = new MutationObserver(() => { let changed = false; for (const [form, entry] of forms.current) if (!form.isConnected) { forms.current.delete(form); changed = entries.current.delete(entry.id) || changed; } if (changed) setCount(entries.current.size); });
    observer.observe(document.body, { childList: true, subtree: true });
    window.addEventListener("beforeunload", unload); window.addEventListener(DRAFT_SAVED, saved);
    return () => { observer.disconnect(); window.removeEventListener("beforeunload", unload); window.removeEventListener(DRAFT_SAVED, saved); };
  }, [hasDirty]);
  function snapshot(form: HTMLFormElement) {
    // Comparison is in memory only, including volatile password fields; no DOM draft is serialized.
    return JSON.stringify([...form.querySelectorAll<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>("input:not([data-evidence-control]),select,textarea")].map((input) => input instanceof HTMLInputElement && ["checkbox", "radio"].includes(input.type) ? input.checked : input.value));
  }
  function formOf(target: EventTarget) { return target instanceof HTMLElement ? target.closest("form") : null; }
  function captureFocus(target: EventTarget) {
    const form = formOf(target);
    if (form && !form.closest("dialog") && !forms.current.has(form)) forms.current.set(form, { id: form.dataset.draftId ?? `form-${forms.current.size}`, baseline: snapshot(form) });
  }
  function captureChange(target: EventTarget) {
    const form = formOf(target);
    if (!form || !form.isConnected || form.closest("dialog")) return;
    let entry = forms.current.get(form);
    if (!entry) { entry = { id: form.dataset.draftId ?? `form-${forms.current.size}`, baseline: null }; forms.current.set(form, entry); }
    if (entry.baseline !== snapshot(form)) entries.current.set(entry.id, () => form.reset()); else entries.current.delete(entry.id);
    setCount(entries.current.size);
  }
  return <WorkflowDraftContext.Provider value={{ register, registerReset, requestNavigation }}><div onFocusCapture={(event) => captureFocus(event.target)} onChangeCapture={(event) => captureChange(event.target)} onClickCapture={(event) => {
    const target = event.target;
    queueMicrotask(() => captureChange(target));
    if (dataRouter || !hasDirty() || !isAuthenticated() || !(event.target instanceof HTMLElement)) return;
    const link = event.target.closest("a[href]"); if (!link || !(link instanceof HTMLAnchorElement) || link.origin !== window.location.origin || link.target || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    const next = { pathname: link.pathname, search: link.search }; if (!crossesWorkflowBoundary(location, next)) return;
    event.preventDefault(); requestNavigation(() => navigate(`${link.pathname}${link.search}`), true);
  }}>{children}</div>
    {dataRouter ? <DataNavigationGuard hasDirty={hasDirty} discard={discard} /> : pending ? <Dialog title="Unsaved changes" onClose={() => setPending(null)}><p>Your current workflow has unsaved changes.</p><div className="action-row"><button className="ghost-button" type="button" onClick={() => setPending(null)}>Keep editing</button><button className="danger-button" type="button" onClick={() => { discard(); const action = pending; setPending(null); action(); }}>Discard changes</button></div></Dialog> : null}
  </WorkflowDraftContext.Provider>;
}
