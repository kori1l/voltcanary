import { target, type Dataset, type Document, type Field, type Language, type Literal, type ReferenceTarget, type Review, type Row } from "./nativeDataApi";

export type FieldDraft = { key: string; label: string; type: string; nullable: boolean; unit: string; indexed: boolean; businessKey: boolean;
  reference?: ReferenceTarget | { kind: "SELF" }; choices?: string };
export type CollectionDraft = { label: string; fields: FieldDraft[]; history: boolean };
export const editableTypes = ["TEXT", "BOOLEAN", "INT64", "DECIMAL", "DATE", "INSTANT", "DURATION", "ENUM", "REF", "INTERVAL", "POINT"];
export const businessKeyTypes = ["TEXT", "ENUM", "INT64", "DATE", "INSTANT", "REF"];
export const enumChoices = (choices = "") => choices.split(/\r?\n/).filter(choice => choice.trim().length > 0);
export const typeLabels: Record<string, string> = { TEXT: "Text", BOOLEAN: "Boolean", INT64: "Whole number", DECIMAL: "Decimal", DATE: "Date", INSTANT: "UTC timestamp", DURATION: "Duration (microseconds)", ENUM: "Choice", REF: "Reference", INTERVAL: "Time interval", POINT: "Coordinates" };
export function publishedCreationTypes(language: Language | null): string[] {
  const literal = language?.registry.$defs.Literal as { oneOf?: { properties?: { type?: { const?: string } } }[] } | undefined;
  const published = new Set(literal?.oneOf?.map(variant => variant.properties?.type?.const) ?? []);
  return editableTypes.filter(type => published.has(type) && (type !== "REF" || publishedReferenceKinds(language).length > 0));
}
export function publishedReferenceKinds(language: Language | null): string[] {
  type Target = { properties?: { resourceKind?: { enum?: string[] } } };
  const collection = language?.registry.$defs.Collection as { properties?: { fields?: { items?: {
    oneOf?: { properties?: { type?: { const?: string }; target?: { oneOf?: Target[] } } }[];
  } } } } | undefined;
  const ref = collection?.properties?.fields?.items?.oneOf?.find(field => field.properties?.type?.const === "REF");
  return ref?.properties?.target?.oneOf?.find(target => target.properties?.resourceKind)?.properties?.resourceKind?.enum ?? [];
}
export const newField = (key: string): FieldDraft => ({ key, label: "", type: "TEXT", nullable: false, unit: "1", indexed: false, businessKey: false });
export function collectionDocument(draft: CollectionDraft, reserved: Review): Document {
  const fields = draft.fields.map(field => ({ id: reserved.fieldIdsByKey![field.key], label: field.label, type: field.type,
    nullable: field.businessKey ? false : field.nullable,
    ...(field.type === "TEXT" ? { maxLength: 120 } : {}),
    ...(field.type === "ENUM" ? { values: enumChoices(field.choices) } : {}),
    ...(field.type === "DECIMAL" ? { precision: 18, scale: 3 } : {}),
    ...(["INT64", "DECIMAL"].includes(field.type) ? { unit: field.unit } : {}),
    ...(field.type === "REF" && field.reference ? {
      target: field.reference.kind === "SELF" ? { kind: "DATASET", datasetId: reserved.resourceId } : field.reference,
    } : {}),
  }));
  return { language: "VCL/1", kind: "COLLECTION", spec: { id: reserved.resourceId, revision: 1, label: draft.label, fields,
    keyFieldIds: draft.fields.filter(field => field.businessKey).map(field => reserved.fieldIdsByKey![field.key]),
    storage: { mode: "NATIVE", history: draft.history ? "REVISIONS" : "NONE", indexedFieldIds: draft.fields.filter(field => field.indexed).map(field => reserved.fieldIdsByKey![field.key]) } } };
}
export type CellDraft = { mode: "UNCHANGED" | "VALUE" | "NULL"; value: string; secondary?: string };
export function literal(field: Field, cell: CellDraft): Literal {
  if (cell.mode === "NULL") return { type: "NULL", value: null };
  if (field.type === "INTERVAL") return { type: "INTERVAL", value: { start: cell.value, end: cell.secondary || null } };
  if (field.type === "POINT") return { type: "POINT", value: { longitude: Number(cell.value), latitude: Number(cell.secondary) } };
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
