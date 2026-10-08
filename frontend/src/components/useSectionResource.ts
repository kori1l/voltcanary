import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { ApiError, sessionIdentityRevision, subscribeSession } from "../api/client";

export interface SectionResource<T> {
  data: T | null;
  status: "idle" | "waiting-dependency" | "loading" | "ready" | "empty" | "error" | "stale" | "no-access";
  error: string | null;
  receivedAt: string | null;
  refreshing: boolean;
}
export const initialResource = <T,>(status: SectionResource<T>["status"] = "idle"): SectionResource<T> => ({ data: null, status, error: null, receivedAt: null, refreshing: false });
export function failedResource<T>(previous: SectionResource<T>, error: unknown): SectionResource<T> {
  const denied = error instanceof ApiError && [401, 403].includes(error.status);
  return { ...(denied ? initialResource<T>() : previous), status: denied ? "no-access" : previous.data === null ? "error" : "stale", error: error instanceof Error ? error.message : "Could not load this section", refreshing: false };
}
const initial = initialResource;

export function useSectionResource<T>(scope = "default") {
  const identity = useSyncExternalStore(subscribeSession, sessionIdentityRevision);
  scope = `${identity}:${scope}`;
  const [stored, setStored] = useState<{ scope: string; state: SectionResource<T> }>({ scope, state: initial<T>() });
  const request = useRef(0);
  useEffect(() => () => { request.current += 1; }, [scope]);
  const load = useCallback(async (loader: () => Promise<T>, isEmpty: (data: T) => boolean = () => false): Promise<T | null> => {
    const generation = ++request.current;
    setStored((previous) => { const state = previous.scope === scope ? previous.state : initial<T>(); return { scope, state: { ...state, status: state.data === null ? "loading" : state.status, error: null, refreshing: true } }; });
    try {
      const data = await loader();
      if (generation !== request.current) return null;
      setStored({ scope, state: { data, status: isEmpty(data) ? "empty" : "ready", error: null, receivedAt: new Date().toISOString(), refreshing: false } });
      return data;
    } catch (error) {
      if (generation !== request.current) return null;
      setStored((previous) => ({ scope, state: failedResource(previous.scope === scope ? previous.state : initial<T>(), error) }));
      return null;
    }
  }, [scope]);
  const update = useCallback((mutator: (data: T) => T) => {
    setStored((previous) => previous.scope === scope && previous.state.data !== null ? { scope, state: { ...previous.state, data: mutator(previous.state.data) } } : previous);
  }, [scope]);
  const waitForDependency = useCallback((error: string | null = null) => {
    request.current += 1;
    setStored({ scope, state: { ...initial<T>(error ? "error" : "waiting-dependency"), error } });
  }, [scope]);
  const setData = useCallback((value: T | ((previous: T) => T)) => {
    request.current += 1;
    setStored((previous) => {
      if (typeof value === "function" && (previous.scope !== scope || previous.state.data === null)) return previous;
      const data = typeof value === "function" ? (value as (previous: T) => T)(previous.state.data as T) : value;
      return { scope, state: { data, status: "ready", error: null, receivedAt: new Date().toISOString(), refreshing: false } };
    });
  }, [scope]);
  return { ...(stored.scope === scope ? stored.state : initial<T>()), load, update, setData, waitForDependency };
}
