import { useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { listWorkspaces, type Workspace } from "../workspaces/workspacesApi";
import { nativeDataApi, type Dataset, type Page, type Review, type Row, type RowFilter, type SharedDescription, type SharedTable } from "./nativeDataApi";
import { displayLiteral, editableTypes, mutationDocument, type CellDraft } from "./nativeDocuments";
import { RowForm } from "./RowForm";
import { NativeReviewPanel } from "./NativeReviewPanel";
import { NativeHistoryPane } from "./NativeHistoryPane";
import { NativeTemporalPane } from "./NativeTemporalPane";
import { NativeRowFilter } from "./NativeRowFilter";

const reviewParams = ["sharedReview", "sharedWorkspace", "sharedAccount", "sharedDataset", "sharedGrant"] as const;

export function NativeSharedPane() {
  const [params, setParams] = useSearchParams();
  const [savedReview, savedWorkspace, savedAccount, savedDataset, savedGrant] = reviewParams.map(key => params.get(key));
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [workspace, setWorkspace] = useState("");
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    void listWorkspaces(controller.signal).then(result => {
      if (!controller.signal.aborted) setWorkspaces(result);
    }).catch((failure: Error) => { if (!controller.signal.aborted) setError(failure.message); });
    return () => controller.abort();
  }, []);
  function closeSaved() {
    setParams(current => {
      const next = new URLSearchParams(current);
      reviewParams.forEach(key => next.delete(key));
      return next;
    }, { replace: true });
  }
  return <section className="panel form-stack" aria-label="Shared tables">
    <h2>Shared tables</h2>
    {error ? <p role="alert">{error}</p> : null}
    {savedReview ? savedWorkspace && savedAccount && savedDataset && savedGrant
      ? <SavedSharedReview key={[savedReview, savedWorkspace, savedAccount, savedDataset, savedGrant].join(":")} workspace={savedWorkspace} account={savedAccount} dataset={savedDataset}
        grant={savedGrant} reviewId={savedReview} onClose={closeSaved}/>
      : <><p role="alert">The saved review link is incomplete.</p><button type="button" onClick={closeSaved}>Back to shared tables</button></>
      : <>
    <label>Workspace<select value={workspace} onChange={event => setWorkspace(event.target.value)}>
      <option value="">Select workspace</option>
      {workspaces.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
    </select></label>
    {workspace ? <SharedWorkspace key={workspace} workspace={workspace}/> : null}
    </>}
  </section>;
}

