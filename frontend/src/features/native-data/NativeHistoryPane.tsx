import { useEffect, useState } from "react";
import {
  nativeDataApi,
  type HistoricalSchema,
  type HistoryGeneration,
  type Page,
  type SharedTableContext,
} from "./nativeDataApi";
import { displayLiteral } from "./nativeDocuments";

export function NativeHistoryPane({ id, shared }: { id: string; shared?: SharedTableContext }) {
  const [generations, setGenerations] = useState<HistoryGeneration[]>([]);
  const [after, setAfter] = useState<string | null>(null);
  const [next, setNext] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [generation, setGeneration] = useState("");
  const [schema, setSchema] = useState<HistoricalSchema | null>(null);
  const [version, setVersion] = useState("");
  const [page, setPage] = useState<Page | null>(null);
  const [request, setRequest] = useState<{ version: number; cursor: string | null } | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    void nativeDataApi
      .historyGenerations(id, after, controller.signal, shared)
      .then((result) => {
        if (controller.signal.aborted) return;
        setGenerations((current) => (after ? [...current, ...result.items] : result.items));
        setNext(result.nextAfterGeneration);
        setLoaded(true);
      })
      .catch((failure: Error) => {
        if (!controller.signal.aborted) setError(failure.message);
      });
    return () => controller.abort();
  }, [id, after, shared]);
  useEffect(() => {
    if (!generation) return;
    const controller = new AbortController();
    void nativeDataApi
      .historySchema(id, generation, controller.signal, shared)
      .then((result) => {
        if (!controller.signal.aborted) setSchema(result);
      })
      .catch((failure: Error) => {
        if (!controller.signal.aborted) setError(failure.message);
      });
    return () => controller.abort();
  }, [id, generation, shared]);
  useEffect(() => {
    if (!schema || !request) return;
    const controller = new AbortController();
    void nativeDataApi
      .historyPage(
        id,
        generation,
        request.version,
        schema,
        request.cursor,
        controller.signal,
        shared,
      )
      .then((result) => {
        if (!controller.signal.aborted) setPage(result);
      })
      .catch((failure: Error) => {
        if (!controller.signal.aborted) setError(failure.message);
      });
    return () => controller.abort();
  }, [id, generation, request, schema, shared]);

  const max = schema
    ? Math.min(schema.lastDataVersion, schema.firstDataVersion + schema.maxQueryCuts - 1)
    : undefined;
  function openPage(cut: number, cursor: string | null) {
    setError(null);
    setPage(null);
    setRequest({ version: cut, cursor });
  }
  return (
    <section className="panel form-stack" aria-label="Table history">
      <h2>Table history</h2>
      {error ? <p role="alert">{error}</p> : null}
      {!loaded && !error ? <p role="status">Loading history…</p> : null}
      {loaded && !generations.length ? <p>No captured history for this table.</p> : null}
      {generations.length ? (
        <div className="action-row">
          <label>
            Generation
            <select
              value={generation}
              onChange={(event) => {
                const value = event.target.value;
                const item = generations.find((entry) => entry.generation === value);
                setGeneration(value);
                setSchema(null);
                setRequest(null);
                setPage(null);
                setError(null);
                setVersion(item ? String(item.firstDataVersion) : "");
              }}
            >
              <option value="">Select generation</option>
              {generations.map((item) => (
                <option key={item.generation} value={item.generation}>
                  Schema {item.schemaRevision}, versions {item.firstDataVersion}–
                  {item.lastDataVersion} ({item.status.toLowerCase()})
                </option>
              ))}
            </select>
          </label>
          {next ? (
            <button
              type="button"
              className="ghost-button"
              disabled={next === after}
              onClick={() => setAfter(next)}
            >
              Load more generations
            </button>
          ) : null}
        </div>
      ) : null}
      {schema ? (
        <form
          className="action-row"
          onSubmit={(event) => {
            event.preventDefault();
            const cut = Number(version);
            if (
              Number.isSafeInteger(cut) &&
              cut >= schema.firstDataVersion &&
              max !== undefined &&
              cut <= max
            )
              openPage(cut, null);
          }}
        >
          <label>
            Data version
            <input
              type="number"
              required
              min={schema.firstDataVersion}
              max={max}
              step={1}
              value={version}
              onChange={(event) => setVersion(event.target.value)}
            />
          </label>
          <button className="primary-button" type="submit">
            Read history
          </button>
        </form>
      ) : null}
      {request && !page && !error ? <p role="status">Reading version {request.version}…</p> : null}
      {schema && page && request ? (
        <>
          <p role="status">Data version {page.readManifest.dataVersion}</p>
          {page.rows.length ? (
            <div className="native-table-scroll">
              <table>
                <thead>
                  <tr>
                    {schema.definition.fields.map((field) => (
                      <th key={field.id} scope="col">
                        {field.label}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {page.rows.map((row) => (
                    <tr key={row.recordId}>
                      {schema.definition.fields.map((field) => (
                        <td key={field.id}>{displayLiteral(row.values[field.id])}</td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <p>No records at this version.</p>
          )}
          {page.nextCursor ? (
            <button
              type="button"
              className="ghost-button"
              onClick={() => openPage(request.version, page.nextCursor)}
            >
              Next history page
            </button>
          ) : null}
        </>
      ) : null}
    </section>
  );
}
