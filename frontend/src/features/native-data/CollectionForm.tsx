import { useState, type FormEvent } from "react";
import { newField, typeLabels, type CollectionDraft } from "./nativeDocuments";

export function CollectionForm({ busy, types, onPrepare, onCancel }: { busy: boolean; types: string[]; onPrepare: (draft: CollectionDraft) => void; onCancel: () => void }) {
  const [draft, setDraft] = useState<CollectionDraft>({ label: "", fields: [newField("field0")], history: false });
  const [sequence, setSequence] = useState(1);
  function submit(event: FormEvent) { event.preventDefault(); onPrepare(draft); }
  return <form className="panel native-editor" onSubmit={submit}>
    <h2>Create table</h2>
    <fieldset disabled={busy}>
      <label>Table name<input required maxLength={256} value={draft.label} onChange={event => setDraft({ ...draft, label: event.target.value })}/></label>
      <div className="native-fields">{draft.fields.map((field, index) => {
        const update = (patch: Partial<typeof field>) => setDraft({ ...draft, fields: draft.fields.map(item => item.key === field.key ? { ...item, ...patch } : item) });
        return <section className="native-field" key={field.key} aria-label={`Column ${index + 1}`}>
          <label>Column name<input required aria-label={`Column ${index + 1} name`} maxLength={256} value={field.label} onChange={event => update({ label: event.target.value })}/></label>
          <label>Type<select aria-label={`Column ${index + 1} type`} value={field.type} onChange={event => update({ type: event.target.value })}>
            {types.map(type => <option key={type} value={type}>{typeLabels[type] ?? type}</option>)}
          </select></label>
          {["INT64", "DECIMAL"].includes(field.type) ? <label>Unit<input required aria-label={`Column ${index + 1} unit`} value={field.unit} onChange={event => update({ unit: event.target.value })}/></label> : null}
          <label><input type="checkbox" checked={field.businessKey} onChange={event => update({ businessKey: event.target.checked, nullable: event.target.checked ? false : field.nullable })}/>Unique key component</label>
          <label><input type="checkbox" disabled={field.businessKey} checked={field.nullable} onChange={event => update({ nullable: event.target.checked })}/>Allow empty values</label>
          <label><input type="checkbox" checked={field.indexed} onChange={event => update({ indexed: event.target.checked })}/>Enable indexed filtering</label>
          <button className="ghost-button" type="button" disabled={draft.fields.length === 1} onClick={() => setDraft({ ...draft, fields: draft.fields.filter(item => item.key !== field.key) })}>Remove column {index + 1}</button>
        </section>;
      })}</div>
      <p className="muted">Text: up to 120 characters. Decimal: 18 digits, up to 3 fractional digits. Integer and decimal values keep exact precision.</p>
      <button type="button" className="ghost-button" disabled={draft.fields.length >= 64} onClick={() => { setDraft({ ...draft, fields: [...draft.fields, newField(`field${sequence}`)] }); setSequence(sequence + 1); }}>Add column</button>
      <label><input type="checkbox" checked={draft.history} onChange={event => setDraft({ ...draft, history: event.target.checked })}/>Keep row revisions</label>
      <p className="muted">Revision capture is available; browsing earlier revisions is not yet available.</p>
      <div className="action-row"><button type="submit" className="primary-button">Review table</button><button type="button" className="ghost-button" onClick={onCancel}>Cancel</button></div>
    </fieldset>
  </form>;
}
