import { useCallback, useEffect, useRef, useState } from "react";

export interface SectionResource<T> {
  data: T | null;
  status: "loading" | "ready" | "empty" | "error" | "stale";
  error: string | null;
  receivedAt: string | null;
  refreshing: boolean;
}
const initial = <T,>(): SectionResource<T> => ({ data: null, status: "loading", error: null, receivedAt: null, refreshing: false });

export function useSectionResource<T>(scope = "default") {
  const [stored, setStored] = useState<{ scope: string; state: SectionResource<T> }>({ scope, state: initial<T>() });
  const request = useRef(0);
  useEffect(() => () => { request.current += 1; }, [scope]);
  const load = useCallback(async (loader: () => Promise<T>, isEmpty: (data: T) => boolean = () => false): Promise<T | null> => {
    const generation = ++request.current;
    setStored((previous) => ({ scope, state: { ...(previous.scope === scope ? previous.state : initial<T>()), refreshing: true } }));
    try {
      const data = await loader();
      if (generation !== request.current) return null;
      setStored({ scope, state: { data, status: isEmpty(data) ? "empty" : "ready", error: null, receivedAt: new Date().toISOString(), refreshing: false } });
      return data;
    } catch (error) {
      if (generation !== request.current) return null;
      setStored((previous) => ({ scope, state: { ...previous.state, status: previous.state.data === null ? "error" : "stale", error: error instanceof Error ? error.message : "Could not load this section", refreshing: false } }));
      return null;
    }
  }, [scope]);
  const update = useCallback((mutator: (data: T) => T) => {
    setStored((previous) => previous.scope === scope && previous.state.data !== null ? { scope, state: { ...previous.state, data: mutator(previous.state.data) } } : previous);
  }, [scope]);
  const setData = useCallback((value: T | ((previous: T) => T)) => {
    request.current += 1;
    setStored((previous) => {
      if (typeof value === "function" && (previous.scope !== scope || previous.state.data === null)) return previous;
      const data = typeof value === "function" ? (value as (previous: T) => T)(previous.state.data as T) : value;
      return { scope, state: { data, status: "ready", error: null, receivedAt: new Date().toISOString(), refreshing: false } };
    });
  }, [scope]);
  return { ...(stored.scope === scope ? stored.state : initial<T>()), load, update, setData };
}
