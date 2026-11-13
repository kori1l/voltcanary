import { useEffect, useState } from "react";
import { nativeDataApi, type Dataset, type Page, type Row } from "./nativeDataApi";
import { displayLiteral, editableTypes, type CellDraft } from "./nativeDocuments";
import { RowForm } from "./RowForm";

export type RowIntent = { dataset: Dataset; row?: Row; operation: "INSERT" | "UPDATE" | "DELETE"; cells: Record<string, CellDraft> };
export function NativeTablePane({ id, blocked, onPrepare, onEditingChange }: { id: string; blocked: boolean; onPrepare: (intent: RowIntent) => Promise<void>; onEditingChange: (editing: boolean) => void }) {
  const [dataset, setDataset] = useState<Dataset | null>(null);
  const [page, setPage] = useState<Page | null>(null);
  const [cursor, setCursor] = useState<string | null>(null);
  const [refresh, setRefresh] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [editor, setEditor] = useState<{ operation: RowIntent["operation"]; row?: Row } | null>(null);
  useEffect(() => { onEditingChange(Boolean(editor)); return () => onEditingChange(false); }, [editor, onEditingChange]);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true); setError(null); setPage(null); setDataset(null); setEditor(null);
    void nativeDataApi.describe(id, controller.signal).then(async description => {
      if (!description.liveQueryReady) throw new Error("Live reading is unavailable for this table.");
      const result = await nativeDataApi.query(description, cursor, controller.signal);
      if (controller.signal.aborted) return;
      setDataset({ ...description, dataVersion: result.readManifest.dataVersion });
      setPage(result);
    }).catch(error => { if (!controller.signal.aborted) setError(error.message); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [id, cursor, refresh]);
  const editable = dataset?.definition.fields.every(field => editableTypes.includes(field.type));
  return <section className="panel native-table" aria-label="Table records">
    <div className="action-row"><h2>{dataset?.definition.label ?? "Records"}</h2>
      <button type="button" className="ghost-button" disabled={loading || blocked || Boolean(editor)} onClick={() => { setCursor(null); setRefresh(value => value + 1); }}>Refresh records</button>
      <button type="button" className="primary-button" disabled={!editable || loading || blocked || Boolean(editor)} onClick={() => setEditor({ operation: "INSERT" })}>Add row</button>
    </div>
    {loading ? <p role="status">Loading records…</p> : null}
    {error ? <p role="alert">{error}</p> : null}
    {dataset && !editable ? <p>These field types can be read here; their row editor is not yet available.</p> : null}
    {dataset && page ? <>
      <div className="native-table-scroll"><table><thead><tr>{dataset.definition.fields.map(field => <th key={field.id} scope="col">{field.label}</th>)}<th scope="col">Actions</th></tr></thead>
        <tbody>{page.rows.map(row => <tr key={row.recordId}>{dataset.definition.fields.map(field => <td key={field.id}>{displayLiteral(row.values[field.id])}</td>)}
          <td><div className="action-row"><button type="button" className="ghost-button" disabled={blocked || !editable || Boolean(editor)} onClick={() => setEditor({ operation: "UPDATE", row })}>Edit</button>
            <button type="button" className="ghost-button" disabled={blocked || Boolean(editor)} onClick={() => setEditor({ operation: "DELETE", row })}>Delete</button></div></td>
        </tr>)}</tbody></table></div>
      {!page.rows.length ? <p>No rows on this page.</p> : null}
      <p role="status">{page.rows.length} rows on this page · Total unknown. {page.completeness === "UNKNOWN" ? "Data changed while paging. Refresh before drawing conclusions about missing rows." : page.nextCursor ? "More rows available." : "End of current live traversal."}</p>
      <button type="button" className="ghost-button" disabled={blocked || Boolean(editor) || !page.nextCursor} onClick={() => setCursor(page.nextCursor)}>Next page</button>
    </> : null}
    {dataset && editor && !blocked ? <RowForm key={`${editor.operation}:${editor.row?.recordId ?? "new"}`} dataset={dataset} {...editor} busy={blocked}
      onCancel={() => setEditor(null)} onPrepare={cells => { void onPrepare({ dataset, ...editor, cells }).then(() => setEditor(null)).catch(() => {}); }}/>: null}
  </section>;
}
