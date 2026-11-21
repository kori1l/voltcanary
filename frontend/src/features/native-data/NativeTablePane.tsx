import { useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { nativeDataApi, type Dataset, type DatasetCapabilities, type Page, type Row, type RowFilter } from "./nativeDataApi";
import { displayLiteral, editableTypes, type CellDraft } from "./nativeDocuments";
import { RowForm } from "./RowForm";
import { NativeHistoryPane } from "./NativeHistoryPane";
import { NativeImportPane } from "./NativeImportPane";
import { NativeMigrationPane } from "./NativeMigrationPane";
import { NativeSharingPane } from "./NativeSharingPane";
import { NativeTemporalPane } from "./NativeTemporalPane";
import { NativeRowFilter } from "./NativeRowFilter";
import { NativeDashboardDraft } from "./NativeDashboardDraft";

export type RowIntent = { dataset: Dataset; row?: Row; operation: "INSERT" | "UPDATE" | "DELETE"; cells: Record<string, CellDraft> };
export function NativeTablePane({ id, blocked, onPrepare, onEditingChange, onArchive }: { id: string; blocked: boolean; onPrepare: (intent: RowIntent) => Promise<void>; onEditingChange: (editing: boolean) => void; onArchive: () => void }) {
  const [dataset, setDataset] = useState<Dataset | null>(null);
  const [page, setPage] = useState<Page | null>(null);
  const [cursor, setCursor] = useState<string | null>(null);
  const [filter, setFilter] = useState<RowFilter | null>(null);
  const [refresh, setRefresh] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [capabilities, setCapabilities] = useState<DatasetCapabilities | null>(null);
  const [capabilityError, setCapabilityError] = useState<string | null>(null);
  const [editor, setEditor] = useState<{ operation: RowIntent["operation"]; row?: Row } | null>(null);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [importOpen, setImportOpen] = useState(false);
  const [migrationOpen, setMigrationOpen] = useState(false);
  const [sharingOpen, setSharingOpen] = useState(false);
  const [temporalOpen, setTemporalOpen] = useState(false);
  const [dashboardOpen, setDashboardOpen] = useState(false);
  const [params] = useSearchParams();
  const importing = importOpen || params.has("importReview");
  const migrating = migrationOpen || params.has("migrationReview");
  const sharing = sharingOpen || params.has("sharingReview");
  const busyRecords = blocked || importing || migrating || sharing;
  useEffect(() => { onEditingChange(Boolean(editor)); return () => onEditingChange(false); }, [editor, onEditingChange]);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true); setError(null); setPage(null); setEditor(null);
    setCapabilities(null); setCapabilityError(null);
    void nativeDataApi.describe(id, controller.signal).then(async description => {
      if (!description.liveQueryReady) throw new Error("Live reading is unavailable for this table.");
      if (controller.signal.aborted) return;
      setDataset(description);
      const [rows, operations] = await Promise.allSettled([
        nativeDataApi.query(description, cursor, controller.signal, filter),
        nativeDataApi.capabilities(id, controller.signal),
      ]);
      if (controller.signal.aborted) return;
      if (rows.status === "rejected") throw rows.reason;
      const result = rows.value;
      setDataset({ ...description, dataVersion: result.readManifest.dataVersion });
      setPage(result);
      if (operations.status === "rejected") {
        setCapabilityError("Table capabilities are unavailable. Refresh records to retry.");
        return;
      }
      const available = operations.value;
      if (available?.profile !== "dataset-actions-1" || available.datasetId !== description.id
          || available.schemaRevision !== description.schemaRevision || available.generation !== description.generation
          || !["NATIVE", "EXTERNAL", "PROJECTED"].includes(available.sourceMode) || !Array.isArray(available.actions)
          || !available.unavailableReasons || typeof available.unavailableReasons !== "object"
          || Array.isArray(available.unavailableReasons)
          || Object.values(available.unavailableReasons).some(reason => typeof reason !== "string")) {
        setCapabilityError("Table capabilities changed or are unsupported. Refresh records to retry.");
        return;
      }
      setCapabilities(available);
    }).catch(error => { if (!controller.signal.aborted) setError(error.message); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [id, cursor, refresh, filter]);
  const editable = dataset?.definition.fields.every(field => editableTypes.includes(field.type));
  const can = (action: string) => !loading && capabilities?.datasetId === id && capabilities.actions.includes(action);
  const sourceLabels: Record<string, string> = { NATIVE: "Native table", EXTERNAL: "External table", PROJECTED: "Projected table" };
  return <section className="panel native-table" aria-label="Table records">
    <div className="action-row"><h2>{dataset?.definition.label ?? "Records"}</h2>
      <button type="button" className="ghost-button" disabled={loading || busyRecords || Boolean(editor)} onClick={() => { setCursor(null); setRefresh(value => value + 1); }}>Refresh records</button>
      <button type="button" className="primary-button" disabled={!editable || !can("INSERT") || busyRecords || Boolean(editor)} onClick={() => setEditor({ operation: "INSERT" })}>Add row</button>
      <button type="button" className="ghost-button" disabled={!can("IMPORT") || busyRecords || Boolean(editor)} onClick={() => setImportOpen(true)}>Import records</button>
      <button type="button" className="ghost-button" disabled={!can("MIGRATE") || busyRecords || Boolean(editor)} onClick={() => setMigrationOpen(true)}>Change schema</button>
      <button type="button" className="ghost-button" disabled={!can("SHARE") || busyRecords || Boolean(editor)} onClick={() => setSharingOpen(true)}>Manage access</button>
      <button type="button" className="ghost-button" disabled={!can("DASHBOARD") || busyRecords || Boolean(editor)} aria-expanded={dashboardOpen}
        onClick={() => setDashboardOpen(open => !open)}>Dashboard from table</button>
      <button type="button" className="ghost-button" disabled={!can("HISTORY")} aria-expanded={historyOpen} onClick={() => setHistoryOpen(open => !open)}>
        {historyOpen ? "Hide history" : "View history"}
      </button>
      {dataset?.definition.fields.some(field => field.type === "INTERVAL") ? <button type="button" className="ghost-button"
        disabled={!can("TEMPORAL_QUERY")}
        aria-expanded={temporalOpen} onClick={() => setTemporalOpen(open => !open)}>
        {temporalOpen ? "Hide event-time lookup" : "Find by event time"}
      </button> : null}
    </div>
    {loading ? <p role="status">Loading records…</p> : null}
    {error ? <p role="alert">{error}</p> : null}
    {capabilityError ? <p role="status">{capabilityError}</p> : null}
    {capabilities ? <p>{sourceLabels[capabilities.sourceMode]}</p> : null}
    {capabilities && Object.entries(capabilities.unavailableReasons).some(([action]) => action !== "TEMPORAL_QUERY") ? <ul>
      {Object.entries(capabilities.unavailableReasons).filter(([action]) => action !== "TEMPORAL_QUERY")
        .map(([action, reason]) => <li key={action}>{reason}</li>)}
    </ul> : null}
    {dataset && dashboardOpen ? <NativeDashboardDraft key={dataset.schemaRevision} dataset={dataset}/> : null}
    {dataset ? <NativeRowFilter fields={dataset.definition.fields} indexes={dataset.readyIndexedFields} capabilities={dataset.queryCapabilities}
      active={filter} busy={loading || busyRecords || Boolean(editor)} onFilter={next => { setCursor(null); setFilter(next); }}/> : null}
    {dataset && !editable ? <p>These field types can be read here; their row editor is not yet available.</p> : null}
    {dataset && page ? <>
      <div className="native-table-scroll"><table><thead><tr>{dataset.definition.fields.map(field => <th key={field.id} scope="col">{field.label}</th>)}<th scope="col">Actions</th></tr></thead>
        <tbody>{page.rows.map(row => <tr key={row.recordId}>{dataset.definition.fields.map(field => <td key={field.id}>{displayLiteral(row.values[field.id])}</td>)}
          <td><div className="action-row"><button type="button" className="ghost-button" disabled={!can("UPDATE") || busyRecords || !editable || Boolean(editor)} onClick={() => setEditor({ operation: "UPDATE", row })}>Edit</button>
            <button type="button" className="ghost-button" disabled={!can("DELETE") || busyRecords || Boolean(editor)} onClick={() => setEditor({ operation: "DELETE", row })}>Delete</button></div></td>
        </tr>)}</tbody></table></div>
      {!page.rows.length ? <p>No rows on this page.</p> : null}
      <p role="status">{page.rows.length} rows on this page · Total unknown. {page.completeness === "UNKNOWN" ? "Data changed while paging. Refresh before drawing conclusions about missing rows." : page.nextCursor ? "More rows available." : "End of current live traversal."}</p>
      <button type="button" className="ghost-button" disabled={busyRecords || Boolean(editor) || !page.nextCursor} onClick={() => setCursor(page.nextCursor)}>Next page</button>
    </> : null}
    {dataset && editor && !blocked ? <RowForm key={`${editor.operation}:${editor.row?.recordId ?? "new"}`} dataset={dataset} {...editor} busy={blocked} browseOwnedReferences
      onCancel={() => setEditor(null)} onPrepare={cells => { void onPrepare({ dataset, ...editor, cells }).then(() => setEditor(null)).catch(() => {}); }}/>: null}
    {historyOpen ? <NativeHistoryPane key={id} id={id}/> : null}
    {dataset && temporalOpen ? <NativeTemporalPane key={`${id}:${dataset.schemaRevision}`} dataset={dataset}/> : null}
    {dataset && sharing ? <NativeSharingPane dataset={dataset} onClose={() => setSharingOpen(false)}/> : null}
    {migrating ? <NativeMigrationPane id={id} dataset={dataset} onClose={archived => {
      setMigrationOpen(false);
      if (archived) onArchive();
      else { setCursor(null); setRefresh(value => value + 1); }
    }}/> : null}
    {dataset && importing ? <NativeImportPane dataset={dataset} onChanged={() => {
      setImportOpen(false);
      setCursor(null);
      setRefresh(value => value + 1);
    }}/> : null}
  </section>;
}
