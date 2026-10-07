export function buildAssistantContext(pathname: string, search: string) {
  const params = new URLSearchParams(search);
  const searchParams = Object.fromEntries(["tab", "metric", "from", "to", "alertId", "ruleId"].flatMap((key) => params.get(key) ? [[key, params.get(key)!]] : []));
  const deviceMatch = pathname.match(/^\/devices\/([^/]+)/);
  const deviceId = deviceMatch ? decodeURIComponent(deviceMatch[1]) : params.get("deviceId") ?? undefined;
  const page = deviceMatch ? "Device details" : pathname.startsWith("/alerts") ? "Alerts" : pathname.startsWith("/devices") ? "Devices" : pathname.startsWith("/settings") ? "Settings" : "Console";
  return { page, pageLabel: page, route: pathname, searchParams, deviceId };
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
