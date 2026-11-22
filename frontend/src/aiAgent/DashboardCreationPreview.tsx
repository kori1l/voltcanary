import type { AgentAction } from "./aiAgentApi";
import type { DashboardDefinition } from "../dashboards/dashboardsApi";
import { lazy, Suspense } from "react";
const DashboardDefinitionPreview = lazy(() =>
  import("../features/dashboards/components/DashboardDefinitionPreview").then((module) => ({
    default: module.DashboardDefinitionPreview,
  })),
);

function object(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}

export function DashboardCreationPreview({
  action,
  compact = false,
}: {
  action: AgentAction;
  compact?: boolean;
}) {
  const parameters = action.parameters;
  const definition = object(parameters.definition);
  const time = object(object(definition.defaultContext).time);
  const names = Array.isArray(parameters.deviceNames)
    ? parameters.deviceNames.filter((name): name is string => typeof name === "string")
    : [];
  const panels = Array.isArray(definition.widgets) ? definition.widgets.map(object) : [];
  const renderable =
    definition.schemaVersion === 1 &&
    Array.isArray(definition.sections) &&
    definition.layouts &&
    panels.every((panel) => typeof panel.type === "string" && typeof panel.id === "string");
  const periods: Record<string, string> = {
    LAST_1_HOUR: "Последний час",
    LAST_6_HOURS: "Последние 6 часов",
    LAST_24_HOURS: "Последние 24 часа",
    LAST_7_DAYS: "Последние 7 дней",
    LAST_30_DAYS: "Последние 30 дней",
  };
  return (
    <section aria-label="Dashboard draft preview">
      {compact ? (
        <p className="assistant-preview-context">
          {names.join(", ")} · {periods[String(time.preset)] ?? "Выбранный период"} · Личный
          черновик
        </p>
      ) : (
        <dl>
          <dt>Имя</dt>
          <dd>{String(definition.name ?? "Не указано")}</dd>
          {parameters.templateId ? (
            <>
              <dt>Шаблон</dt>
              <dd>
                {String(parameters.templateName ?? parameters.templateId)} · v
                {String(parameters.templateVersion)}
              </dd>
            </>
          ) : null}
          <dt>Устройства</dt>
          <dd>{names.join(", ")}</dd>
          <dt>Период</dt>
          <dd>{periods[String(time.preset)] ?? "Выбранный период"}</dd>
          <dt>Доступ</dt>
          <dd>Личный черновик</dd>
        </dl>
      )}
      {renderable ? (
        <Suspense fallback={<p role="status">Открываю предпросмотр…</p>}>
          <DashboardDefinitionPreview definition={definition as unknown as DashboardDefinition} />
        </Suspense>
      ) : (
        <>
          <p>Панели:</p>
          <ul>
            {panels.map((panel) => (
              <li key={String(panel.id)}>{String(panel.title)}</li>
            ))}
          </ul>
        </>
      )}
      {!compact ? (
        <p>
          Без публикации, общего доступа или изменения дашборда по умолчанию. Отсутствующие
          наблюдения останутся без данных.
        </p>
      ) : null}
    </section>
  );
}
