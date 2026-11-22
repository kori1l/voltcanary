import { useRef, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { sessionIdentityRevision } from "../../api/client";
import { markWorkflowDraftSaved } from "../../components/workflowDraftContext";
import { createDashboard, type DashboardWidget } from "../../dashboards/dashboardsApi";
import { blankDashboard } from "../dashboards/model/dashboardLayout";
import { insertWidget } from "../dashboards/model/dashboardWidgetInsertion";
import type { Dataset } from "./nativeDataApi";
import { configuredRecordsBinding } from "../dashboards/model/configuredRecordsQuery";

export function NativeDashboardDraft({ dataset }: { dataset: Dataset }) {
  const fields = dataset.definition.fields;
  const [selected, setSelected] = useState(fields.slice(0, 16).map((field) => field.id));
  const [name, setName] = useState(dataset.definition.label.slice(0, 80));
  const [dashboardId, setDashboardId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const command = useRef<{ input: string; key: string; widgetId: string } | null>(null);
  const running = useRef(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (running.current || !name.trim() || !selected.length || selected.length > 16 || dashboardId)
      return;
    const configured = configuredRecordsBinding(dataset, selected);
    const input = JSON.stringify({
      name: name.trim(),
      configured,
      title: dataset.definition.label,
    });
    if (command.current?.input !== input)
      command.current = { input, key: crypto.randomUUID(), widgetId: crypto.randomUUID() };
    const { key, widgetId } = command.current;
    const widget: DashboardWidget = {
      id: widgetId,
      type: "configured-records",
      typeVersion: 1,
      title: dataset.definition.label.slice(0, 120),
      ...configured,
      presentation: {},
      navigation: {},
    };
    const identity = sessionIdentityRevision();
    running.current = true;
    setBusy(true);
    setError(null);
    try {
      const dashboard = await createDashboard(
        insertWidget(blankDashboard(name.trim()), widget, "desktop"),
        key,
      );
      if (sessionIdentityRevision() === identity) {
        setDashboardId(dashboard.id);
        markWorkflowDraftSaved("native-table-dashboard");
      }
    } catch (failure) {
      if (sessionIdentityRevision() === identity) setError((failure as Error).message);
    } finally {
      running.current = false;
      setBusy(false);
    }
  }

  return (
    <section className="panel form-stack">
      <h3>Dashboard from table</h3>
      {error ? <p role="alert">{error}</p> : null}
      {dashboardId ? (
        <Link to={`/dashboards/${dashboardId}/edit`}>Open dashboard editor</Link>
      ) : (
        <form data-draft-id="native-table-dashboard" onSubmit={(event) => void submit(event)}>
          <fieldset className="form-stack" disabled={busy}>
            <legend>Configured records</legend>
            <label>
              Dashboard name
              <input
                required
                maxLength={80}
                value={name}
                onChange={(event) => setName(event.target.value)}
              />
            </label>
            <p>Choose up to 16 fields. The panel reads the first 25 rows.</p>
            {fields.map((field) => (
              <label key={field.id}>
                <input
                  type="checkbox"
                  checked={selected.includes(field.id)}
                  disabled={!selected.includes(field.id) && selected.length >= 16}
                  onChange={(event) =>
                    setSelected((current) =>
                      event.target.checked
                        ? [...current, field.id]
                        : current.filter((id) => id !== field.id),
                    )
                  }
                />
                {field.label}
              </label>
            ))}
            <button type="submit" className="primary-button" disabled={!selected.length}>
              Create dashboard
            </button>
          </fieldset>
        </form>
      )}
    </section>
  );
}
