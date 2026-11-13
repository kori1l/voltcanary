import { target, type Dataset, type Document, type Field, type Language, type Literal, type Review, type Row } from "./nativeDataApi";

export type FieldDraft = { key: string; label: string; type: string; nullable: boolean; unit: string; indexed: boolean; businessKey: boolean };
export type CollectionDraft = { label: string; fields: FieldDraft[]; history: boolean };
export const editableTypes = ["TEXT", "BOOLEAN", "INT64", "DECIMAL", "DATE", "INSTANT", "DURATION", "ENUM"];
export const creationTypes = editableTypes.filter(type => type !== "ENUM");
export const typeLabels: Record<string, string> = { TEXT: "Text", BOOLEAN: "Boolean", INT64: "Whole number", DECIMAL: "Decimal", DATE: "Date", INSTANT: "UTC timestamp", DURATION: "Duration (microseconds)", ENUM: "Choice" };
export function publishedCreationTypes(language: Language | null): string[] {
  const literal = language?.registry.$defs.Literal as { oneOf?: { properties?: { type?: { const?: string } } }[] } | undefined;
  const published = new Set(literal?.oneOf?.map(variant => variant.properties?.type?.const) ?? []);
  return creationTypes.filter(type => published.has(type));
}
export const newField = (key: string): FieldDraft => ({ key, label: "", type: "TEXT", nullable: false, unit: "1", indexed: false, businessKey: false });
export function collectionDocument(draft: CollectionDraft, reserved: Review): Document {
  const fields = draft.fields.map(field => ({ id: reserved.fieldIdsByKey![field.key], label: field.label, type: field.type,
    nullable: field.businessKey ? false : field.nullable,
    ...(field.type === "TEXT" ? { maxLength: 120 } : {}),
    ...(field.type === "DECIMAL" ? { precision: 18, scale: 3 } : {}),
    ...(["INT64", "DECIMAL"].includes(field.type) ? { unit: field.unit } : {}),
  }));
  return { language: "VCL/1", kind: "COLLECTION", spec: { id: reserved.resourceId, revision: 1, label: draft.label, fields,
    keyFieldIds: draft.fields.filter(field => field.businessKey).map(field => reserved.fieldIdsByKey![field.key]),
    storage: { mode: "NATIVE", history: draft.history ? "REVISIONS" : "NONE", indexedFieldIds: draft.fields.filter(field => field.indexed).map(field => reserved.fieldIdsByKey![field.key]) } } };
}
export type CellDraft = { mode: "UNCHANGED" | "VALUE" | "NULL"; value: string };
export function literal(field: Field, cell: CellDraft): Literal {
  if (cell.mode === "NULL") return { type: "NULL", value: null };
  return { type: field.type, value: field.type === "BOOLEAN" ? cell.value === "true" : cell.value,
    ...(["INT64", "DECIMAL"].includes(field.type) ? { unit: field.unit ?? "1" } : {}) };
}
export function mutationDocument(dataset: Dataset, reserved: Review, key: string, operation: string,
  cells: Record<string, CellDraft>, row?: Row): Document {
  const keys = dataset.definition.keyFieldIds;
  const keyPairs = keys.map(id => ({ fieldId: id, literal: row ? row.values[id] : literal(dataset.definition.fields.find(field => field.id === id)!, cells[id]) }));
  if (!keys.length && row) keyPairs.push({ fieldId: dataset.definition.systemFields.recordIdFieldId, literal: { type: "REF", value: row.recordId } });
  const values = operation === "DELETE" ? [] : dataset.definition.fields.filter(field => !keys.includes(field.id) && cells[field.id]?.mode !== "UNCHANGED")
    .map(field => ({ fieldId: field.id, literal: literal(field, cells[field.id]) }));
  return { language: "VCL/1", kind: "MUTATION", spec: { target: target(dataset), mutationId: reserved.mutationId, reviewId: reserved.reviewId,
    idempotencyKey: key, operation, key: keyPairs, values, expectedRowVersion: row?.recordVersion ?? null,
    targetPrecondition: { mode: "NATIVE", generation: dataset.generation, dataVersion: dataset.dataVersion } } };
}
export function displayLiteral(value?: Literal): string {
  if (!value) return "Unavailable";
  if (value.type === "NULL") return "Empty";
  if (typeof value.value === "object") return JSON.stringify(value.value);
  return `${String(value.value)}${value.type === "DURATION" ? " µs" : value.unit && value.unit !== "1" ? ` ${value.unit}` : ""}`;
}
