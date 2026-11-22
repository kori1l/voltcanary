import { useEffect, useState } from "react";
import { listCircles, type CircleRevision } from "./nativeCircleApi";

export function NativeCircleBrowser({ onSelect }: { onSelect: (circle: CircleRevision) => void }) {
  const [request, setRequest] = useState<{ after: string | null }>({ after: null });
  const [page, setPage] = useState<Awaited<ReturnType<typeof listCircles>> | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    setPage(null);
    setError(null);
    void listCircles(request.after, controller.signal)
      .then((result) => {
        if (!controller.signal.aborted) setPage(result);
      })
      .catch((failure) => {
        if (!controller.signal.aborted) setError((failure as Error).message);
      });
    return () => controller.abort();
  }, [request]);

  if (error)
    return (
      <div>
        <p role="alert">{error}</p>
        <button type="button" className="ghost-button" onClick={() => setRequest({ after: null })}>
          Reload zones
        </button>
      </div>
    );
  if (!page) return <p role="status">Loading saved zones…</p>;
  return (
    <div className="form-stack">
      {page.items.length ? (
        <label>
          Saved zone
          <select
            value=""
            onChange={(event) => {
              const selected = page.items.find((circle) => circle.id === event.target.value);
              if (selected) onSelect(selected);
            }}
          >
            <option value="" disabled>
              Select a zone
            </option>
            {page.items.map(({ id, revision, geometry }) => (
              <option key={id} value={id}>
                {geometry.latitude}, {geometry.longitude} · {geometry.radiusMetres} m · revision{" "}
                {revision} · {id}
              </option>
            ))}
          </select>
        </label>
      ) : (
        <p>No zones on this page.</p>
      )}
      {request.after || page.nextAfter ? (
        <div className="action-row">
          {request.after ? (
            <button
              type="button"
              className="ghost-button"
              onClick={() => setRequest({ after: null })}
            >
              First page
            </button>
          ) : null}
          {page.nextAfter ? (
            <button
              type="button"
              className="ghost-button"
              onClick={() => setRequest({ after: page.nextAfter })}
            >
              Next zones
            </button>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
