import { useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { ApiError } from "../../api/client";
import { nativeDataApi, type Dataset, type MigrationReview } from "./nativeDataApi";

type Mode = "PRESERVE" | "RESET" | "ARCHIVE";
const dependencyLabels: Record<string, string> = {
  DASHBOARD_DRAFT: "Dashboard draft",
  PUBLISHED_DASHBOARD: "Published dashboard",
  RULE_BUNDLE: "Rule bundle",
  RULE_DEPLOYMENT: "Deployed rule",
  AUTOMATION_GRANT: "Alert automation grant",
  GRANT_REVIEW: "Alert grant review",
};
const requiresNewReview = (code?: string | null) =>
  code === "VALIDATION_STALE" || code === "REVIEW_EXPIRED";

export function NativeMigrationPane({
  id,
  dataset,
  onClose,
}: {
  id: string;
  dataset: Dataset | null;
  onClose: (archived: boolean) => void;
}) {
  const [params, setParams] = useSearchParams();
  const savedId = params.get("migrationReview");
  const [review, setReview] = useState<MigrationReview | null>(null);
  const [mode, setMode] = useState<Mode>("PRESERVE");
  const [keys, setKeys] = useState("");
  const [schema, setSchema] = useState("");
  const [mappings, setMappings] = useState("[]");
  const [busy, setBusy] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [rejectedReview, setRejectedReview] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const visible = review?.id === savedId ? review : null;
  const renew = Boolean(
    savedId && (rejectedReview === savedId || requiresNewReview(visible?.outcomeCode)),
  );

  useEffect(() => {
    if (!dataset) return;
    const { generation, dataVersion, systemFields, ...spec } =
      dataset.definition as Dataset["definition"] & {
        generation?: string;
        dataVersion?: number;
      };
    void generation;
    void dataVersion;
    void systemFields;
    setSchema(
      JSON.stringify(
        {
          language: "VCL/1",
          kind: "COLLECTION",
          spec: {
            ...spec,
            id,
            revision: dataset.schemaRevision + 1,
          },
        },
        null,
        2,
      ),
    );
  }, [dataset, id]);

  useEffect(() => {
    if (!savedId || busy) return;
    const controller = new AbortController();
    void nativeDataApi
      .inspectMigration(id, savedId, controller.signal)
      .then((result) => {
        if (!controller.signal.aborted) setReview(result);
      })
      .catch((failure: Error) => {
        if (!controller.signal.aborted) setError(failure.message);
      });
    return () => controller.abort();
  }, [id, savedId, busy]);

  function remember(reviewId: string | null) {
    setParams(
      (current) => {
        const next = new URLSearchParams(current);
        if (reviewId) next.set("migrationReview", reviewId);
        else {
          next.delete("migrationReview");
          if (visible?.receipt?.status === "ARCHIVED") next.delete("datasetId");
        }
        return next;
      },
      { replace: true },
    );
  }

  async function run(action: "reserve" | "prepare" | "apply" | "inspect") {
    if (busy || (action !== "reserve" && !savedId)) return;
    setBusy(true);
    setError(null);
    if (action === "apply") setUncertain(true);
    try {
      let result: MigrationReview;
      switch (action) {
        case "reserve":
          result = await nativeDataApi.reserveMigration(
            id,
            keys.split(/[,\s]+/).filter(Boolean),
            crypto.randomUUID(),
          );
          remember(result.id);
          break;
        case "prepare":
          if (!savedId) return;
          result = await nativeDataApi.prepareMigration(
            id,
            savedId,
            mode === "ARCHIVE"
              ? {
                  schema: null,
                  mappings: [],
                  dataMode: mode,
                }
              : { schema: JSON.parse(schema), mappings: JSON.parse(mappings), dataMode: mode },
          );
          break;
        case "apply":
          if (!visible) return;
          result = await nativeDataApi.applyMigration(id, visible);
          break;
        case "inspect":
          if (!savedId) return;
          result = await nativeDataApi.inspectMigration(id, savedId);
          break;
      }
      setReview(result);
      setUncertain(false);
    } catch (failure) {
      if (action === "apply" && failure instanceof ApiError && requiresNewReview(failure.code)) {
        setRejectedReview(savedId);
        setUncertain(false);
      } else {
        setError((failure as Error).message);
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel form-stack" aria-label="Change table schema">
      <h2>Change table schema</h2>
      {error ? <p role="alert">{error}</p> : null}
      {renew ? (
        <p role="alert">
          This review is no longer current. Start a new review to refresh the schema and
          dependencies.
        </p>
      ) : null}
      {!savedId ? (
        <>
          <label>
            New field keys
            <input value={keys} onChange={(event) => setKeys(event.target.value)} disabled={busy} />
          </label>
          <button
            type="button"
            className="primary-button"
            disabled={busy || !dataset}
            onClick={() => void run("reserve")}
          >
            Reserve field IDs
          </button>
        </>
      ) : null}
      {visible?.status === "RESERVED" ? (
        <>
          {visible.fields.length ? (
            <ul>
              {visible.fields.map((field) => (
                <li key={field.id}>
                  {field.key} · <code>{field.id}</code>
                </li>
              ))}
            </ul>
          ) : null}
          <label>
            Operation
            <select
              value={mode}
              disabled={busy}
              onChange={(event) => setMode(event.target.value as Mode)}
            >
              <option value="PRESERVE">Migrate and preserve records</option>
              <option value="RESET">Migrate and clear records</option>
              <option value="ARCHIVE">Archive table</option>
            </select>
          </label>
          {mode === "ARCHIVE" ? null : (
            <>
              <label>
                Collection schema (JSON)
                <textarea
                  rows={12}
                  value={schema}
                  onChange={(event) => setSchema(event.target.value)}
                  disabled={busy}
                />
              </label>
              <label>
                Field mappings (JSON)
                <textarea
                  rows={5}
                  value={mappings}
                  onChange={(event) => setMappings(event.target.value)}
                  disabled={busy}
                />
              </label>
            </>
          )}
          <button
            type="button"
            className="primary-button"
            disabled={busy || (mode !== "ARCHIVE" && !schema)}
            onClick={() => void run("prepare")}
          >
            Review impact
          </button>
        </>
      ) : null}
      {visible ? (
        <p role="status">
          {visible.status}
          {visible.outcomeCode ? " · " + visible.outcomeCode : ""}
        </p>
      ) : null}
      {visible?.impact ? (
        <>
          <p>
            {visible.impact.addedFieldIds.length} fields added ·{" "}
            {visible.impact.changedFieldIds.length} changed ·{" "}
            {visible.impact.removedFieldIds.length} removed
          </p>
          {visible.impact.blocked ? <p role="alert">Dependencies block this operation.</p> : null}
          {visible.impact.owners.some((owner) => owner.dependencies.length > 0) ? (
            <ul aria-label="Affected rules, dashboards and grants">
              {visible.impact.owners.flatMap((owner) =>
                owner.dependencies.map((dependency) => (
                  <li key={`${owner.owner}/${dependency.kind}/${dependency.id}`}>
                    {dependencyLabels[dependency.kind] ?? dependency.kind} ·{" "}
                    <code>{dependency.id}</code>
                    {dependency.blocking ? " · Blocks this change" : " · Review before reuse"}
                  </li>
                )),
              )}
            </ul>
          ) : null}
          <details>
            <summary>Impact details</summary>
            {visible.reviewDigest ? (
              <p>
                Review digest <code>{visible.reviewDigest}</code>
              </p>
            ) : null}
            <pre>{JSON.stringify(visible.impact, null, 2)}</pre>
          </details>
        </>
      ) : null}
      {visible?.receipt ? (
        <p role="status">
          {visible.receipt.status === "ARCHIVED"
            ? "Table archived"
            : "Schema revision " + visible.receipt.schemaRevision + " applied"}
        </p>
      ) : null}
      <div className="action-row">
        {visible &&
        ["PREPARED", "APPLYING", "UNKNOWN"].includes(visible.status) &&
        !visible.impact?.blocked &&
        !uncertain &&
        !renew ? (
          <button
            type="button"
            className="primary-button"
            disabled={busy || Date.parse(visible.expiresAt) <= Date.now()}
            onClick={() => void run("apply")}
          >
            {visible.impact?.operation === "ARCHIVE" ? "Confirm archive" : "Confirm migration"}
          </button>
        ) : null}
        {savedId ? (
          <button
            type="button"
            className="ghost-button"
            disabled={busy}
            onClick={() => void run("inspect")}
          >
            Check saved outcome
          </button>
        ) : null}
        <button
          type="button"
          className="ghost-button"
          disabled={busy}
          onClick={() => {
            remember(null);
            onClose(visible?.receipt?.status === "ARCHIVED");
          }}
        >
          Back to records
        </button>
      </div>
    </section>
  );
}
