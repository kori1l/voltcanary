import { useEffect, useState } from "react";
import { apiRequest } from "../../../api/client";
import { getCurrentDeviceAccount } from "../../../devices/devicesApi";

type MeasurePage = {
  items: { measure: string; node: string; from: string; until: string; status: string }[];
  nextAfter: string | null;
};

export function RuleMeasurePicker({
  epoch,
  onSelect,
}: {
  epoch: string;
  onSelect: (measure: string) => void;
}) {
  const [request, setRequest] = useState<{ after: string | null } | null>(null);
  const [result, setResult] = useState<MeasurePage | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (!request) return;
    const controller = new AbortController();
    void getCurrentDeviceAccount()
      .then((account) => {
        if (controller.signal.aborted) return;
        return apiRequest<MeasurePage>(
          "/analytics/configurable/rule-cutovers/historical-measure-list",
          {
            method: "POST",
            body: JSON.stringify({ account: account.id, epoch, after: request.after }),
            signal: controller.signal,
          },
        );
      })
      .then((page) => {
        if (!controller.signal.aborted && page) setResult(page);
      })
      .catch((failure) => {
        if (!controller.signal.aborted) setError((failure as Error).message);
      });
    return () => controller.abort();
  }, [epoch, request]);

  function load(after: string | null) {
    setResult(null);
    setError(null);
    setRequest({ after });
  }

  return (
    <div className="form-stack">
      <button type="button" className="ghost-button" onClick={() => load(null)}>
        Browse saved measures
      </button>
      {request && !result && !error ? <p role="status">Loading saved measures…</p> : null}
      {error ? <p role="alert">{error}</p> : null}
      {result?.items.length ? (
        <label>
          Saved measure
          <select value="" onChange={(event) => onSelect(event.target.value)}>
            <option value="" disabled>
              Select a measure
            </option>
            {result.items.map((item) => (
              <option key={item.measure} value={item.measure}>
                {item.node} · {item.from} to {item.until} · {item.status}
              </option>
            ))}
          </select>
        </label>
      ) : result ? (
        <p>No measures on this page.</p>
      ) : null}
      {result?.nextAfter ? (
        <button type="button" className="ghost-button" onClick={() => load(result.nextAfter)}>
          Next measures
        </button>
      ) : null}
    </div>
  );
}
