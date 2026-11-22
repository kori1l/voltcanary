import { useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { nativeDataApi, target, type Dataset, type ImportReview } from "./nativeDataApi";
import {
  importColumns,
  initialImportMapping,
  NativeImportMapping,
  type ImportFormat,
} from "./NativeImportMapping";
import { NativeImportReviewMapping } from "./NativeImportReviewMapping";
import { NativeImportReceipts } from "./NativeImportReceipts";

const modes = {
  ATOMIC_INSERT: "Insert new rows",
  ATOMIC_UPSERT: "Insert or update in one transaction",
  CHUNKED_UPSERT: "Insert or update in batches",
  STAGED_REPLACE: "Replace all rows",
};
type ImportMode = keyof typeof modes;
type ImportCapabilities = Awaited<ReturnType<typeof nativeDataApi.importCapabilities>>;
const formats: Record<ImportFormat, string> = { CSV: ".csv", JSONL: ".jsonl,.ndjson" };
const manifestMaxRowBytes = 1_048_576;
const statusLabels: Record<string, string> = {
  PREPARED: "Ready for confirmation",
  RUNNING: "Import in progress",
  SUCCEEDED: "Import complete",
  PARTIAL: "Import partially complete",
  FAILED: "Import failed",
  CANCELLED: "Import cancelled",
  REJECTED: "Validation failed",
};

export function NativeImportPane({
  dataset,
  onChanged,
}: {
  dataset: Dataset;
  onChanged: () => void;
}) {
  const [params, setParams] = useSearchParams();
  const reviewId = params.get("importReview");
  const [file, setFile] = useState<File | null>(null);
  const [preferredFormat, setFormat] = useState<ImportFormat>("CSV");
  const [delimiter, setDelimiter] = useState(",");
  const [preferredMode, setMode] = useState<ImportMode>("ATOMIC_INSERT");
  const [capabilities, setCapabilities] = useState<ImportCapabilities | null>(null);
  const [capabilityError, setCapabilityError] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [mapping, setMapping] = useState(() => initialImportMapping(dataset.definition.fields));
  const [explicitMapping, setExplicitMapping] = useState<string | null>(null);
  const [review, setReview] = useState<ImportReview | null>(null);
  const [busy, setBusy] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const visible = review?.id === reviewId ? review : null;
  const availableFormats =
    capabilities?.formats.filter((value): value is ImportFormat =>
      Object.prototype.hasOwnProperty.call(formats, value),
    ) ?? [];
  const availableModes =
    capabilities?.modes.filter((value): value is ImportMode =>
      Object.prototype.hasOwnProperty.call(modes, value),
    ) ?? [];
  const format = availableFormats.includes(preferredFormat) ? preferredFormat : availableFormats[0];
  const mode = availableModes.includes(preferredMode) ? preferredMode : availableModes[0];

  useEffect(() => {
    if (reviewId) return;
    const controller = new AbortController();
    async function loadCapabilities() {
      try {
        const result = await nativeDataApi.importCapabilities(controller.signal);
        if (!controller.signal.aborted) setCapabilities(result);
      } catch {
        if (!controller.signal.aborted) setCapabilityError(true);
      }
    }
    void loadCapabilities();
    return () => controller.abort();
  }, [reviewId, attempt]);

  useEffect(() => {
    if (!reviewId || busy) return;
    const savedId = reviewId;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    async function inspect() {
      try {
        const current = await nativeDataApi.inspectImport(dataset.id, savedId, controller.signal);
        if (controller.signal.aborted) return;
        setReview(current);
        if (current.status === "RUNNING") timer = setTimeout(() => void inspect(), 2000);
      } catch (failure) {
        if (!controller.signal.aborted) setError((failure as Error).message);
      }
    }
    void inspect();
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [dataset.id, reviewId, busy]);

  function remember(id: string | null) {
    setParams(
      (current) => {
        const next = new URLSearchParams(current);
        if (id) next.set("importReview", id);
        else next.delete("importReview");
        return next;
      },
      { replace: true },
    );
  }

  async function prepare() {
    if (!file || busy || !capabilities || !format || !mode) return;
    setBusy(true);
    setError(null);
    try {
      const columns: unknown =
        explicitMapping === null ? importColumns(mapping, format) : JSON.parse(explicitMapping);
      if (
        !Array.isArray(columns) ||
        !columns.length ||
        columns.some((column) => !column || typeof column.sourceColumn !== "string")
      ) {
        throw new Error("Enter an array of column mappings with source column names.");
      }
      const maxBytes = capabilities.maxSourceBytes;
      if (file.size > maxBytes)
        throw new Error(`The file exceeds the ${maxBytes.toLocaleString()} byte limit.`);
      const rowLimits: Record<ImportMode, number> = {
        ATOMIC_INSERT: capabilities.maxAtomicRows,
        ATOMIC_UPSERT: capabilities.maxAtomicRows,
        STAGED_REPLACE: capabilities.maxReplacementRows,
        CHUNKED_UPSERT: capabilities.maxChunkedRows,
      };
      const artifact = await nativeDataApi.uploadImport(file, crypto.randomUUID());
      const result = await nativeDataApi.prepareImport(dataset.id, crypto.randomUUID(), {
        language: "VCL/1",
        kind: "IMPORT",
        spec: {
          contractVersion: "IMPORT/1",
          artifactId: artifact.id,
          artifactSha256: artifact.sha256,
          target: target(dataset),
          mappingRevision: 1,
          mode,
          columns,
          format:
            format === "CSV"
              ? {
                  kind: format,
                  encoding: "UTF-8",
                  delimiter,
                  header: columns.map((column) => column.sourceColumn),
                }
              : { kind: format, encoding: "UTF-8" },
          keyFields: dataset.definition.keyFieldIds,
          nullPolicy: "SCHEMA_EXPLICIT",
          conflictPolicy: "REJECT_STALE",
          limits: {
            maxRows: rowLimits[mode],
            maxBytes,
            maxRowBytes: Math.min(maxBytes, manifestMaxRowBytes),
          },
          provenance: { sourceId: artifact.id, description: "Data table import" },
          targetPrecondition: {
            mode: "NATIVE",
            generation: dataset.generation,
            dataVersion: dataset.dataVersion,
          },
        },
      });
      setReview(result);
      remember(result.id);
    } catch (failure) {
      setError((failure as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function act(action: "apply" | "inspect" | "cancel") {
    if (!reviewId || busy || (action === "apply" && !visible)) return;
    setBusy(true);
    setError(null);
    if (action !== "inspect") setUncertain(true);
    try {
      const result =
        action === "apply" && visible
          ? await nativeDataApi.applyImport(dataset.id, visible)
          : action === "cancel"
            ? await nativeDataApi.cancelImport(reviewId)
            : await nativeDataApi.inspectImport(dataset.id, reviewId);
      setReview(result);
      setUncertain(false);
    } catch (failure) {
      setError((failure as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel form-stack" aria-label="Import records">
      <h2>Import records</h2>
      {error ? <p role="alert">{error}</p> : null}
      {!reviewId ? (
        format && mode && capabilities ? (
          <form
            className="form-stack"
            aria-label="Import file"
            onSubmit={(event) => {
              event.preventDefault();
              void prepare();
            }}
          >
            <label>
              File
              <input
                type="file"
                accept={formats[format]}
                required
                disabled={busy}
                onChange={(event) => setFile(event.target.files?.[0] ?? null)}
              />
            </label>
            <p>Maximum file size {capabilities.maxSourceBytes.toLocaleString()} bytes.</p>
            <label>
              Format
              <select
                value={format}
                disabled={busy}
                onChange={(event) => setFormat(event.target.value as ImportFormat)}
              >
                {availableFormats.map((value) => (
                  <option key={value}>{value}</option>
                ))}
              </select>
            </label>
            {format === "CSV" ? (
              <label>
                Delimiter
                <select
                  value={delimiter}
                  disabled={busy}
                  onChange={(event) => setDelimiter(event.target.value)}
                >
                  <option value=",">Comma</option>
                  <option value=";">Semicolon</option>
                  <option value={"\t"}>Tab</option>
                </select>
              </label>
            ) : null}
            <label>
              Operation
              <select
                value={mode}
                disabled={busy}
                onChange={(event) => setMode(event.target.value as ImportMode)}
              >
                {availableModes.map((value) => (
                  <option key={value} value={value}>
                    {modes[value]}
                  </option>
                ))}
              </select>
            </label>
            {explicitMapping === null ? (
              <NativeImportMapping
                mapping={mapping}
                setMapping={setMapping}
                format={format}
                keys={dataset.definition.keyFieldIds}
                busy={busy}
              />
            ) : (
              <label>
                Column mappings (JSON)
                <textarea
                  value={explicitMapping}
                  rows={10}
                  required
                  disabled={busy}
                  onChange={(event) => setExplicitMapping(event.target.value)}
                />
              </label>
            )}
            <button
              className="ghost-button"
              type="button"
              disabled={busy}
              onClick={() => {
                setError(null);
                if (explicitMapping !== null) setExplicitMapping(null);
                else {
                  try {
                    setExplicitMapping(JSON.stringify(importColumns(mapping, format), null, 2));
                  } catch {
                    setExplicitMapping("");
                  }
                }
              }}
            >
              {explicitMapping === null
                ? "Edit mapping as JSON"
                : "Discard JSON edits and return to form"}
            </button>
            <button
              className="primary-button"
              type="submit"
              disabled={busy || !dataset.definition.keyFieldIds.length}
            >
              Validate file
            </button>
            <button className="ghost-button" type="button" disabled={busy} onClick={onChanged}>
              Back to records
            </button>
            {!dataset.definition.keyFieldIds.length ? (
              <p>This import requires a table with business keys.</p>
            ) : null}
          </form>
        ) : (
          <>
            {capabilityError ? (
              <>
                <p role="alert">Import capabilities are unavailable.</p>
                <button
                  className="ghost-button"
                  type="button"
                  onClick={() => {
                    setCapabilityError(false);
                    setAttempt((current) => current + 1);
                  }}
                >
                  Retry loading import capabilities
                </button>
              </>
            ) : (
              <p role="status">
                {capabilities
                  ? "No supported import format or operation is available."
                  : "Loading import capabilities…"}
              </p>
            )}
            <button className="ghost-button" type="button" onClick={onChanged}>
              Back to records
            </button>
          </>
        )
      ) : (
        <>
          {visible ? (
            <>
              <p role="status">
                {uncertain
                  ? "Import outcome unknown. Check the saved outcome before continuing."
                  : (statusLabels[visible.status] ?? visible.status)}
              </p>
              <p>
                {visible.validationComplete
                  ? "Validation complete."
                  : "Validation incomplete. Counts cover records examined so far."}{" "}
                {visible.validRows} valid · {visible.invalidRows} invalid · {visible.processedRows}{" "}
                examined
              </p>
              {visible.totalRows === null ? (
                <p>Total and remaining record counts are unknown.</p>
              ) : null}
              {visible.validationComplete ? (
                <p>
                  Reviewed changes: {visible.typedDiff.inserts} inserts ·{" "}
                  {visible.typedDiff.updates} updates · {visible.typedDiff.deletes} deletions
                </p>
              ) : null}
              {visible.progress && visible.totalRows !== null ? (
                <p>
                  {visible.progress.committedRows}{" "}
                  {uncertain ? "last confirmed committed" : "committed"}
                  {!uncertain ? (
                    <>
                      {" "}
                      · {visible.totalRows - visible.progress.committedRows}
                      {visible.status === "RUNNING" ? " pending" : " uncommitted"}
                    </>
                  ) : (
                    " · Current remaining count unknown"
                  )}
                </p>
              ) : null}
              {visible.progress && visible.progress.committedRows > 0 ? (
                <NativeImportReceipts key={visible.id} id={visible.id} />
              ) : null}
              {visible.reviewDigest ? (
                <details>
                  <summary>Review details</summary>
                  <p>
                    Review digest: <code>{visible.reviewDigest}</code>
                  </p>
                </details>
              ) : null}
              <NativeImportReviewMapping diff={visible.typedDiff} dataset={dataset} />
              {visible.outcomeCode ? (
                <p>
                  {visible.outcomeCode === "ROW_LIMIT_REACHED"
                    ? "Validation stopped at the configured record limit."
                    : visible.outcomeCode}
                </p>
              ) : null}
              {visible.errors.length ? (
                <ul>
                  {visible.errors.map((entry, index) => (
                    <li key={index}>
                      Record {entry.recordOrdinal} · {entry.code}
                    </li>
                  ))}
                </ul>
              ) : null}
              {visible.displayedErrorsTruncated ? (
                <p>Showing the first 100 validation errors.</p>
              ) : null}
            </>
          ) : (
            <p role="status">Loading saved import review…</p>
          )}
          <div className="action-row">
            {visible?.status === "PREPARED" && visible.validationComplete && !uncertain ? (
              <button
                type="button"
                className="primary-button"
                disabled={busy || Date.parse(visible.expiresAt) <= Date.now()}
                onClick={() => void act("apply")}
              >
                Confirm import
              </button>
            ) : null}
            <button
              type="button"
              className="ghost-button"
              disabled={busy}
              onClick={() => void act("inspect")}
            >
              Check saved outcome
            </button>
            {visible?.status === "RUNNING" && !uncertain ? (
              <button
                type="button"
                className="ghost-button"
                disabled={busy}
                onClick={() => void act("cancel")}
              >
                Cancel remaining batches
              </button>
            ) : null}
            <button
              type="button"
              className="ghost-button"
              disabled={busy}
              onClick={() => {
                remember(null);
                setReview(null);
                setUncertain(false);
                setError(null);
                onChanged();
              }}
            >
              Back to records
            </button>
          </div>
        </>
      )}
    </section>
  );
}
