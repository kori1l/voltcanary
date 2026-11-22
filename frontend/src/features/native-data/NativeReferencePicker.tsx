import { useEffect, useState } from "react";
import { nativeDataApi, type Dataset, type Page, type Row } from "./nativeDataApi";
import { displayLiteral } from "./nativeDocuments";

type Candidates = { table: Dataset; page: Page };

export function NativeReferencePicker({
  datasetId,
  fieldLabel,
  onSelect,
}: {
  datasetId: string;
  fieldLabel: string;
  onSelect: (id: string) => void;
}) {
  const [request, setRequest] = useState<{ cursor: string | null } | null>(null);
  const [candidates, setCandidates] = useState<Candidates | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!request) return;
    const { cursor } = request;
    const controller = new AbortController();
    const { signal } = controller;
    async function load() {
      try {
        const table = await nativeDataApi.describe(datasetId, signal);
        if (!table.liveQueryReady) throw new Error("The linked table cannot be read live.");
        if (signal.aborted) return;
        const page = await nativeDataApi.query(table, cursor, signal);
        if (!signal.aborted) setCandidates({ table, page });
      } catch (failure) {
        if (!signal.aborted) setError((failure as Error).message);
      }
    }
    void load();
    return () => controller.abort();
  }, [datasetId, request]);

  function browse(cursor: string | null) {
    setCandidates(null);
    setError(null);
    setRequest({ cursor });
  }

  if (!request)
    return (
      <button type="button" className="ghost-button" onClick={() => browse(null)}>
        Browse records
      </button>
    );
  return (
    <section aria-label={`Records for ${fieldLabel}`}>
      {error ? <p role="alert">{error}</p> : null}
      {!candidates && !error ? <p role="status">Loading linked records…</p> : null}
      {candidates ? (
        <>
          <p>{candidates.table.definition.label}</p>
          {candidates.page.rows.length ? (
            <ul>
              {candidates.page.rows.map((row) => (
                <li key={row.recordId}>
                  <button
                    type="button"
                    className="ghost-button"
                    onClick={() => {
                      onSelect(row.recordId);
                      setRequest(null);
                    }}
                  >
                    {rowLabel(candidates.table, row)}
                  </button>
                </li>
              ))}
            </ul>
          ) : (
            <p>No records on this page.</p>
          )}
          {candidates.page.completeness === "UNKNOWN" ? (
            <p role="status">
              The linked table changed while paging. Reload to refresh the results.
            </p>
          ) : candidates.page.nextCursor ? (
            <button
              type="button"
              className="ghost-button"
              onClick={() => browse(candidates.page.nextCursor)}
            >
              Next linked records
            </button>
          ) : null}
        </>
      ) : null}
      <div className="action-row">
        {error || request.cursor || candidates?.page.completeness === "UNKNOWN" ? (
          <button type="button" className="ghost-button" onClick={() => browse(null)}>
            Reload linked records
          </button>
        ) : null}
        <button type="button" className="ghost-button" onClick={() => setRequest(null)}>
          Close record picker
        </button>
      </div>
    </section>
  );
}

function rowLabel(table: Dataset, row: Row) {
  const { fields, keyFieldIds } = table.definition;
  const labelFields = keyFieldIds.length
    ? keyFieldIds
    : fields
        .filter((field) => ["TEXT", "ENUM"].includes(field.type))
        .slice(0, 2)
        .map((field) => field.id);
  const values = labelFields
    .map((id) => row.values[id])
    .filter((value) => value && value.type !== "NULL" && value.value !== "");
  if (!values.length) return row.recordId;
  const label = values.map(displayLiteral).join(" · ");
  return keyFieldIds.length ? label : `${label} · ${row.recordId}`;
}
