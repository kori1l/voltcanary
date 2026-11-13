import { useEffect, useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { nativeDataApi, type Language, type Review, type Summary } from "./nativeDataApi";
import { collectionDocument, mutationDocument, publishedCreationTypes, type CollectionDraft } from "./nativeDocuments";
import { CollectionForm } from "./CollectionForm";
import { NativeTablePane, type RowIntent } from "./NativeTablePane";
import { NativeReviewPanel } from "./NativeReviewPanel";
import "./nativeData.css";

export function NativeDataWorkbench() {
  const [params, setParams] = useSearchParams();
  const selected = params.get("datasetId") ?? "";
  const reviewId = params.get("reviewId");
  const [tables, setTables] = useState<Summary[]>([]);
  const [language, setLanguage] = useState<Language | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [reload, setReload] = useState(0);
  const [creating, setCreating] = useState(false);
  const [rowEditing, setRowEditing] = useState(false);
  const [review, setReview] = useState<Review | null>(null);
  const [applyKey, setApplyKey] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [inspectRetry, setInspectRetry] = useState(0);
  const localReview = useRef<string | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    setLoaded(false);
    void Promise.all([nativeDataApi.list(controller.signal), nativeDataApi.language(controller.signal)]).then(([tables, language]) => {
      if (!controller.signal.aborted) { setTables(tables); setLanguage(language); setLoaded(true); }
    }).catch(error => { if (!controller.signal.aborted) setError(error.message); });
    return () => controller.abort();
  }, [reload]);
  useEffect(() => {
    if (!reviewId) { setReview(null); setUncertain(false); return; }
    if (localReview.current === reviewId) return;
    const controller = new AbortController();
    setReview(null); setApplyKey(null); setError(null);
    void nativeDataApi.inspect(reviewId, controller.signal).then(result => {
      if (controller.signal.aborted) return;
      setReview(result);
      if (result.status === "PREPARED") setApplyKey(typeof result.typedDiff?.idempotencyKey === "string" ? result.typedDiff.idempotencyKey : crypto.randomUUID());
    }).catch(error => { if (!controller.signal.aborted) setError(error.message); });
    return () => controller.abort();
  }, [reviewId, inspectRetry]);
  function remember(result: Review, key: string) {
    localReview.current = result.reviewId;
    setReview(result); setApplyKey(key); setUncertain(false);
    setParams(current => { const next = new URLSearchParams(current); next.set("reviewId", result.reviewId); return next; });
  }
  async function prepareCollection(draft: CollectionDraft) {
    setBusy(true); setError(null);
    try {
      const key = crypto.randomUUID();
      const reserved = await nativeDataApi.reserveCollection(draft.fields.map(field => field.key), key);
      remember(reserved, key);
      const prepared = await nativeDataApi.prepareCollection(reserved.reviewId, collectionDocument(draft, reserved));
      setReview(prepared); setCreating(false);
    } catch (error) { setError((error as Error).message); }
    finally { setBusy(false); }
  }
  async function prepareRow(intent: RowIntent) {
    setBusy(true); setError(null);
    try {
      const key = crypto.randomUUID();
      const reserved = await nativeDataApi.reserveRow(intent.dataset, key);
      remember(reserved, key);
      setReview(await nativeDataApi.prepareRow(intent.dataset.id, reserved.reviewId, mutationDocument(intent.dataset, reserved, key, intent.operation, intent.cells, intent.row)));
    } catch (error) { setError((error as Error).message); throw error; }
    finally { setBusy(false); }
  }
  async function apply() {
    if (!review || !applyKey || busy) return;
    setBusy(true); setUncertain(true); setError(null);
    try { setReview(await nativeDataApi.apply(review, applyKey)); setUncertain(false); setReload(value => value + 1); }
    catch (error) { setError((error as Error).message); }
    finally { setBusy(false); }
  }
  async function inspect() {
    if (!review || busy) return;
    setBusy(true); setError(null);
    try {
      const result = await (["APPLYING", "UNKNOWN"].includes(review.status) ? nativeDataApi.reconcile(review.reviewId) : nativeDataApi.inspect(review.reviewId));
      setReview(result); setUncertain(false);
      if (result.status === "SUCCEEDED") setReload(value => value + 1);
    } catch (error) { setError((error as Error).message); }
    finally { setBusy(false); }
  }
  function close() {
    localReview.current = null; setReview(null); setCreating(false); setApplyKey(null);
    setParams(current => { const next = new URLSearchParams(current); next.delete("reviewId"); return next; });
    setReload(value => value + 1);
  }
  const blocked = busy || Boolean(reviewId);
  const types = publishedCreationTypes(language);
  return <section className="native-workbench form-stack">
    <header className="page-header"><h1>Data tables</h1><p>Define your own tables and review each change before saving.</p></header>
    {error ? <p className="error-message" role="alert">{error}</p> : null}
    {!loaded && !error ? <p role="status">Loading tables…</p> : null}
    <div className="panel action-row"><label>Table<select value={selected} disabled={busy || creating || rowEditing} onChange={event => { setCreating(false); const next = new URLSearchParams(params); next.set("datasetId", event.target.value); setParams(next); }}>
      <option value="">Select table</option>{tables.map(table => <option key={table.id} value={table.id}>{table.label}</option>)}
    </select></label><button type="button" className="primary-button" disabled={!types.includes("TEXT") || blocked || rowEditing || creating} onClick={() => setCreating(true)}>Create table</button></div>
    {loaded && !tables.length ? <p>No tables yet. Create a table to start adding records.</p> : null}
    {review ? <NativeReviewPanel review={review} busy={busy} uncertain={uncertain} onApply={() => void apply()} onInspect={() => void inspect()} onClose={close}/> : null}
    {reviewId && !review && !busy ? <div><p role="status">Review not loaded. Inspect its saved outcome before retrying a change.</p>
      <button type="button" className="ghost-button" onClick={() => setInspectRetry(value => value + 1)}>Load saved review</button>
      <button type="button" className="ghost-button" onClick={close}>Back to tables</button></div> : null}
    {creating && !reviewId ? <CollectionForm busy={busy} types={types} onPrepare={draft => void prepareCollection(draft)} onCancel={() => setCreating(false)}/> : null}
    {selected ? <NativeTablePane key={`${selected}:${reload}`} id={selected} blocked={blocked || creating} onPrepare={prepareRow} onEditingChange={setRowEditing}/> : null}
  </section>;
}
