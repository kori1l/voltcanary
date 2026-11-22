import type { Dataset, ImportReview } from "./nativeDataApi";

const emptyLabels = { REJECT: "Reject", AS_NULL: "Set to null", PRESERVE_EMPTY: "Keep empty text" };
const delimiterLabels: Record<string, string> = { ",": "Comma", ";": "Semicolon", "\t": "Tab" };

export function NativeImportReviewMapping({
  diff,
  dataset,
}: {
  diff: ImportReview["typedDiff"];
  dataset: Dataset;
}) {
  if (!diff.mapping) return null;
  const { format, columns } = diff.mapping;
  const sameRevision =
    diff.target?.datasetId === dataset.id && diff.target.schemaRevision === dataset.schemaRevision;
  const labels = new Map(
    sameRevision ? dataset.definition.fields.map((field) => [field.id, field.label]) : [],
  );
  const delimiter = format.delimiter
    ? (delimiterLabels[format.delimiter] ?? format.delimiter)
    : null;
  return (
    <details>
      <summary>Saved column mapping</summary>
      <p>
        {format.kind} · {format.encoding}
        {delimiter ? ` · ${delimiter} delimiter` : ""}
        {diff.mappingRevision ? ` · Mapping revision ${diff.mappingRevision}` : ""}
      </p>
      {diff.target && !sameRevision ? (
        <p>
          The mapping targets table schema revision {diff.target.schemaRevision}. Its field IDs are
          shown below.
        </p>
      ) : null}
      <ul>
        {columns.map((column) => (
          <li key={column.targetFieldId}>
            <strong>
              {column.sourceColumn} → {labels.get(column.targetFieldId) ?? column.targetFieldId}
            </strong>
            <p>
              {column.sourceType}
              {column.sourceUnit ? ` · ${column.sourceUnit}` : ""} · Empty values:{" "}
              {emptyLabels[column.emptyValue]}
            </p>
            <details>
              <summary>
                {column.conversions.map((conversion) => conversion.method).join(" → ") ||
                  "Conversions"}
              </summary>
              <pre>{JSON.stringify(column.conversions, null, 2)}</pre>
            </details>
          </li>
        ))}
      </ul>
    </details>
  );
}
