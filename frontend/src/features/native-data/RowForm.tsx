import { useState, type FormEvent } from "react";
import type { Dataset, Row } from "./nativeDataApi";
import { displayLiteral, type CellDraft } from "./nativeDocuments";
import { NativeReferencePicker } from "./NativeReferencePicker";
import { NativePointEditor } from "./NativePointEditor";
import { NativeCircleBrowser } from "./NativeCircleBrowser";
import { NativeResourcePicker } from "./NativeResourcePicker";
import { isDeviceResource, NativeDeviceResourcePicker } from "./NativeDeviceResourcePicker";

const utcInstantPattern = "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]{1,6})?Z";

export function RowForm({ dataset, row, operation, busy, browseOwnedReferences = false, onPrepare, onCancel }: {
  dataset: Dataset; row?: Row; operation: "INSERT" | "UPDATE" | "DELETE"; busy: boolean;
  browseOwnedReferences?: boolean;
  onPrepare: (cells: Record<string, CellDraft>) => void; onCancel: () => void;
}) {
  const [cells, setCells] = useState<Record<string, CellDraft>>(() => Object.fromEntries(dataset.definition.fields.map(field => [field.id,
    { mode: row || field.nullable ? "UNCHANGED" : "VALUE", value: field.type === "BOOLEAN" ? "false" : "" }])));
  const deleting = operation === "DELETE";
  const changed = operation !== "UPDATE" || dataset.definition.fields.some(field => !dataset.definition.keyFieldIds.includes(field.id) && cells[field.id].mode !== "UNCHANGED");
  function submit(event: FormEvent) { event.preventDefault(); onPrepare(cells); }
  return <form className="panel native-editor" onSubmit={submit}>
    <h2>{deleting ? "Delete row" : row ? "Edit row" : "Add row"}</h2>
    {row ? <p>Row version {row.recordVersion}. {deleting ? "The row will be removed after confirmation." : "Unchanged fields retain their current values."}</p> : null}
    <fieldset disabled={busy}>
      {dataset.definition.fields.map(field => {
        const locked = deleting || Boolean(row && dataset.definition.keyFieldIds.includes(field.id));
        const cell = cells[field.id];
        const resource = field.target?.kind === "RESOURCE" ? field.target.resourceKind : null;
        const browse = browseOwnedReferences && !locked && cell.mode === "VALUE" && field.type === "REF";
        const update = (patch: Partial<CellDraft>) => setCells({ ...cells, [field.id]: { ...cell, ...patch } });
        return <div className="native-field" key={field.id}>
          <label>{field.label}{field.unit && field.unit !== "1" ? ` (${field.unit})` : ""}
            {locked ? <output>{displayLiteral(row?.values[field.id])} {deleting ? "" : "· Key (unchanged)"}</output> : <>
              <select aria-label={`${field.label} action`} value={cell.mode} onChange={event => update({ mode: event.target.value as CellDraft["mode"] })}>
                {row || field.nullable ? <option value="UNCHANGED">{row ? "Unchanged" : "Omit"}</option> : null}
                <option value="VALUE">Set value</option>{field.nullable ? <option value="NULL">Clear to empty</option> : null}
              </select>
              {cell.mode === "VALUE" ? field.type === "INTERVAL" ? <>
                <input aria-label={`${field.label} start (UTC)`} required pattern={utcInstantPattern}
                  placeholder="Start · 2026-11-03T12:00:00Z" value={cell.value}
                  onChange={event => update({ value: event.target.value })}/>
                <input aria-label={`${field.label} end (UTC, optional)`} pattern={utcInstantPattern}
                  placeholder="End · optional" value={cell.secondary ?? ""}
                  onChange={event => update({ secondary: event.target.value })}/>
              </> : field.type === "POINT" ? null : field.type === "BOOLEAN" || field.type === "ENUM" ? <select required aria-label={`${field.label} value`} value={cell.value} onChange={event => update({ value: event.target.value })}>
                {field.type === "ENUM" ? <option value="">Select value</option> : null}
                {(field.type === "BOOLEAN" ? ["false", "true"] : field.values ?? []).map(value => <option key={value}>{value}</option>)}
              </select> : <input aria-label={`${field.label} value`} required={field.type !== "TEXT"} type={field.type === "DATE" ? "date" : "text"}
                pattern={field.type === "REF" ? "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" : undefined}
                placeholder={field.type === "INSTANT" ? "2026-11-03T12:00:00Z" : field.type === "REF" ? "Resource or row UUID" : undefined}
                value={cell.value} onChange={event => update({ value: event.target.value })}/> : null}
            </>}
          </label>
          {!locked && cell.mode === "VALUE" && field.type === "POINT"
            ? <NativePointEditor label={field.label} cell={cell} onChange={update}/> : null}
          {browse && field.target?.kind === "DATASET"
            ? <NativeReferencePicker key={field.target.datasetId} datasetId={field.target.datasetId} fieldLabel={field.label}
              onSelect={value => update({ value })}/> : null}
          {browse && resource === "SPATIAL_FEATURE"
            ? <NativeCircleBrowser onSelect={circle => update({ value: circle.id })}/> : null}
          {browse && (resource === "DEVICE" || resource === "LOCATION")
            ? <NativeResourcePicker kind={resource} field={field.label} onSelect={value => update({ value })}/> : null}
          {browse && isDeviceResource(resource) ? <NativeDeviceResourcePicker key={resource} kind={resource}
            onSelect={value => update({ value })}/> : null}
          {row && !deleting && !locked ? <small>Current: {displayLiteral(row.values[field.id])}</small> : null}
        </div>;
      })}
      <div className="action-row"><button type="submit" className="primary-button" disabled={!changed}>Review {deleting ? "deletion" : "row"}</button><button className="ghost-button" type="button" onClick={onCancel}>Cancel</button></div>
    </fieldset>
  </form>;
}
