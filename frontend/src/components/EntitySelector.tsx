import { useEffect, useState, useSyncExternalStore } from "react";
import { sessionIdentityRevision, subscribeSession } from "../api/client";
import { Field } from "./Field";
import { CopyIdentifier } from "./CopyIdentifier";
import { usePagedEntityOptions, type EntityOptionLoader } from "./usePagedEntityOptions";

export interface EntityOption { id: string; name: string; status?: string }

export function EntitySelector({ label, options, value, onChange, loading, error, required, disabled, loadOptions }: {
  label: string; options: EntityOption[]; value: string; onChange: (id: string) => void;
  loading?: boolean; error?: string; required?: boolean; disabled?: boolean;
  loadOptions?: EntityOptionLoader;
}) {
  const [query, setQuery] = useState("");
  const identity = useSyncExternalStore(subscribeSession, sessionIdentityRevision);
  const [pinned, setPinned] = useState<{ identity: number; option: EntityOption } | null>(null);
  const paging = usePagedEntityOptions(query, loadOptions);
  const choices = loadOptions ? paging.resource.data?.options ?? [] : options;
  const selected = loadOptions && paging.resource.status === "no-access" ? undefined : choices.find((option) => option.id === value) ?? options.find((option) => option.id === value) ?? (pinned?.identity === identity && pinned.option.id === value ? pinned.option : undefined);
  useEffect(() => { if (paging.resource.status === "no-access") setPinned(null); }, [paging.resource.status]);
  const filtered = loadOptions ? choices : choices.filter((option) => `${option.name} ${option.status ?? ""}`.toLowerCase().includes(query.toLowerCase()));
  const visible = selected && !filtered.some((option) => option.id === value) ? [selected, ...filtered] : filtered;
  return <div className="entity-selector">
    <Field label={`Search ${label.toLowerCase()}`}>{(props) => <input {...props} data-evidence-control="true" type="search" value={query} onChange={(event) => setQuery(event.target.value)} disabled={disabled || loading} />}</Field>
    <Field label={label} required={required} error={error || (loadOptions ? paging.resource.error ?? undefined : undefined)} hint={loading || (loadOptions && (paging.resource.refreshing || paging.resource.status === "idle")) ? "Loading available choices…" : choices.length === 0 ? "No available choices." : undefined}>{(props) => <select {...props} value={value} onChange={(event) => { const option = visible.find((option) => option.id === event.target.value); setPinned(option ? { identity, option } : null); onChange(event.target.value); }} required={required} disabled={disabled || loading || !!error || (loadOptions && ["loading", "no-access"].includes(paging.resource.status))}>
      <option value="">Select {label.toLowerCase()}</option>
      {value && !selected ? <option value={value} disabled>Unavailable selection</option> : null}
      {visible.map((option) => <option key={option.id} value={option.id}>{option.name}{option.status ? ` · ${option.status}` : ""}</option>)}
    </select>}</Field>
    {loadOptions ? <div role="status">{paging.applyingSearch ? "Applying search…" : `${choices.length} choices loaded · Total unknown`}{paging.resource.data?.hasMore ? <button type="button" className="ghost-button" disabled={paging.resource.refreshing} onClick={paging.loadMore}>Load more choices</button> : null}{paging.resource.error ? <button type="button" className="ghost-button" onClick={paging.retry}>Retry choices</button> : null}</div> : null}
    {selected ? <details className="technical-details"><summary>Technical identifier</summary><CopyIdentifier value={selected.id} /></details> : null}
  </div>;
}
