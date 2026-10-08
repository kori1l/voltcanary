import { useContext, useEffect, useRef, type Dispatch, type ReactNode, type SetStateAction } from "react";
import { MemoryRouter, useInRouterContext, useLocation, useNavigate } from "react-router-dom";
import { consoleQuery, crossesWorkflowBoundary } from "./consoleRouteContext";
import { NAVIGATION_CANCELLED, WorkflowDraftContext } from "../components/workflowDraftContext";

export function ConsoleRouteContext({ children, fallbackPath }: { children: ReactNode; fallbackPath?: string }) {
  const inRouter = useInRouterContext();
  return inRouter ? children : <MemoryRouter initialEntries={[`${fallbackPath ?? window.location.pathname}${window.location.search}`]}>{children}</MemoryRouter>;
}

/** One instance per route shares pending patches, including multiple updates in one event. */
export function useConsoleQuery() {
  const location = useLocation();
  const navigate = useNavigate();
  const guard = useContext(WorkflowDraftContext);
  const params = consoleQuery(location.pathname, location.search);
  const pending = useRef({ path: location.pathname, search: location.search, params });
  const submitted = useRef<string | null>(null);
  const flush = useRef({ scheduled: false, replace: false });
  const active = useRef(true);
  useEffect(() => { active.current = true; return () => { active.current = false; }; }, []);
  if (pending.current.path !== location.pathname || pending.current.search !== location.search) {
    const incoming = location.search.replace(/^\?/, "");
    const preserveStaged = pending.current.path === location.pathname && incoming === submitted.current && flush.current.scheduled;
    pending.current = { path: location.pathname, search: location.search, params: preserveStaged ? pending.current.params : params };
    submitted.current = null;
  }
  useEffect(() => { const cancel = () => { pending.current.params = consoleQuery(location.pathname, location.search); }; window.addEventListener(NAVIGATION_CANCELLED, cancel); return () => window.removeEventListener(NAVIGATION_CANCELLED, cancel); }, [location.pathname, location.search]);
  function update(patch: Record<string, string | number | boolean | null | undefined>, replace = false) {
    const preview = new URLSearchParams(pending.current.params);
    for (const [key, value] of Object.entries(patch)) { if (value === null || value === undefined || value === "" || value === false) preview.delete(key); else preview.set(key, String(value)); }
    const commit = () => {
    const next = new URLSearchParams(pending.current.params);
    for (const [key, value] of Object.entries(patch)) { if (value === null || value === undefined || value === "" || value === false) next.delete(key); else next.set(key, String(value)); }
    const safe = consoleQuery(location.pathname, next.toString(), true);
    pending.current.params = safe;
    const search = safe.toString();
    if (search !== params.toString()) {
      if (!flush.current.scheduled) {
        flush.current = { scheduled: true, replace };
        queueMicrotask(() => {
          const mode = flush.current.replace; flush.current.scheduled = false;
          if (!active.current || pending.current.path !== location.pathname) return;
          const finalSearch = pending.current.params.toString();
          submitted.current = finalSearch;
          navigate({ pathname: location.pathname, search: finalSearch ? `?${finalSearch}` : "" }, { replace: mode });
        });
      } else flush.current.replace = flush.current.replace && replace;
    }
    };
    if (guard) guard.requestNavigation(commit, crossesWorkflowBoundary(location, { pathname: location.pathname, search: preview.toString() })); else commit();
  }
  function state<T extends string | null>(key: string, fallback: T): readonly [T, Dispatch<SetStateAction<T>>] {
    const value = (params.get(key) ?? fallback) as T;
    return [value, (next) => {
      const previous = (pending.current.params.get(key) ?? fallback) as T;
      const resolved = typeof next === "function" ? next(previous) : next;
      update({ [key]: resolved === fallback ? null : resolved }, key === "q");
    }];
  }
  function dateState(key: string, fallback: Date): readonly [Date, Dispatch<SetStateAction<Date>>] {
    const value = params.has(key) ? new Date(params.get(key)!) : fallback;
    return [value, (next) => { const previous = pending.current.params.has(key) ? new Date(pending.current.params.get(key)!) : fallback; update({ [key]: (typeof next === "function" ? next(previous) : next).toISOString() }); }];
  }
  function flagState(key: string): readonly [boolean, Dispatch<SetStateAction<boolean>>] {
    return [params.get(key) === "true", (next) => update({ [key]: typeof next === "function" ? next(pending.current.params.get(key) === "true") : next })];
  }
  return { params, update, state, dateState, flagState };
}
