import { consoleQuery, safeReturnTo } from "../app/consoleRouteContext";
import type { TaskEvidence } from "./taskEvidence";
export function buildAssistantContext(pathname: string, search: string, evidence: TaskEvidence = {}) {
  const params = consoleQuery(pathname, search);
  const searchParams = Object.fromEntries(params);
  for (const key of ["from", "to"] as const) if (evidence.interval?.[key] && Number.isFinite(Date.parse(evidence.interval[key]!))) searchParams[key] = evidence.interval[key]!;
  const deviceMatch = pathname.match(/^\/devices\/([^/]+)/);
  const deviceId = deviceMatch && /^[a-zA-Z0-9_-]{1,128}$/.test(deviceMatch[1]) ? deviceMatch[1] : params.get("deviceId") ?? undefined;
  const page = deviceMatch ? "Device details" : pathname === "/settings/ml" ? "ML workspace" : pathname === "/maintenance" ? "Maintenance" : pathname.startsWith("/alerts") ? "Alerts" : pathname.startsWith("/devices") ? "Devices" : pathname.startsWith("/settings") ? "Settings" : "Console";
  const names = Object.fromEntries(["device", "dataset", "operation", "incident", "location"].flatMap((key) => { const name = evidence.names?.[key as keyof NonNullable<TaskEvidence["names"]>]; return typeof name === "string" && name.length <= 200 ? [[key, name]] : []; }));
  return { page, pageLabel: page, route: safeReturnTo(pathname), searchParams, deviceId, names };
}
export function inspectAssistantContext(text: string): Record<string, unknown> {
  const value: unknown = text.trim() ? JSON.parse(text) : {};
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("Context must be a JSON object");
  function check(item: unknown) {
    if (!item || typeof item !== "object") return;
    for (const [key, value] of Object.entries(item)) { if (/password|secret|api.?key|authorization|credential|token$/i.test(key)) throw new Error("Remove credentials from assistant context"); check(value); }
  }
  check(value); return value as Record<string, unknown>;
}
export function redactAssistantDetails(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(redactAssistantDetails);
  if (!value || typeof value !== "object") return value;
  return Object.fromEntries(Object.entries(value).map(([key,value]) => [key, /password|secret|api.?key|authorization|credential|token$/i.test(key) ? "[redacted]" : redactAssistantDetails(value)]));
}
