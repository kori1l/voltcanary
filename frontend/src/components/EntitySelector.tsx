import { useState } from "react";
import { Field } from "./Field";

export interface EntityOption { id: string; name: string; status?: string }

export function EntitySelector({ label, options, value, onChange, loading, error, required, disabled }: {
  label: string; options: EntityOption[]; value: string; onChange: (id: string) => void;
  loading?: boolean; error?: string; required?: boolean; disabled?: boolean;
}) {
  const [query, setQuery] = useState("");
  const selected = options.find((option) => option.id === value);
  const filtered = options.filter((option) => `${option.name} ${option.status ?? ""}`.toLowerCase().includes(query.toLowerCase()));
  const visible = selected && !filtered.some((option) => option.id === value) ? [selected, ...filtered] : filtered;
  return <div className="entity-selector">
    <Field label={`Search ${label.toLowerCase()}`}>{(props) => <input {...props} type="search" value={query} onChange={(event) => setQuery(event.target.value)} disabled={disabled || loading} />}</Field>
    <Field label={label} required={required} error={error} hint={loading ? "Loading available choices…" : options.length === 0 ? "No available choices." : undefined}>{(props) => <select {...props} value={value} onChange={(event) => onChange(event.target.value)} required={required} disabled={disabled || loading || !!error}>
      <option value="">Select {label.toLowerCase()}</option>
      {value && !selected ? <option value={value} disabled>Unavailable selection</option> : null}
      {visible.map((option) => <option key={option.id} value={option.id}>{option.name}{option.status ? ` · ${option.status}` : ""}</option>)}
    </select>}</Field>
    {selected ? <details className="technical-details"><summary>Technical identifier</summary><code>{selected.id}</code><button type="button" className="ghost-button" onClick={() => void navigator.clipboard.writeText(selected.id)}>Copy ID</button></details> : null}
  </div>;
}
