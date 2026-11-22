import { CircleAlert, CircleCheck, HelpCircle, LoaderCircle } from "lucide-react";
import type { SectionResource } from "../components/useSectionResource";

export function SimulatorStatusIcon({
  resource,
}: {
  resource: Pick<SectionResource<unknown>, "status" | "error" | "refreshing">;
}) {
  const unavailable = ["error", "stale", "no-access"].includes(resource.status);
  const loading = resource.status === "loading" || resource.refreshing;
  const available = ["ready", "empty"].includes(resource.status) && !unavailable;
  const label = unavailable
    ? resource.status === "no-access"
      ? "Simulator access unavailable"
      : "Simulator unavailable"
    : loading
      ? "Checking simulator"
      : available
        ? "Simulator available"
        : "Simulator status not checked";
  const Icon = unavailable
    ? CircleAlert
    : loading
      ? LoaderCircle
      : available
        ? CircleCheck
        : HelpCircle;
  return (
    <span
      className={`simulator-availability ${unavailable ? "is-unavailable" : available ? "is-available" : "is-unknown"}`}
      role="img"
      aria-label={label}
      title={resource.error ? `${label}: ${resource.error}` : label}
    >
      <Icon size={16} aria-hidden="true" />
    </span>
  );
}
