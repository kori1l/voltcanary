import type { Field } from "./nativeDataApi";

type EmptyValue = "REJECT" | "AS_NULL" | "PRESERVE_EMPTY";
export type ImportColumnDraft = { field: Field; sourceColumn: string; included: boolean; emptyValue: EmptyValue; separator: "." | "," };
export type ImportFormat = "CSV" | "JSONL";

export function initialImportMapping(fields: Field[]): ImportColumnDraft[] {
  return fields.map(field => ({
    field, sourceColumn: field.label, included: true, separator: ".",
    emptyValue: field.type === "TEXT" ? "PRESERVE_EMPTY" : "REJECT",
  }));
}

function conversion({ field, separator }: ImportColumnDraft) {
  switch (field.type) {
    case "TEXT": return { method: "identity" };
    case "INT64": return { method: "parse_int64", trim: false };
    case "DECIMAL": return { method: "parse_decimal", decimalSeparator: separator, scale: field.scale ?? 0, rounding: "REJECT" };
    case "BOOLEAN": return { method: "parse_boolean", trueTokens: ["true"], falseTokens: ["false"] };
    case "DATE": return { method: "parse_date", format: "YYYY-MM-DD" };
    case "INSTANT": return { method: "parse_instant", format: "RFC3339_OFFSET" };
    default: return null;
  }
}

export function importColumns(mapping: ImportColumnDraft[], format: ImportFormat) {
  return mapping.filter(column => column.included).map(column => {
    const { field, sourceColumn, emptyValue } = column;
    const method = format === "CSV" ? conversion(column) : { method: "identity" };
    if (!method) throw new Error(`Use JSONL or an explicit mapping for ${field.label}.`);
    return { sourceColumn, sourceType: format === "CSV" ? "TEXT" : field.type,
      sourceUnit: field.type === "DECIMAL" || field.type === "INT64" ? field.unit ?? null : null,
      targetFieldId: field.id, emptyValue, conversions: [method] };
  });
}

export function NativeImportMapping({ mapping, setMapping, format, keys, busy }: {
  mapping: ImportColumnDraft[];
  setMapping: (mapping: ImportColumnDraft[]) => void;
  format: ImportFormat;
  keys: string[];
  busy: boolean;
}) {
  return <fieldset className="form-stack" disabled={busy}>
    <legend>Column mapping</legend>
    {format === "CSV" ? <p>Put the mapped columns in the same order as the file header.</p> : null}
    {mapping.map((column, index) => {
      const { field } = column;
      function update(change: Partial<ImportColumnDraft>) {
        setMapping(mapping.map(item => item === column ? { ...item, ...change } : item));
      }
      return <section key={field.id} className="native-field" aria-label={`Mapping for ${field.label}`}>
        <label><input type="checkbox" checked={column.included} disabled={keys.includes(field.id)}
          onChange={event => update({ included: event.target.checked })}/> Map to {field.label}</label>
        {column.included ? <>
          <label>Source column<input required value={column.sourceColumn} onChange={event => update({ sourceColumn: event.target.value })}/></label>
          <label>Empty string<select value={column.emptyValue} onChange={event => update({ emptyValue: event.target.value as EmptyValue })}>
            <option value="REJECT">Reject</option>
            {field.type === "TEXT" ? <option value="PRESERVE_EMPTY">Keep empty text</option> : null}
            {field.nullable ? <option value="AS_NULL">Set to null</option> : null}
          </select></label>
          {format === "CSV" && field.type === "DECIMAL" ? <label>Decimal separator<select value={column.separator}
            onChange={event => update({ separator: event.target.value as "." | "," })}>
            <option value=".">Period</option><option value=",">Comma</option>
          </select></label> : null}
          {format === "CSV" && field.type === "BOOLEAN" ? <small>Accepted values are true and false.</small> : null}
          {format === "CSV" && !conversion(column) ? <p>Use JSONL or an explicit mapping for this column type.</p> : null}
          {format === "CSV" && index > 0 ? <button className="ghost-button" type="button" onClick={() => {
            const reordered = [...mapping];
            [reordered[index - 1], reordered[index]] = [column, reordered[index - 1]];
            setMapping(reordered);
          }}>Move column earlier</button> : null}
        </> : null}
      </section>;
    })}
  </fieldset>;
}
