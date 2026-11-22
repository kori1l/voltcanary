import { MlLifecycleStatusFilter, MlModelVersion } from "../../../analytics/mlModuleApi";

export const lifecycleStatusFilters: MlLifecycleStatusFilter[] = [
  "default",
  "active",
  "failed",
  "archived",
  "deleted",
  "all",
];

export function StatusFilterSelect(props: {
  label: string;
  value: MlLifecycleStatusFilter;
  onChange: (value: MlLifecycleStatusFilter) => Promise<void>;
}) {
  return (
    <label className="compact-select">
      {props.label}
      <select
        value={props.value}
        onChange={(event) => void props.onChange(event.target.value as MlLifecycleStatusFilter)}
      >
        {lifecycleStatusFilters.map((value) => (
          <option key={value} value={value}>
            {humanizeKey(value)}
          </option>
        ))}
      </select>
    </label>
  );
}

export function modelOriginLabel(model: MlModelVersion) {
  if (model.demoModel || model.modelOrigin === "DEMO") {
    return "Demo";
  }
  if (model.systemHeuristic || model.modelOrigin === "SYSTEM_HEURISTIC") {
    return "System heuristic";
  }
  return "Trained";
}

export function humanizeKey(value: string) {
  const text = value.replace(/([a-z])([A-Z])/g, "$1 $2").toLowerCase();
  return text.replace(/^./, (match) => match.toUpperCase());
}

export function formatQualityValue(value: unknown) {
  if (value === null || value === undefined) {
    return "N/A";
  }
  if (typeof value === "object") {
    return JSON.stringify(value);
  }
  return String(value);
}

export function metricNumber(metrics: Record<string, unknown>, key: string) {
  const value = metrics[key];
  if (typeof value === "number" && Number.isFinite(value)) {
    return value;
  }
  if (typeof value === "string" && value.trim()) {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
}

export function formatPercentMetric(value: number | null) {
  if (value === null) {
    return "N/A";
  }
  return `${Math.round(value * 100)}%`;
}

export function formatDecimalMetric(value: number | null) {
  if (value === null) {
    return "N/A";
  }
  return value.toLocaleString(undefined, {
    maximumFractionDigits: 2,
    minimumFractionDigits: value < 10 ? 1 : 0,
  });
}

export function formatSignedMetric(value: number | null) {
  if (value === null) {
    return "N/A";
  }
  const sign = value > 0 ? "+" : "";
  return `${sign}${formatDecimalMetric(value)}`;
}

export function stringMetric(metrics: Record<string, unknown>, key: string) {
  const value = metrics[key];
  return typeof value === "string" && value.trim() ? value : null;
}

export function humanizeFeatureName(value: string) {
  return value
    .replace(/[_-]+/g, " ")
    .replace(/([a-z])([A-Z])/g, "$1 $2")
    .split(/\s+/)
    .filter(Boolean)
    .map((part) =>
      part.length <= 2 ? part.toUpperCase() : part.replace(/^./, (match) => match.toUpperCase()),
    )
    .join(" ");
}
