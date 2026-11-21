import { useEffect, useState } from "react";
import type { DashboardWidget } from "../../../dashboards/dashboardsApi";
import { nativeDataApi, type Dataset, type Summary } from "../../native-data/nativeDataApi";
import { configuredRecordsBinding, configuredRecordsSelection } from "../model/configuredRecordsQuery";

export function ConfiguredRecordsSettings({ widget, onChange }: {
  widget: DashboardWidget;
  onChange: (change: Pick<DashboardWidget, "binding" | "query" | "payload">) => void;
}) {
  const chosen = configuredRecordsSelection(widget.query);
  const [table, setTable] = useState(chosen.table);
  const [tables, setTables] = useState<Summary[] | null>(null);
  const [description, setDescription] = useState<Dataset | null>(null);
  const [catalogError, setCatalogError] = useState<string | null>(null);
  const [tableError, setTableError] = useState<string | null>(null);
  const workspace = widget.binding.workspaceId;

  useEffect(() => {
    if (workspace) return;
    const controller = new AbortController();
    void nativeDataApi.list(controller.signal).then(result => {
      if (!controller.signal.aborted) setTables(result);
    }).catch(failure => { if (!controller.signal.aborted) setCatalogError((failure as Error).message); });
    return () => controller.abort();
  }, [workspace]);

  useEffect(() => {
    if (!table || workspace) return;
    const controller = new AbortController();
    void nativeDataApi.describe(table, controller.signal).then(result => {
      if (!controller.signal.aborted) setDescription(result);
    }).catch(failure => { if (!controller.signal.aborted) setTableError((failure as Error).message); });
    return () => controller.abort();
  }, [table, workspace]);

  if (workspace) return <p role="status">Configured table records require a private dashboard.</p>;
  const current = description?.id === table ? description : null;
  const selected = chosen.table === table && chosen.revision === current?.schemaRevision ? chosen.columns : [];
  return <div className="form-stack">
    {catalogError || tableError ? <p role="alert">{catalogError ?? tableError}</p> : null}
    <label>Owned table<select value={table} disabled={!tables} onChange={event => {
      setTable(event.target.value);
      setTableError(null);
      onChange({ binding: { mode: "DYNAMIC" }, query: {}, payload: {} });
    }}>
      <option value="">Select table</option>
      {table && !tables?.some(item => item.id === table) ? <option value={table} disabled>Current table unavailable</option> : null}
      {tables?.map(item => <option key={item.id} value={item.id}>{item.label}</option>)}
    </select></label>
    {table && !current && !tableError ? <p role="status">Loading table fields…</p> : null}
    {current && !current.liveQueryReady ? <p role="status">Live reading is unavailable for this table.</p> : null}
    {current?.liveQueryReady ? <>
      {chosen.table === table && chosen.revision !== current.schemaRevision ? <p role="status">The table schema changed. Select columns for the current revision.</p> : null}
      <p>Choose up to 16 columns. The panel reads the first 25 rows.</p>
      {current.definition.fields.map(field => <label key={field.id}><input type="checkbox" checked={selected.includes(field.id)}
        disabled={!selected.includes(field.id) && selected.length >= 16} onChange={event => onChange(configuredRecordsBinding(
          current, event.target.checked ? [...selected, field.id] : selected.filter(id => id !== field.id),
        ))}/>{field.label}</label>)}
    </> : null}
  </div>;
}
