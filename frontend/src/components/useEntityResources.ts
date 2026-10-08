import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { sessionIdentityRevision, subscribeSession } from "../api/client";
import { failedResource, initialResource, type SectionResource } from "./useSectionResource";

/** Independent request generations and retained evidence per entity and authorized scope. */
export function useEntityResources<T>(scope: string) {
  const identity = useSyncExternalStore(subscribeSession, sessionIdentityRevision);
  scope = `${identity}:${scope}`;
  const [stored, setStored] = useState<{ scope: string; items: Record<string, SectionResource<T>> }>({ scope, items: {} });
  const epoch = useRef(0);
  const generations = useRef(new Map<string, number>());
  useEffect(() => () => { epoch.current++; generations.current.clear(); }, [scope]);
  const load = useCallback(async (id: string, loader: () => Promise<T>, isEmpty: (data: T) => boolean = () => false) => {
    const generation = (generations.current.get(id) ?? 0) + 1;
    generations.current.set(id, generation);
    const startedEpoch = epoch.current;
    const write = (transform: (previous: SectionResource<T>) => SectionResource<T>) => setStored((previous) => {
      const items = previous.scope === scope ? previous.items : {};
      return { scope, items: { ...items, [id]: transform(items[id] ?? initialResource<T>()) } };
    });
    write((previous) => ({ ...previous, status: previous.data === null ? "loading" : previous.status, refreshing: true, error: null }));
    try {
      const data = await loader();
      if (epoch.current !== startedEpoch || generations.current.get(id) !== generation) return null;
      const status = isEmpty(data) ? "empty" : "ready";
      write(() => ({ data, status, receivedAt: new Date().toISOString(), error: null, refreshing: false }));
      return data;
    } catch (error) {
      if (epoch.current === startedEpoch && generations.current.get(id) === generation) write((previous) => failedResource(previous, error));
      return null;
    }
  }, [scope]);
  const setData = useCallback((id: string, data: T) => {
    generations.current.set(id, (generations.current.get(id) ?? 0) + 1);
    setStored((previous) => ({ scope, items: { ...(previous.scope === scope ? previous.items : {}), [id]: { data, status: "ready", refreshing: false, error: null, receivedAt: new Date().toISOString() } } }));
  }, [scope]);
  return { items: stored.scope === scope ? stored.items : {}, load, setData };
}
