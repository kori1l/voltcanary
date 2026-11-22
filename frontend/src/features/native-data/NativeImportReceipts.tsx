import { useState } from "react";
import { nativeDataApi, type ImportChunk } from "./nativeDataApi";

export function NativeImportReceipts({ id }: { id: string }) {
  const [page, setPage] = useState<{
    chunks: ImportChunk[];
    nextAfter: number;
    hasMore: boolean;
  } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function load(afterChunk: number) {
    setBusy(true);
    setError(null);
    try {
      setPage(await nativeDataApi.importChunks(id, afterChunk));
    } catch (failure) {
      setError((failure as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-label="Committed batch receipts" className="form-stack">
      {error ? <p role="alert">{error}</p> : null}
      {page?.chunks.map((chunk) => (
        <details key={chunk.chunkIndex}>
          <summary>
            Batch {chunk.chunkIndex + 1} · {chunk.rows.length} committed
          </summary>
          <ul>
            {chunk.rows.map((row) => (
              <li key={row.recordId}>
                <code>{row.recordId}</code> · Version <code>{row.recordVersion}</code>
              </li>
            ))}
          </ul>
        </details>
      ))}
      {page && !page.chunks.length ? <p>No committed batch receipts on this page.</p> : null}
      <div className="action-row">
        <button
          className="ghost-button"
          type="button"
          disabled={busy}
          onClick={() => void load(-1)}
        >
          {page ? "Reload batch receipts" : "Show committed batch receipts"}
        </button>
        {page?.hasMore ? (
          <button
            className="ghost-button"
            type="button"
            disabled={busy}
            onClick={() => void load(page.nextAfter)}
          >
            Next receipt page
          </button>
        ) : null}
      </div>
    </section>
  );
}
