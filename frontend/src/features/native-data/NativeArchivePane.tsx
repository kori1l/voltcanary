import { useEffect, useState } from "react";
import { nativeDataApi, type Summary } from "./nativeDataApi";
import { NativeHistoryPane } from "./NativeHistoryPane";

export function NativeArchivePane({ revision }: { revision: number }) {
  const [tables, setTables] = useState<Summary[] | null>(null);
  const [selected, setSelected] = useState("");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    setError(null);
    void nativeDataApi
      .archived(controller.signal)
      .then((result) => {
        if (controller.signal.aborted) return;
        setTables(result);
        setSelected((previous) => (result.some((table) => table.id === previous) ? previous : ""));
      })
      .catch((failure: Error) => {
        if (!controller.signal.aborted) setError(failure.message);
      });
    return () => controller.abort();
  }, [revision]);

  return (
    <section className="panel form-stack" aria-label="Archived tables">
      <h2>Archived tables</h2>
      {error ? <p role="alert">{error}</p> : null}
      {!tables && !error ? <p role="status">Loading archived tables…</p> : null}
      {tables?.length === 0 ? <p>No archived tables.</p> : null}
      {tables?.length ? (
        <label>
          Archived table
          <select value={selected} onChange={(event) => setSelected(event.target.value)}>
            <option value="">Select table</option>
            {tables.map((table) => (
              <option key={table.id} value={table.id}>
                {table.label}
              </option>
            ))}
          </select>
        </label>
      ) : null}
      {selected ? <NativeHistoryPane key={selected} id={selected} /> : null}
    </section>
  );
}
