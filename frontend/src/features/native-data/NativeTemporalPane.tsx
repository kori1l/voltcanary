import { useState } from "react";
import {
  nativeDataApi,
  type Dataset,
  type SharedTableContext,
  type TemporalMatch,
  type TemporalSegment,
  type TemporalSnapshot,
} from "./nativeDataApi";
import { displayLiteral, literal } from "./nativeDocuments";

const instantPattern = "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]{1,6})?Z";
const reasons: Record<string, string> = {
  ASOF_MISSING: "No matching record",
  ASOF_AMBIGUOUS: "Multiple matching records",
  ASOF_INTERVAL_UNKNOWN: "A matching record has an unknown interval",
};

export function NativeTemporalPane({
  dataset,
  shared,
}: {
  dataset: Dataset;
  shared?: SharedTableContext;
}) {
  const fields = dataset.definition.fields;
  const intervals = fields.filter((field) => field.type === "INTERVAL");
  const keyFields = fields.filter((field) => field.type !== "INTERVAL" && field.type !== "POINT");
  const [interval, setInterval] = useState(intervals[0]?.id ?? "");
  const [keys, setKeys] = useState<string[]>([]);
  const [values, setValues] = useState<Record<string, string>>({});
  const [snapshot, setSnapshot] = useState<TemporalSnapshot | null>(null);
  const [mode, setMode] = useState<"POINT" | "SPLIT">("POINT");
  const [start, setStart] = useState("");
  const [end, setEnd] = useState("");
  const [result, setResult] = useState<{
    version: number;
    match?: TemporalMatch;
    segments?: TemporalSegment[];
  } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function run(seal: boolean) {
    if (busy) return;
    setError(null);
    setResult(null);
    setBusy(true);
    try {
      if (seal) {
        setSnapshot(null);
        setSnapshot(await nativeDataApi.sealTemporal(dataset, interval, keys, shared));
        return;
      }
      if (!snapshot) return;
      const input = keyFields
        .filter((field) => keys.includes(field.id))
        .map((field) => ({
          fieldId: field.id,
          literal: literal(field, { mode: "VALUE", value: values[field.id] ?? "" }),
        }));
      if (mode === "POINT") {
        const response = await nativeDataApi.lookupTemporal(
          dataset,
          snapshot.snapshotId,
          interval,
          input,
          start,
          shared,
        );
        setResult({ version: response.readManifest.dataVersion, match: response });
      } else {
        const response = await nativeDataApi.splitTemporal(
          dataset,
          snapshot.snapshotId,
          interval,
          input,
          start,
          end,
          shared,
        );
        setResult({ version: response.readManifest.dataVersion, segments: response.segments });
      }
    } catch (failure) {
      setError((failure as Error).message);
    } finally {
      setBusy(false);
    }
  }

  function display(match: TemporalMatch) {
    return match.row ? (
      <dl>
        {fields.map((field) => (
          <div key={field.id}>
            <dt>{field.label}</dt>
            <dd>{displayLiteral(match.row?.values[field.id])}</dd>
          </div>
        ))}
      </dl>
    ) : (
      <p>{match.reason ? (reasons[match.reason] ?? match.reason) : match.status}</p>
    );
  }

  return (
    <section className="panel form-stack" aria-label="Event-time lookup">
      <h2>Event-time lookup</h2>
      {error ? <p role="alert">{error}</p> : null}
      <fieldset disabled={busy}>
        <label>
          Effective interval
          <select
            value={interval}
            onChange={(event) => {
              setInterval(event.target.value);
              setSnapshot(null);
              setResult(null);
            }}
          >
            {intervals.map((field) => (
              <option key={field.id} value={field.id}>
                {field.label}
              </option>
            ))}
          </select>
        </label>
        <p>Select up to eight fields to match.</p>
        {keyFields.map((field) => (
          <label key={field.id}>
            <input
              type="checkbox"
              checked={keys.includes(field.id)}
              disabled={keys.length >= 8 && !keys.includes(field.id)}
              onChange={(event) => {
                setKeys((current) =>
                  event.target.checked
                    ? [...current, field.id]
                    : current.filter((id) => id !== field.id),
                );
                setSnapshot(null);
                setResult(null);
              }}
            />
            {field.label}
          </label>
        ))}
        <button
          type="button"
          className="ghost-button"
          disabled={!interval || !keys.length}
          onClick={() => void run(true)}
        >
          {snapshot ? "Capture a new snapshot" : "Capture snapshot"}
        </button>
      </fieldset>
      {snapshot ? (
        <form
          className="form-stack"
          onSubmit={(event) => {
            event.preventDefault();
            void run(false);
          }}
        >
          <p role="status">
            {snapshot.rowCount} captured records · Expires{" "}
            {new Date(snapshot.expiresAt).toLocaleString()}
          </p>
          <fieldset disabled={busy}>
            <label>
              Lookup
              <select
                value={mode}
                onChange={(event) => {
                  setMode(event.target.value as typeof mode);
                  setResult(null);
                }}
              >
                <option value="POINT">At an event time</option>
                <option value="SPLIT">Across a time range</option>
              </select>
            </label>
            {keyFields
              .filter((field) => keys.includes(field.id))
              .map((field) => (
                <label key={field.id}>
                  {field.label}
                  {field.type === "BOOLEAN" || field.type === "ENUM" ? (
                    <select
                      value={values[field.id] ?? ""}
                      required
                      onChange={(event) =>
                        setValues((current) => ({ ...current, [field.id]: event.target.value }))
                      }
                    >
                      <option value="">Select value</option>
                      {(field.type === "BOOLEAN" ? ["false", "true"] : (field.values ?? [])).map(
                        (value) => (
                          <option key={value}>{value}</option>
                        ),
                      )}
                    </select>
                  ) : (
                    <input
                      value={values[field.id] ?? ""}
                      required={field.type !== "TEXT"}
                      onChange={(event) =>
                        setValues((current) => ({ ...current, [field.id]: event.target.value }))
                      }
                    />
                  )}
                </label>
              ))}
            <label>
              {mode === "POINT" ? "Event time (UTC)" : "Start (UTC)"}
              <input
                required
                pattern={instantPattern}
                placeholder="2026-11-03T12:00:00Z"
                value={start}
                onChange={(event) => setStart(event.target.value)}
              />
            </label>
            {mode === "SPLIT" ? (
              <label>
                End (UTC)
                <input
                  required
                  pattern={instantPattern}
                  placeholder="2026-11-03T13:00:00Z"
                  value={end}
                  onChange={(event) => setEnd(event.target.value)}
                />
              </label>
            ) : null}
            <button type="submit" className="primary-button">
              Find records
            </button>
          </fieldset>
        </form>
      ) : null}
      {result ? (
        <>
          <p role="status">Captured data version {result.version}</p>
          {result.match ? (
            display(result.match)
          ) : (
            <ol>
              {result.segments?.map((segment) => (
                <li key={segment.start}>
                  <p>
                    {segment.start} – {segment.end}
                  </p>
                  {display(segment)}
                </li>
              ))}
            </ol>
          )}
        </>
      ) : null}
    </section>
  );
}
