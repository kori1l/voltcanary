import { MlLifecycleImpact } from "../../../analytics/mlModuleApi";

export function requestLifecycleReason(action: string, label: string, impact?: MlLifecycleImpact) {
  const summary = impact ? `\n\nImpact: ${impact.message}${formatImpactDependencies(impact)}` : "";
  if (!window.confirm(`${humanizeKey(action)} ${label}?${summary}`)) {
    return null;
  }
  const reason = window.prompt(`Reason for ${action} ${label}`);
  return reason?.trim() || null;
}

export function formatImpactDependencies(impact: MlLifecycleImpact) {
  const details = impact.dependencies
    .map((dependency) => {
      const values = Object.entries(dependency.details ?? {})
        .filter(([, value]) => value !== null && value !== "" && value !== undefined)
        .map(([key, value]) => `${humanizeKey(key)} ${String(value)}`)
        .join(", ");
      return values ? `${dependency.type} ${values}` : dependency.type;
    })
    .filter(Boolean);
  return details.length === 0 ? "" : ` (${details.join("; ")})`;
}

function humanizeKey(value: string) {
  const text = value.replace(/([a-z])([A-Z])/g, "$1 $2").toLowerCase();
  return text.replace(/^./, (match) => match.toUpperCase());
}
