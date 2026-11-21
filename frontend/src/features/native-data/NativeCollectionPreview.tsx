import type { Field } from "./nativeDataApi";
import { typeLabels } from "./nativeDocuments";
import "./NativeCollectionPreview.css";

export type NativeCollectionDefinition = {
  label: string;
  fields: Field[];
  keyFieldIds: string[];
  storage: { mode: "NATIVE"; history: "NONE" | "REVISIONS"; indexedFieldIds: string[] };
};

function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

/** Display guard only; the ordinary signed owner/compiler remains the schema and authority boundary. */
export function readNativeCollectionDefinition(value: unknown): NativeCollectionDefinition | null {
  if (!record(value) || typeof value.label !== "string" || !value.label.trim() || !Array.isArray(value.fields)
    || value.fields.length < 1 || value.fields.length > 64 || !record(value.storage) || value.storage.mode !== "NATIVE"
    || !["NONE", "REVISIONS"].includes(String(value.storage.history))) return null;
  const fields = value.fields;
  if (!fields.every(field => record(field) && typeof field.id === "string" && field.id.length > 0 && typeof field.label === "string"
    && field.label.length > 0 && typeof field.type === "string" && field.type.length > 0 && typeof field.nullable === "boolean"
    && (field.unit === undefined || typeof field.unit === "string")
    && (field.values === undefined || (Array.isArray(field.values) && field.values.every(value => typeof value === "string")))
    && (field.target === undefined || (record(field.target) &&
      (field.target.kind === "DATASET" && typeof field.target.datasetId === "string"
        || field.target.kind === "RESOURCE" && typeof field.target.resourceKind === "string")))
    && [field.maxLength, field.precision, field.scale].every(value => value === undefined || (typeof value === "number" && Number.isSafeInteger(value) && value >= 0)))) return null;
  const ids = new Set(fields.map(field => String(field.id)));
  if (ids.size !== fields.length) return null;
  for (const keys of [value.keyFieldIds, value.storage.indexedFieldIds]) {
    if (!Array.isArray(keys) || new Set(keys).size !== keys.length || !keys.every(key => typeof key === "string" && ids.has(key))) return null;
  }
  return value as NativeCollectionDefinition;
}

export function NativeCollectionPreview({ definition }: { definition: NativeCollectionDefinition }) {
  const fields = definition.fields;
  const labels = (ids: string[]) => ids.map(id => fields.find(field => field.id === id)!.label).join(", ");
  return <section className="native-schema-preview" aria-label="Table schema preview">
    <h3>{definition.label}</h3>
    <ul>{fields.map(field => <li key={field.id}>{field.label}: {typeLabels[field.type] ?? field.type}
      {field.unit && field.unit !== "1" ? ` (${field.unit})` : ""}{field.nullable ? " · Allows empty" : " · Required"}
      {field.type === "TEXT" && field.maxLength ? ` · Maximum ${field.maxLength} characters` : ""}
      {field.type === "ENUM" && field.values?.length ? ` · Allowed values: ${field.values.join(", ")}` : ""}
      {field.type === "DECIMAL" && field.precision !== undefined && field.scale !== undefined ? ` · Precision ${field.precision}, scale ${field.scale}` : ""}
      {field.target ? ` · ${field.target.kind === "DATASET" ? "Table " + field.target.datasetId : field.target.resourceKind.toLowerCase().replace(/_/g, " ")}` : ""}
    </li>)}</ul>
    <p>Unique key: {labels(definition.keyFieldIds) || "Automatically assigned row identity"}</p>
    <p>Indexed columns: {labels(definition.storage.indexedFieldIds) || "None"}</p>
    <p>Keep row revisions: {definition.storage.history === "REVISIONS" ? "Yes" : "No"}</p>
  </section>;
}