function SavedSharedReview({ workspace, account, dataset, grant, reviewId, onClose }: {
  workspace: string; account: string; dataset: string; grant: string; reviewId: string; onClose: () => void;
}) {
  const [saved, setSaved] = useState<{ review: Review; dataset: Dataset } | null>(null);
  const [busy, setBusy] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [reload, setReload] = useState(0);
  const table = { accountId: account, datasetId: dataset, grantId: grant, label: "" };
  useEffect(() => {
    const controller = new AbortController();
    const context = { accountId: account, datasetId: dataset, grantId: grant, label: "" };
    setError(null);
    setBusy(true);
    setSaved(null);
    void Promise.all([
      nativeDataApi.describeShared(workspace, context, controller.signal),
      nativeDataApi.inspectSharedRow(workspace, context, reviewId, controller.signal),
    ]).then(([description, review]) => {
      if (controller.signal.aborted) return;
      setSaved({ review, dataset: {
        id: dataset, generation: description.generation, dataVersion: description.dataVersion, schemaRevision: description.schemaRevision,
        liveQueryReady: true, definition: { label: description.label, fields: description.fields,
          keyFieldIds: description.keyFieldIds ?? [], systemFields: { recordIdFieldId: description.recordIdFieldId } },
      } });
      setUncertain(false);
    }).catch((failure: Error) => { if (!controller.signal.aborted) setError(failure.message); })
      .finally(() => { if (!controller.signal.aborted) setBusy(false); });
    return () => controller.abort();
  }, [workspace, account, dataset, grant, reviewId, reload]);

  async function apply() {
    const key = saved?.review.typedDiff?.idempotencyKey;
    if (!saved || busy || typeof key !== "string") return;
    setBusy(true);
    setUncertain(true);
    setError(null);
    try {
      const review = await nativeDataApi.applySharedRow(workspace, table, saved.review, key);
      setSaved({ ...saved, review });
      setUncertain(false);
    } catch (failure) {
      setError((failure as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return <>
    {error ? <p role="alert">{error}</p> : null}
    {saved ? saved.review.typedDiff?.operation ? <NativeReviewPanel review={saved.review} scopedDataset={saved.dataset} busy={busy} uncertain={uncertain}
      onApply={() => void apply()} onInspect={() => setReload(value => value + 1)} onClose={onClose}/> : <>
        <p role="status">Review status {saved.review.status}. The row change has no prepared document.</p>
        <button type="button" className="ghost-button" onClick={() => setReload(value => value + 1)}>Check saved outcome</button>
        <button type="button" className="ghost-button" onClick={onClose}>Back to shared tables</button>
      </>
      : busy ? <p role="status">Loading saved shared review…</p> : null}
    {error || !saved && !busy ? <div className="action-row">
      <button type="button" className="ghost-button" disabled={busy} onClick={() => setReload(value => value + 1)}>Retry inspection</button>
      <button type="button" className="ghost-button" disabled={busy} onClick={onClose}>Back to shared tables</button>
    </div> : null}
  </>;
}

function SharedWorkspace({ workspace }: { workspace: string }) {
  const [tables, setTables] = useState<SharedTable[]>([]);
  const [selected, setSelected] = useState("");
  const [after, setAfter] = useState<string | null>(null);
  const [next, setNext] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    void nativeDataApi.sharedTables(workspace, after, controller.signal).then(result => {
      if (controller.signal.aborted) return;
      setTables(current => after ? [...current, ...result.items] : result.items);
      setNext(result.nextAfterGrant);
    }).catch((failure: Error) => { if (!controller.signal.aborted) setError(failure.message); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [workspace, after]);
  const table = tables.find(item => item.grantId === selected);
  return <>
    {error ? <p role="alert">{error}</p> : null}
    {loading ? <p role="status">Loading shared tables…</p> : null}
    {!loading && !error && !tables.length ? <p>No shared tables in this workspace.</p> : null}
    {tables.length ? <label>Table<select value={selected} onChange={event => setSelected(event.target.value)}>
      <option value="">Select shared table</option>
      {tables.map(item => <option key={item.grantId} value={item.grantId}>{item.label}</option>)}
    </select></label> : null}
    {next ? <button type="button" className="ghost-button" disabled={loading || next === after} onClick={() => setAfter(next)}>Load more tables</button> : null}
    {table ? <SharedRecords key={table.grantId} workspace={workspace} table={table}/> : null}
  </>;
}

function SharedRecords({ workspace, table }: { workspace: string; table: SharedTable }) {
  const [, setParams] = useSearchParams();
  const shared = useMemo(() => ({ workspace, table }), [workspace, table]);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [temporalOpen, setTemporalOpen] = useState(false);
  const [result, setResult] = useState<{ description: SharedDescription; page: Page } | null>(null);
  const [filterDescription, setFilterDescription] = useState<SharedDescription | null>(null);
  const [filter, setFilter] = useState<RowFilter | null>(null);
  const [cursor, setCursor] = useState<string | null>(null);
  const [reload, setReload] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [editor, setEditor] = useState<{ operation: "INSERT" | "UPDATE" | "DELETE"; row?: Row } | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    const controller = new AbortController();
    setResult(null);
    setError(null);
    void nativeDataApi.describeShared(workspace, table, controller.signal).then(async description => {
      if (controller.signal.aborted) return;
      setFilterDescription(description);
      const page = await nativeDataApi.queryShared(workspace, table, description, cursor, controller.signal, filter);
      if (!controller.signal.aborted) setResult({ description, page });
    }).catch((failure: Error) => {
      if (controller.signal.aborted) return;
      setError(failure.message);
      setFilterDescription(null);
    });
    return () => controller.abort();
  }, [workspace, table, cursor, reload, filter]);

  const description = result?.description;
  const actions = description?.keysVisible ? description.policy.actions : [];
  const keys = description?.keyFieldIds ?? [];
  const writable = description?.fields.filter(field => description.policy.writeFieldIds.includes(field.id)) ?? [];
  const insertable = actions.includes("INSERT") && writable.length > 0 && writable.every(field => editableTypes.includes(field.type));
  const updatable = actions.includes("UPDATE") && writable.some(field => !keys.includes(field.id) && editableTypes.includes(field.type));
  const deletable = actions.includes("DELETE");
  const dataset: Dataset | null = description && result ? {
    id: table.datasetId, generation: description.generation, dataVersion: result.page.readManifest.dataVersion,
    schemaRevision: description.schemaRevision, liveQueryReady: true,
    definition: { label: description.label, fields: description.fields, keyFieldIds: keys,
      systemFields: { recordIdFieldId: description.recordIdFieldId } },
  } : null;
  const editorDataset = dataset && editor ? {
    ...dataset, definition: { ...dataset.definition, fields: dataset.definition.fields.filter(field =>
      keys.includes(field.id) || (editor.operation !== "DELETE" && description?.policy.writeFieldIds.includes(field.id)
        && editableTypes.includes(field.type))) },
  } : null;

  async function prepare(cells: Record<string, CellDraft>) {
    if (!editor || !editorDataset || busy) return;
    setBusy(true);
    setError(null);
    let reservationId: string | null = null;
    try {
      const key = crypto.randomUUID();
      const reserved = await nativeDataApi.reserveSharedRow(workspace, table, editorDataset.schemaRevision, key);
      reservationId = reserved.reviewId;
      const document = mutationDocument(editorDataset, reserved, key, editor.operation, cells, editor.row);
      await nativeDataApi.prepareSharedRow(workspace, table, reserved.reviewId, document);
    } catch (failure) {
      setError((failure as Error).message);
    } finally {
      setBusy(false);
      if (reservationId) {
        const reviewId = reservationId;
        setParams(current => {
          const next = new URLSearchParams(current);
          const selectors = [reviewId, workspace, table.accountId, table.datasetId, table.grantId];
          reviewParams.forEach((name, index) => next.set(name, selectors[index]));
          return next;
        }, { replace: true });
      }
    }
  }

  return <>
    <div className="action-row">
      <button type="button" className="ghost-button" disabled={Boolean(editor)} onClick={() => { setCursor(null); setReload(value => value + 1); }}>Refresh shared records</button>
      {insertable ? <button type="button" className="primary-button" disabled={Boolean(editor)} onClick={() => setEditor({ operation: "INSERT" })}>Add shared row</button> : null}
      {description?.policy.actions.includes("HISTORY") ? <button type="button" className="ghost-button"
        aria-expanded={historyOpen} onClick={() => setHistoryOpen(open => !open)}>Table history</button> : null}
      {description?.fields.some(field => field.type === "INTERVAL") ? <button type="button" className="ghost-button"
        aria-expanded={temporalOpen} onClick={() => setTemporalOpen(open => !open)}>Find by event time</button> : null}
    </div>
    {error ? <p role="alert">{error}</p> : null}
    {filterDescription || filter ? <NativeRowFilter fields={filterDescription?.fields ?? []} indexes={filterDescription?.readyIndexedFields}
      capabilities={filterDescription?.queryCapabilities} active={filter} busy={Boolean(editor) || (!result && !error)}
      onFilter={next => { setCursor(null); setFilter(next); }}/> : null}
    {!result && !error ? <p role="status">Loading shared records…</p> : null}
    {result ? <>
      {result.page.rows.length ? <div className="native-table-scroll"><table>
        <thead><tr>{result.description.fields.map(field => <th key={field.id} scope="col">{field.label}</th>)}
          {updatable || deletable ? <th scope="col">Actions</th> : null}</tr></thead>
        <tbody>{result.page.rows.map(row => <tr key={row.recordId}>{result.description.fields.map(field =>
          <td key={field.id}>{displayLiteral(row.values[field.id])}</td>)}
          {updatable || deletable ? <td className="action-row">
            {updatable ? <button type="button" className="ghost-button" disabled={Boolean(editor)} onClick={() => setEditor({ operation: "UPDATE", row })}>Edit</button> : null}
            {deletable ? <button type="button" className="ghost-button" disabled={Boolean(editor)} onClick={() => setEditor({ operation: "DELETE", row })}>Delete</button> : null}
          </td> : null}</tr>)}</tbody>
      </table></div> : <p>No shared records on this page.</p>}
      {result.page.completeness === "UNKNOWN" ? <p role="status">Records changed while paging. Refresh to start again.</p> : null}
      {result.page.nextCursor ? <button type="button" className="ghost-button" disabled={Boolean(editor)} onClick={() => setCursor(result.page.nextCursor)}>Next shared page</button> : null}
    </> : null}
    {editor && editorDataset ? <RowForm dataset={editorDataset} {...editor} busy={busy}
      onPrepare={cells => void prepare(cells)} onCancel={() => setEditor(null)}/> : null}
    {historyOpen && description?.policy.actions.includes("HISTORY") ? <NativeHistoryPane id={table.datasetId} shared={shared}/> : null}
    {temporalOpen && dataset ? <NativeTemporalPane dataset={dataset} shared={shared}/> : null}
  </>;
}
