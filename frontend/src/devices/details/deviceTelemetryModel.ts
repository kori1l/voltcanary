import { TelemetryChart } from "../../telemetry/telemetryApi";
import { humanizeMetricKey, inferMetricUnit } from "../../features/telemetry/model/telemetry.utils";
import { toDateTimeLocalValue } from "./deviceDetailsFormatting";

export type ChartMetricConfig = { key: string; label: string; unit: string };
export type ChartDensity = "compact" | "balanced" | "detailed";
export type ChartTimeRange = "15m" | "1h" | "6h" | "24h" | "7d" | "custom";

export type DashboardChartPreferences = {
  selectedMetricKeys: string[];
  showChartPoints: boolean;
  chartDensity: ChartDensity;
  chartTimeRange: ChartTimeRange;
  customChartFrom: string;
  customChartTo: string;
  expandedChartMetricKey: string | null;
};


export const CHART_METRICS: ChartMetricConfig[] = [
  { key: "activePowerW", label: "Active power", unit: "W" },
  { key: "voltageV", label: "Voltage", unit: "V" },
  { key: "currentA", label: "Current", unit: "A" },
  { key: "energyWh", label: "Energy", unit: "Wh" },
  { key: "rssi", label: "Signal strength", unit: "dBm" },
];

export const CHART_METRIC_LOOKUP = new Map(CHART_METRICS.map((metric) => [metric.key, metric]));
export const DEFAULT_CHART_METRICS = ["activePowerW", "voltageV", "currentA", "energyWh"];
export const CHART_DENSITY_LIMITS: Record<ChartDensity, number> = {
  compact: 50,
  balanced: 90,
  detailed: 160,
};
export const CHART_TIME_RANGES: { value: ChartTimeRange; label: string; durationMs?: number }[] = [
  { value: "15m", label: "15m", durationMs: 15 * 60 * 1000 },
  { value: "1h", label: "1h", durationMs: 60 * 60 * 1000 },
  { value: "6h", label: "6h", durationMs: 6 * 60 * 60 * 1000 },
  { value: "24h", label: "24h", durationMs: 24 * 60 * 60 * 1000 },
  { value: "7d", label: "7d", durationMs: 7 * 24 * 60 * 60 * 1000 },
  { value: "custom", label: "Custom" },
];

export const DEFAULT_DASHBOARD_CHART_PREFERENCES: DashboardChartPreferences = {
  selectedMetricKeys: DEFAULT_CHART_METRICS,
  showChartPoints: false,
  chartDensity: "balanced",
  chartTimeRange: "24h",
  customChartFrom: toDateTimeLocalValue(new Date(Date.now() - 24 * 60 * 60 * 1000)),
  customChartTo: toDateTimeLocalValue(new Date()),
  expandedChartMetricKey: null,
};

export function buildChartMetrics(readings: Record<string, number>): ChartMetricConfig[] {
  return Object.entries(readings)
    .filter(([, value]) => typeof value === "number" && Number.isFinite(value))
    .map(([key]) => CHART_METRIC_LOOKUP.get(key) ?? createMetricConfig(key))
    .sort((first, second) => metricSortWeight(first.key) - metricSortWeight(second.key) || first.label.localeCompare(second.label));
}

export function buildAvailableChartMetrics(charts: Record<string, TelemetryChart>) {
  return Object.keys(charts)
    .map((key) => CHART_METRIC_LOOKUP.get(key) ?? createMetricConfig(key))
    .filter((metric) => charts[metric.key]?.points.length > 0)
    .sort((first, second) => metricSortWeight(first.key) - metricSortWeight(second.key) || first.label.localeCompare(second.label));
}

export function createMetricConfig(key: string): ChartMetricConfig {
  return {
    key,
    label: humanizeMetricKey(key),
    unit: inferMetricUnit(key),
  };
}

export function metricSortWeight(key: string) {
  const index = DEFAULT_CHART_METRICS.indexOf(key);
  return index >= 0 ? index : DEFAULT_CHART_METRICS.length + 1;
}

export function readDashboardChartPreferences(deviceId: string | undefined): DashboardChartPreferences {
  if (!deviceId) {
    return DEFAULT_DASHBOARD_CHART_PREFERENCES;
  }
  try {
    const raw = window.localStorage.getItem(dashboardChartPreferencesKey(deviceId));
    if (!raw) {
      return DEFAULT_DASHBOARD_CHART_PREFERENCES;
    }
    const parsed = JSON.parse(raw) as Partial<DashboardChartPreferences>;
    return {
      selectedMetricKeys: Array.isArray(parsed.selectedMetricKeys) && parsed.selectedMetricKeys.length > 0
        ? parsed.selectedMetricKeys.filter((key): key is string => typeof key === "string")
        : DEFAULT_DASHBOARD_CHART_PREFERENCES.selectedMetricKeys,
      showChartPoints: typeof parsed.showChartPoints === "boolean" ? parsed.showChartPoints : DEFAULT_DASHBOARD_CHART_PREFERENCES.showChartPoints,
      chartDensity: isChartDensity(parsed.chartDensity) ? parsed.chartDensity : DEFAULT_DASHBOARD_CHART_PREFERENCES.chartDensity,
      chartTimeRange: isChartTimeRange(parsed.chartTimeRange) ? parsed.chartTimeRange : DEFAULT_DASHBOARD_CHART_PREFERENCES.chartTimeRange,
      customChartFrom: typeof parsed.customChartFrom === "string" && parsed.customChartFrom ? parsed.customChartFrom : DEFAULT_DASHBOARD_CHART_PREFERENCES.customChartFrom,
      customChartTo: typeof parsed.customChartTo === "string" && parsed.customChartTo ? parsed.customChartTo : DEFAULT_DASHBOARD_CHART_PREFERENCES.customChartTo,
      expandedChartMetricKey: typeof parsed.expandedChartMetricKey === "string" ? parsed.expandedChartMetricKey : null,
    };
  } catch {
    return DEFAULT_DASHBOARD_CHART_PREFERENCES;
  }
}

export function writeDashboardChartPreferences(deviceId: string, preferences: DashboardChartPreferences) {
  window.localStorage.setItem(dashboardChartPreferencesKey(deviceId), JSON.stringify(preferences));
}

export function getChartTimeWindow(range: ChartTimeRange, customFrom: string, customTo: string) {
  if (range === "custom") {
    const fromDate = new Date(customFrom);
    const toDate = new Date(customTo);
    if (Number.isNaN(fromDate.getTime()) || Number.isNaN(toDate.getTime()) || fromDate >= toDate) {
      return null;
    }
    return { from: fromDate.toISOString(), to: toDate.toISOString() };
  }

  const preset = CHART_TIME_RANGES.find((item) => item.value === range);
  const to = new Date();
  const from = new Date(to.getTime() - (preset?.durationMs ?? 24 * 60 * 60 * 1000));
  return { from: from.toISOString(), to: to.toISOString() };
}

export function arraysEqual(first: string[], second: string[]) {
  return first.length === second.length && first.every((item, index) => item === second[index]);
}

function dashboardChartPreferencesKey(deviceId: string) {
  return `voltcanary.dashboardCharts.${deviceId}`;
}

function isChartDensity(value: unknown): value is ChartDensity {
  return value === "compact" || value === "balanced" || value === "detailed";
}

function isChartTimeRange(value: unknown): value is ChartTimeRange {
  return value === "15m" || value === "1h" || value === "6h" || value === "24h" || value === "7d" || value === "custom";
}
