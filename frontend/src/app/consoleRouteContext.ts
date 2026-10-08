const enumValues: Record<string, readonly string[]> = {
  scope: ["account", "device"], family: ["ANOMALY_DETECTION", "FAILURE_PREDICTION"],
  sort: ["severity", "time"], severity: ["all", "INFO", "WARNING", "HIGH", "CRITICAL"],
  status: ["all", "active", "ACTIVE", "INACTIVE", "ONLINE", "OFFLINE", "DECOMMISSIONED", "OPEN", "ACKNOWLEDGED", "RESOLVED", "SCHEDULED", "FINISHED", "CANCELLED"],
};
const identifiers = new Set(["deviceId", "locationId", "ruleId", "alertId", "operationId", "datasetVersionId", "snapshotId", "runId", "modelId", "policyId", "dataSourceId", "deviceType"]);
const validId = (value: string) => /^[a-zA-Z0-9_-]{1,128}$/.test(value);
const mlTabs = ["settings", "datasets", "import", "labels", "training", "models", "policies", "monitoring"];
const deviceTabs = ["overview", "telemetry", "analytics", "alerts", "maintenance", "configuration", "simulation"];

/** Only navigable task context is serialized; credentials and arbitrary metadata are omitted. */
export function consoleQuery(pathname: string, search: string, allowTransientRange = false): URLSearchParams {
  const input = new URLSearchParams(search);
  const output = new URLSearchParams();
  const keys = new Set(pathname === "/settings/ml" ? ["tab", "scope", "deviceId", "family", "horizonDays", "datasetVersionId", "snapshotId", "runId", "modelId", "policyId", "from", "to", "timezone"] : pathname === "/devices" ? ["q", "locationId", "status", "view", "offset"] : pathname === "/alerts" ? ["view", "deviceId", "severity", "status", "ruleId", "alertId", "metric", "from", "to", "sort", "offset"] : pathname === "/maintenance" ? ["view", "q", "locationId", "deviceType", "status", "from", "to", "includeCancelled", "operationId", "timezone", "offset"] : pathname.startsWith("/devices/") ? ["tab", "metric", "from", "to", "alertId", "ruleId", "setup", "dataSourceId"] : []);
  const statuses = pathname === "/devices" ? ["all", "ACTIVE", "DISABLED", "OFFLINE", "DECOMMISSIONED"] : pathname === "/alerts" ? ["all", "active", "OPEN", "ACKNOWLEDGED", "RESOLVED"] : ["all", "SCHEDULED", "ACTIVE", "FINISHED", "CANCELLED"];
  for (const [key, value] of input) {
    if (!keys.has(key)) continue;
    if (output.has(key)) continue;
    if (identifiers.has(key) && validId(value)) output.set(key, value);
    else if (key === "status" ? statuses.includes(value) : enumValues[key]?.includes(value)) output.set(key, value);
    else if (key === "setup" && value === "activation") output.set(key, value);
    else if (key === "tab" && (pathname === "/settings/ml" ? mlTabs : deviceTabs).includes(value)) output.set(key, value);
    else if (key === "view" && (pathname === "/maintenance" ? ["timeline", "agenda"] : pathname === "/devices" ? ["cards", "table"] : ["inbox", "rules"]).includes(value)) output.set(key, value);
    else if (key === "q" && value.length <= 200 && !/[\u0000-\u001f]/.test(value)) output.set(key, value);
    else if (key === "metric" && /^[a-zA-Z][a-zA-Z0-9_.-]{0,100}$/.test(value)) output.set(key, value);
    else if (["from", "to"].includes(key) && value.length <= 40 && /^\d{4}-\d{2}-\d{2}(?:T[\d:.+-]+Z?)?$/.test(value) && Number.isFinite(Date.parse(value))) output.set(key, value);
    else if (key === "horizonDays" && /^\d+$/.test(value) && Number(value) > 0 && Number(value) <= 365) output.set(key, value);
    else if (key === "offset" && /^\d+$/.test(value) && Number(value) <= 1_000_000) output.set(key, value);
    else if (key === "includeCancelled" && value === "true") output.set(key, value);
    else if (key === "timezone") { try { new Intl.DateTimeFormat(undefined, { timeZone: value }); output.set(key, value); } catch { /* Invalid timezone omitted. */ } }
  }
  if (!allowTransientRange && output.has("from") && output.has("to") && Date.parse(output.get("from")!) >= Date.parse(output.get("to")!)) { output.delete("from"); output.delete("to"); }
  return output;
}

export function safeReturnTo(value: string | null | undefined): string {
  if (!value || !value.startsWith("/") || value.startsWith("//") || /[\\\u0000-\u0020]/.test(value)) return "/devices";
  try {
    const decoded = decodeURIComponent(value);
    if (decoded.startsWith("//") || decoded.includes("\\") || /[\u0000-\u001f]/.test(decoded)) return "/devices";
    const url = new URL(value, "https://console.invalid");
    if (url.origin !== "https://console.invalid" || !/^\/(?:devices(?:\/[a-zA-Z0-9_-]{1,128})?|dashboard|maintenance|alerts|settings(?:\/ml)?|ai-agent(?:\/settings)?|account)?$/.test(url.pathname)) return "/devices";
    const search = consoleQuery(url.pathname, url.search).toString();
    return `${url.pathname}${search ? `?${search}` : ""}`;
  } catch { return "/devices"; }
}

export function crossesWorkflowBoundary(current: { pathname: string; search: string }, next: { pathname: string; search: string }) {
  if (current.pathname !== next.pathname) return true;
  const a = consoleQuery(current.pathname, current.search), b = consoleQuery(next.pathname, next.search);
  return ["tab", "view", "scope", ...(current.pathname === "/maintenance" ? ["from", "to"] : [])].some((key) => a.get(key) !== b.get(key));
}
