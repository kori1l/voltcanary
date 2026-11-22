import type { DashboardWidget } from "../../../dashboards/dashboardsApi";
import { target, type Dataset } from "../../native-data/nativeDataApi";

export function configuredRecordsBinding(
  dataset: Dataset,
  selected: string[],
): Pick<DashboardWidget, "binding" | "query" | "payload"> {
  const columns = dataset.definition.fields.filter((field) => selected.includes(field.id));
  return {
    binding: { mode: "DYNAMIC" },
    query: {
      language: "VCL/1",
      kind: "QUERY",
      spec: {
        target: target(dataset),
        select: columns.map((field) => field.id),
        where: { op: "CONST", literal: { type: "BOOLEAN", value: true } },
        order: [{ fieldId: dataset.definition.systemFields.recordIdFieldId, direction: "ASC" }],
        limit: 25,
        cursor: null,
        read: { mode: "LIVE" },
      },
    },
    payload: { columnLabels: Object.fromEntries(columns.map((field) => [field.id, field.label])) },
  };
}

export function configuredRecordsSelection(query: DashboardWidget["query"]) {
  const spec = query?.spec as
    { target?: { datasetId?: string; schemaRevision?: number }; select?: string[] } | undefined;
  return {
    table: spec?.target?.datasetId ?? "",
    revision: spec?.target?.schemaRevision,
    columns: Array.isArray(spec?.select) ? spec.select : [],
  };
}
