import { useEffect, useState, type Dispatch, type SetStateAction } from "react";
import { clearScopedDraft, readPrivateLegacyDraft, readScopedDraft, saveScopedDraft, scopedDraftKey, type DraftScope } from "./scopedDrafts";

export function useScopedDraftValue<T extends object>(scope: DraftScope | null, initial: T, decode: (value: Record<string, unknown>) => Partial<T> | null, legacyKey?: string, allowedFields = Object.keys(initial)) {
  const key = scope ? scopedDraftKey(scope) : "volatile";
  const restore = () => readScopedDraft(scope, decode) ?? (legacyKey ? readPrivateLegacyDraft(scope, legacyKey, decode, allowedFields) : null);
  const [stored, setStored] = useState(() => { const saved = restore(); return { key, value: saved ? { ...initial, ...saved } : initial, restored: Boolean(saved) }; });
  const value = stored.key === key ? stored.value : initial;
  useEffect(() => { const saved = restore(); setStored({ key, value: saved ? { ...initial, ...saved } : initial, restored: Boolean(saved) }); }, [key]);
  useEffect(() => {
    if (stored.key !== key || !scope) return;
    if (JSON.stringify(value) === JSON.stringify(initial)) clearScopedDraft(scope);
    else saveScopedDraft(scope, value, allowedFields);
  }, [key, value]);
  const update: Dispatch<SetStateAction<T>> = (next) => setStored((previous) => ({ key, value: typeof next === "function" ? next(previous.key === key ? previous.value : initial) : next, restored: previous.key === key && previous.restored }));
  const clear = () => { clearScopedDraft(scope); setStored({ key, value: initial, restored: false }); };
  return [value, update, clear, stored.key === key && stored.restored] as const;
}
