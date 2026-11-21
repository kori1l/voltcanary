import { useState } from "react";
import type { Field, QueryCapabilities, RowFilter } from "./nativeDataApi";
import { literal } from "./nativeDocuments";

const labels: Record<string, string> = {
  eq: "Equals", ne: "Does not equal", lt: "Less than", le: "At most", gt: "Greater than", ge: "At least", is_null: "Is empty",
};

export function NativeRowFilter({ fields, indexes, capabilities, active, busy, onFilter }: {
  fields: Field[]; indexes?: string[]; capabilities?: QueryCapabilities; active: RowFilter | null; busy: boolean;
  onFilter: (filter: RowFilter | null) => void;
}) {
  const [fieldId, setFieldId] = useState("");
  const [operator, setOperator] = useState("");
  const [value, setValue] = useState("");
  const full = capabilities?.predicateFieldTypes ?? [];
  const equality = capabilities?.equalityOnlyFieldTypes ?? [];
  const supported = capabilities?.methods ?? [];
  const choices = fields.filter(field => indexes?.includes(field.id) && (full.includes(field.type) || equality.includes(field.type)));
  const field = choices.find(item => item.id === fieldId);
  const comparisons = !field || field.type === "REF" ? [] : full.includes(field.type) && field.type !== "BOOLEAN"
    ? ["eq", "ne", "lt", "le", "gt", "ge"] : ["eq", "ne"];
  const methods = [...comparisons, "is_null"].filter(method => supported.includes(method));
  if (!choices.length && !active) return null;

  return <form className="action-row" aria-label="Filter records" onSubmit={event => {
    event.preventDefault();
    if (!field || !methods.includes(operator) || busy) return;
    onFilter({ fieldId, method: operator, ...(operator === "is_null" ? {} : { literal: literal(field, { mode: "VALUE", value }) }) });
  }}>
    <label>Filter field<select value={fieldId} disabled={busy} onChange={event => {
      setFieldId(event.target.value);
      setValue("");
      setOperator("");
    }}>
      <option value="">Select field</option>
      {choices.map(item => <option key={item.id} value={item.id}>{item.label}</option>)}
    </select></label>
    {field ? <label>Condition<select required value={operator} disabled={busy} onChange={event => setOperator(event.target.value)}>
      <option value="">Select condition</option>
      {methods.map(method => <option key={method} value={method}>{labels[method]}</option>)}
    </select></label> : null}
    {field && operator && operator !== "is_null" ? <label>Value{["BOOLEAN", "ENUM"].includes(field.type)
      ? <select required value={value} disabled={busy} onChange={event => setValue(event.target.value)}>
        <option value="">Select value</option>
        {(field.type === "BOOLEAN" ? ["true", "false"] : field.values ?? []).map(item => <option key={item}>{item}</option>)}
      </select> : <input required={field.type !== "TEXT"} value={value} disabled={busy} type={field.type === "DATE" ? "date" : "text"}
        onChange={event => setValue(event.target.value)}/>}</label> : null}
    <button type="submit" className="ghost-button" disabled={busy || !field || !operator}>Apply filter</button>
    {active ? <button type="button" className="ghost-button" disabled={busy} onClick={() => {
      setFieldId(""); setOperator(""); setValue(""); onFilter(null);
    }}>Clear filter</button> : null}
  </form>;
}
