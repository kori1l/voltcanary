import { useMemo, useState } from "react";
import type { DashboardWidget } from "../../../dashboards/dashboardsApi";
import type { Literal } from "../../native-data/nativeDataApi";
import { displayLiteral } from "../../native-data/nativeDocuments";
import { DashboardRecordMap, type RecordPoint } from "./DashboardRecordMap";

type Records = {
  columns: string[];
  rows: { recordId: string; values: Record<string, Literal> }[];
  completeness: string;
  hasMore: boolean;
};

export function DashboardConfiguredRecords({
  widget,
  value,
}: {
  widget: DashboardWidget;
  value: unknown;
}) {
  const data = value as Records | undefined;
  const labels = widget.payload?.columnLabels as Record<string, unknown> | undefined;
  const points = useMemo(() => collectPoints(data, labels), [data, labels]);
  const [showMap, setShowMap] = useState(false);
  if (!data || !Array.isArray(data.columns) || !Array.isArray(data.rows))
    return <p role="status">Table evidence unavailable.</p>;
  return (
    <>
      {data.completeness !== "COMPLETE" ? <p role="status">The result may be incomplete.</p> : null}
      {points.length ? (
        <button
          className="ghost-button"
          type="button"
          aria-expanded={showMap}
          onClick={() => setShowMap((value) => !value)}
        >
          {showMap ? "Hide recorded locations" : "Show recorded locations"}
        </button>
      ) : null}
      {showMap && points.length ? <DashboardRecordMap points={points} /> : null}
      {data.rows.length ? (
        <div className="dashboard-configured-records">
          <table>
            <thead>
              <tr>
                {data.columns.map((id, index) => (
                  <th key={id} scope="col">
                    {typeof labels?.[id] === "string"
                      ? (labels[id] as string)
                      : `Field ${index + 1}`}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {data.rows.map((row) => (
                <tr key={row.recordId}>
                  {data.columns.map((id) => (
                    <td key={id}>{displayLiteral(row.values[id])}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <p role="status">No records on this page.</p>
      )}
      {data.hasMore ? <p>Showing the first {data.rows.length} records.</p> : null}
    </>
  );
}

function collectPoints(
  data: Records | undefined,
  labels: Record<string, unknown> | undefined,
): RecordPoint[] {
  if (!Array.isArray(data?.rows) || !Array.isArray(data.columns)) return [];
  return data.rows.flatMap((row, index) =>
    data.columns.flatMap((column) => {
      const literal = row.values?.[column];
      if (literal?.type !== "POINT" || !literal.value || typeof literal.value !== "object")
        return [];
      const { longitude, latitude } = literal.value as { longitude?: unknown; latitude?: unknown };
      if (
        typeof longitude !== "number" ||
        typeof latitude !== "number" ||
        !Number.isFinite(longitude) ||
        !Number.isFinite(latitude) ||
        Math.abs(longitude) > 180 ||
        Math.abs(latitude) > 90
      )
        return [];
      const name = typeof labels?.[column] === "string" ? labels[column] : "Location";
      return [{ label: `${name}, row ${index + 1}`, longitude, latitude }];
    }),
  );
}
