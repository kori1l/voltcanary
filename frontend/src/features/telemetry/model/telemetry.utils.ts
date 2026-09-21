import { DEFAULT_METRIC_DEFINITIONS } from "./telemetry.constants";
import { MetricDefinition } from "./telemetry.types";

const DEFINITION_BY_KEY = new Map(DEFAULT_METRIC_DEFINITIONS.map((definition) => [definition.key, definition]));

export function getMetricDefinition(key: string): MetricDefinition {
  return DEFINITION_BY_KEY.get(key) ?? {
    id: key,
    key,
    name: humanizeMetricKey(key),
    unit: inferMetricUnit(key),
    kind: inferMetricKind(key),
    defaultVisible: false,
    order: 1000,
  };
}

export function formatMetricValue(value: number | null | undefined, unit: string) {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return "No data";
  }
  return `${formatCompactNumber(value)}${unit ? ` ${unit}` : ""}`;
}

export function formatCompactNumber(value: number) {
  const abs = Math.abs(value);
  const maximumFractionDigits = abs < 10 ? 2 : abs < 100 ? 1 : 0;
  return value.toLocaleString(undefined, { maximumFractionDigits });
}

export function formatShortDateTime(value: string) {
  return new Date(value).toLocaleString([], {
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
  });
}

export function formatShortTime(value: string) {
  return new Date(value).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
}

export function humanizeMetricKey(key: string) {
  return key
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .replace(/[_-]+/g, " ")
    .replace(/\b\w/g, (letter) => letter.toUpperCase());
}

export function inferMetricUnit(key: string) {
  const normalized = key.toLowerCase();
  if (normalized.endsWith("w") || normalized.includes("power")) return "W";
  if (normalized.endsWith("v") || normalized.includes("voltage")) return "V";
  if (normalized.endsWith("a") || normalized.includes("current")) return "A";
  if (normalized.includes("energy") || normalized.endsWith("wh")) return "Wh";
  if (normalized.includes("rssi") || normalized.includes("signal")) return "dBm";
  if (normalized.includes("vibration")) return "mm/s";
  if (normalized.includes("runtime") || normalized.endsWith("hours")) return "h";
  if (normalized.includes("factor")) return "ratio";
  if (normalized.includes("frequency") || normalized.endsWith("hz")) return "Hz";
  if (normalized.includes("percent") || normalized.endsWith("pct")) return "%";
  if (normalized.includes("temperature") || normalized.endsWith("c")) return "C";
  return "";
}

function inferMetricKind(key: string): MetricDefinition["kind"] {
  const normalized = key.toLowerCase();
  if (normalized.includes("energy") || normalized.endsWith("wh") || normalized.includes("runtime")) return "accumulator";
  if (normalized.includes("rssi") || normalized.includes("signal")) return "signal";
  if (normalized.includes("count") || normalized.includes("total")) return "counter";
  return "gauge";
}
