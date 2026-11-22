import { useEffect, useMemo, useState } from "react";
import { sessionIdentityRevision } from "../../../api/client";
import {
  listWidgetTypes,
  previewDashboardDefinition,
  type DashboardQueryBatch,
} from "../../../dashboards/dashboardQueriesApi";
import type { Breakpoint, DashboardDefinition } from "../../../dashboards/dashboardsApi";
import { registerManifestSizes } from "../model/dashboardLayout";
import { dashboardScreens, screenDefinition } from "../model/dashboardScreens";
import { DashboardCanvas } from "./DashboardCanvas";

/** The reviewed definition is never transformed into a second preview-only dashboard. */
export function DashboardDefinitionPreview({ definition }: { definition: DashboardDefinition }) {
  const encoded = JSON.stringify(definition);
  const stable = useMemo<DashboardDefinition>(() => JSON.parse(encoded), [encoded]);
  const [result, setResult] = useState<{ key: string; batch: DashboardQueryBatch } | null>(null);
  const [failure, setFailure] = useState<{ key: string; message: string } | null>(null);
  const [retry, setRetry] = useState(0);
  const [breakpoint, setBreakpoint] = useState<Breakpoint>("desktop");
  const [selected, setSelected] = useState("");
  useEffect(() => {
    const controller = new AbortController(),
      identity = sessionIdentityRevision();
    setFailure(null);
    setResult(null);
    void listWidgetTypes(controller.signal)
      .then(registerManifestSizes)
      .catch(() => {});
    void previewDashboardDefinition(stable, controller.signal)
      .then((batch) => {
        if (!controller.signal.aborted && identity === sessionIdentityRevision())
          setResult({ key: encoded, batch });
      })
      .catch((error) => {
        if (!controller.signal.aborted && identity === sessionIdentityRevision())
          setFailure({
            key: encoded,
            message: error instanceof Error ? error.message : "Предпросмотр недоступен",
          });
      });
    return () => controller.abort();
  }, [encoded, stable, retry]);
  const screens = dashboardScreens(stable),
    active = screens.find((screen) => screen.id === selected) ?? screens[0];
  const batch = result?.key === encoded ? result.batch : null,
    error = failure?.key === encoded ? failure.message : null;
  return (
    <section className="dashboard-definition-preview" aria-label="Расположение панелей дашборда">
      <header className="dashboard-preview-heading">
        <div>
          <strong>{stable.name}</strong>
          <small>Предпросмотр</small>
        </div>
        <label>
          Размер экрана
          <select
            aria-label="Размер экрана предпросмотра"
            value={breakpoint}
            onChange={(event) => setBreakpoint(event.target.value as Breakpoint)}
          >
            <option value="desktop">Компьютер</option>
            <option value="tablet">Планшет</option>
            <option value="mobile">Телефон</option>
          </select>
        </label>
      </header>
      {screens.length > 1 ? (
        <div className="action-row" aria-label="Экраны дашборда">
          {screens.map((screen) => (
            <button
              type="button"
              key={screen.id}
              aria-pressed={screen.id === active.id}
              onClick={() => setSelected(screen.id)}
            >
              {screen.title}
            </button>
          ))}
        </div>
      ) : null}
      <p className="muted-text" role="status">
        {error
          ? `Данные недоступны: ${error}`
          : batch
            ? `Реальные данные на ${new Date(batch.asOf).toLocaleString()}. Пустые панели означают отсутствие данных.`
            : "Загружаю данные для панелей…"}
      </p>
      <DashboardCanvas
        definition={screenDefinition(stable, active.id)}
        panels={batch?.panels ?? []}
        stale={false}
        previewBreakpoint={breakpoint}
        onRetry={() => setRetry((value) => value + 1)}
      />
      {error ? (
        <button type="button" onClick={() => setRetry((value) => value + 1)}>
          Повторить загрузку данных
        </button>
      ) : null}
    </section>
  );
}
