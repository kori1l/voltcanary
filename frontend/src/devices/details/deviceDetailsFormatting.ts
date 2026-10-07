import { Location } from "../devicesApi";

export function formatEnum(value: string) {
  return value
    .toLowerCase()
    .split(/[_\s-]+/)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

export function formatNumber(value: number) {
  return Number.isInteger(value) ? value.toLocaleString() : value.toLocaleString(undefined, { maximumFractionDigits: 2 });
}

export function formatCompactNumber(value: number) {
  const abs = Math.abs(value);
  const maximumFractionDigits = abs < 10 ? 2 : abs < 100 ? 1 : 0;
  return value.toLocaleString(undefined, { maximumFractionDigits });
}

export function formatMetricValue(value: unknown, unit: string) {
  return typeof value === "number" ? `${formatCompactNumber(value)} ${unit}` : "No data";
}

export function formatShortTime(value: string) {
  return new Date(value).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
}

export function formatShortDateTime(value: string) {
  return new Date(value).toLocaleString([], {
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
  });
}

export function toDateTimeLocalValue(date: Date) {
  const offsetMs = date.getTimezoneOffset() * 60 * 1000;
  return new Date(date.getTime() - offsetMs).toISOString().slice(0, 19);
}

export function locationLabel(location: Location) {
  const hierarchy = [location.building, location.floor ? `Floor ${location.floor}` : null, location.room, location.zone]
    .filter(Boolean)
    .join(" / ");
  return hierarchy ? `${location.name} - ${hierarchy}` : location.name;
}

export function parseJsonObject(value: string) {
  const trimmed = value.trim();
  if (!trimmed || trimmed === "{}") {
    return null;
  }
  const parsed: unknown = JSON.parse(trimmed);
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error("JSON configuration must be an object.");
  return parsed as Record<string, unknown>;
}

export function downloadText(text: string, filename: string, type: string) {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}

export function delay(milliseconds: number) {
  return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
}
