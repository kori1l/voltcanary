import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { sessionIdentityRevision, subscribeSession } from "../api/client";
import { useDebouncedValue } from "./useDebouncedValue";
import { useSectionResource } from "./useSectionResource";
import type { EntityOption } from "./EntitySelector";
export type EntityOptionLoader = (request: { search: string; offset: number; limit: number; signal: AbortSignal }) => Promise<EntityOption[]>;
export function usePagedEntityOptions(query: string, loader?: EntityOptionLoader) {
  const search = useDebouncedValue(query.trim());
  const identity = useSyncExternalStore(subscribeSession, sessionIdentityRevision);
  const scope = `${identity}:${search}`;
  const latestLoader = useRef(loader); latestLoader.current = loader;
  const [position, setPosition] = useState({ scope, offset: 0 });
  const offset = position.scope === scope ? position.offset : 0;
  const [attempt, setAttempt] = useState(0);
  const resource = useSectionResource<{ options: EntityOption[]; hasMore: boolean; nextOffset: number }>(scope);
  const accumulated = useRef<{ scope: string; options: EntityOption[] }>({ scope, options: [] });
  const enabled = Boolean(loader);
  useEffect(() => {
    if (!enabled) return;
    const controller = new AbortController();
    void resource.load(async () => {
      const records = await latestLoader.current!({ search, offset, limit: 51, signal: controller.signal });
      if (controller.signal.aborted) throw new DOMException("Obsolete choices", "AbortError");
      if (!Array.isArray(records)) throw new Error("Choices unavailable: invalid page");
      const previous = offset && accumulated.current.scope === scope ? accumulated.current.options : [];
      const options = [...new Map([...previous, ...records.slice(0, 50)].map((option) => [option.id, option])).values()];
      accumulated.current = { scope, options };
      return { options, hasMore: records.length > 50, nextOffset: offset + 50 };
    }, (page) => !page.options.length);
    return () => controller.abort();
  }, [scope, search, offset, attempt, enabled, resource.load]);
  return { resource, applyingSearch: search !== query.trim(), loadMore: () => { if (resource.data?.hasMore) setPosition({ scope, offset: resource.data.nextOffset }); }, retry: () => setAttempt((value) => value + 1) };
}
