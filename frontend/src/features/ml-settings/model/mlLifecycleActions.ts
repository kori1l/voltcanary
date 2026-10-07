import { MlLifecycleImpact } from "../../../analytics/mlModuleApi";
import type { ConfirmationRequest } from "../../../components/useActionConfirmation";

export function requestLifecycleReason(confirm: (request: ConfirmationRequest) => Promise<string | null>, action: string, label: string, impact?: MlLifecycleImpact) {
  return confirm({ title: `${humanizeKey(action)} ${label}`, object: label, impact: impact?.message ?? `This will ${action} the selected lifecycle record.`, dependencies: impact ? formatImpactDependencies(impact) : undefined, reasonRequired: true });
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
