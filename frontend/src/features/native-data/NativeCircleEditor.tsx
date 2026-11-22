import { useEffect, useRef, useState, type FormEvent } from "react";
import { NativePointEditor } from "./NativePointEditor";
import {
  readCircle,
  readCircleDependencies,
  saveCircle,
  type CircleDependency,
  type CircleGeometry,
  type CircleRevision,
} from "./nativeCircleApi";
import type { CellDraft } from "./nativeDocuments";
import { NativeCircleBrowser } from "./NativeCircleBrowser";

type Review = {
  id: string;
  expectedRevision: number;
  geometry: CircleGeometry;
  dependencies: CircleDependency[];
};
const emptyPoint: CellDraft = { mode: "VALUE", value: "", secondary: "" };
const uuid = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

function exactCircle(review: Review, saved: CircleRevision) {
  return (
    saved.id === review.id &&
    saved.revision === review.expectedRevision + 1 &&
    saved.geometry.longitude === review.geometry.longitude &&
    saved.geometry.latitude === review.geometry.latitude &&
    saved.geometry.radiusMetres === review.geometry.radiusMetres
  );
}

export function NativeCircleEditor() {
  const [base, setBase] = useState<CircleRevision | null>(null);
  const [point, setPoint] = useState(emptyPoint);
  const [radius, setRadius] = useState("");
  const [review, setReview] = useState<Review | null>(null);
  const [saved, setSaved] = useState<CircleRevision | null>(null);
  const [busy, setBusy] = useState(false);
  const [unknown, setUnknown] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const request = useRef<AbortController | null>(null);
  useEffect(() => () => request.current?.abort(), []);

  async function run(action: (signal: AbortSignal) => Promise<void>) {
    if (request.current) return;
    const controller = new AbortController();
    request.current = controller;
    setBusy(true);
    setError(null);
    try {
      await action(controller.signal);
    } catch (failure) {
      if (!controller.signal.aborted) setError((failure as Error).message);
    } finally {
      request.current = null;
      if (!controller.signal.aborted) setBusy(false);
    }
  }

  function load(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const values = new FormData(event.currentTarget);
    const id = String(values.get("id")).toLowerCase();
    const revision = Number(values.get("revision"));
    void run(async (signal) => {
      const existing = await readCircle(id, revision, signal);
      if (signal.aborted) return;
      select(existing);
    });
  }

  function select(existing: CircleRevision) {
    setBase(existing);
    setPoint({
      mode: "VALUE",
      value: String(existing.geometry.longitude),
      secondary: String(existing.geometry.latitude),
    });
    setRadius(existing.geometry.radiusMetres);
    setSaved(null);
  }

  function prepare(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const geometry = {
      longitude: Number(point.value),
      latitude: Number(point.secondary),
      radiusMetres: radius.replace(/(\.\d*?)0+$/, "$1").replace(/\.$/, ""),
    };
    void run(async (signal) => {
      const id = base?.id ?? crypto.randomUUID();
      const expectedRevision = base?.revision ?? 0;
      const rules = base ? await readCircleDependencies(id, expectedRevision, signal) : null;
      if (signal.aborted) return;
      if (
        rules &&
        (!rules.complete || rules.circleId !== id || rules.revision !== expectedRevision)
      ) {
        throw new Error("Dependencies could not be verified for this zone revision.");
      }
      setReview({ id, expectedRevision, geometry, dependencies: rules?.dependencies ?? [] });
      setSaved(null);
    });
  }

  function finish(action: "save" | "inspect") {
    if (!review) return;
    void run(async (signal) => {
      if (action === "save") setUnknown(true);
      const result =
        action === "save"
          ? await saveCircle(review.id, review.expectedRevision, review.geometry, signal)
          : await readCircle(review.id, review.expectedRevision + 1, signal);
      if (signal.aborted) return;
      if (!exactCircle(review, result))
        throw new Error("The stored geometry differs from this review.");
      setSaved(result);
      setBase(result);
      setReview(null);
      setUnknown(false);
    });
  }

  return (
    <section className="panel form-stack" aria-label="Circle zones">
      <h2>Circle zones</h2>
      {error ? <p role="alert">{error}</p> : null}
      {saved ? (
        <p role="status">
          Saved zone {saved.id}, revision {saved.revision}.
        </p>
      ) : null}
      {!review ? (
        <>
          <form className="form-stack" onSubmit={load}>
            <fieldset className="form-stack" disabled={busy}>
              <legend>Load a saved zone</legend>
              <NativeCircleBrowser onSelect={select} />
              <details>
                <summary>Open a specific revision</summary>
                <label>
                  Zone ID
                  <input name="id" pattern={uuid} required />
                </label>
                <label>
                  Revision
                  <input
                    name="revision"
                    type="number"
                    min={1}
                    max={128}
                    required
                    defaultValue={1}
                  />
                </label>
                <button className="ghost-button" type="submit">
                  Load revision
                </button>
              </details>
            </fieldset>
          </form>
          <form className="form-stack" onSubmit={prepare}>
            <fieldset className="form-stack" disabled={busy}>
              <legend>{base ? `Revise zone ${base.id}` : "Create a zone"}</legend>
              <NativePointEditor
                label="Zone center"
                cell={point}
                onChange={(change) => setPoint((value) => ({ ...value, ...change }))}
              />
              <label>
                Radius (metres)
                <input
                  required
                  value={radius}
                  maxLength={32}
                  inputMode="decimal"
                  pattern="(?:0|[1-9][0-9]*)(?:[.][0-9]{1,12})?"
                  onChange={(event) => setRadius(event.target.value)}
                />
              </label>
              <button
                type="submit"
                className="primary-button"
                disabled={
                  base?.revision === 128 ||
                  !radius ||
                  Number(radius) <= 0 ||
                  Number(radius) > 20_100_000
                }
              >
                Review zone
              </button>
              {base ? (
                <button
                  type="button"
                  className="ghost-button"
                  onClick={() => {
                    setBase(null);
                    setPoint(emptyPoint);
                    setRadius("");
                    setSaved(null);
                    setError(null);
                  }}
                >
                  Create another zone
                </button>
              ) : null}
            </fieldset>
          </form>
        </>
      ) : (
        <section className="native-field" aria-label="Zone review">
          <h3>Review zone revision {review.expectedRevision + 1}</h3>
          <p>
            Center: {review.geometry.latitude}, {review.geometry.longitude} · Radius:{" "}
            {review.geometry.radiusMetres} m
          </p>
          {review.expectedRevision > 0 ? (
            <>
              <p>
                {review.dependencies.length
                  ? `These rules use revision ${review.expectedRevision}. Review each rule before switching it to the new revision.`
                  : `No compiled rules reference revision ${review.expectedRevision}.`}
              </p>
              {review.dependencies.length ? (
                <ul aria-label="Rules using the current zone revision">
                  {review.dependencies.map((dependency) => (
                    <li key={dependency.id}>
                      {dependency.kind === "RULE_DEPLOYMENT" ? "Rule deployment" : "Rule bundle"} ·{" "}
                      <code>{dependency.id}</code>
                      {dependency.ruleId ? (
                        <>
                          {" "}
                          · Rule <code>{dependency.ruleId}</code>
                        </>
                      ) : null}
                      {dependency.state ? " · " + dependency.state : null}
                    </li>
                  ))}
                </ul>
              ) : null}
            </>
          ) : null}
          {unknown ? (
            <p role="status">
              Save outcome unknown. Check the proposed revision or retry the same change.
            </p>
          ) : null}
          <div className="action-row">
            <button
              type="button"
              className="primary-button"
              disabled={busy}
              onClick={() => finish("save")}
            >
              {unknown ? "Retry same change" : "Confirm and save zone"}
            </button>
            {unknown ? (
              <button
                type="button"
                className="ghost-button"
                disabled={busy}
                onClick={() => finish("inspect")}
              >
                Check revision
              </button>
            ) : (
              <button
                type="button"
                className="ghost-button"
                disabled={busy}
                onClick={() => setReview(null)}
              >
                Edit geometry
              </button>
            )}
          </div>
        </section>
      )}
    </section>
  );
}
