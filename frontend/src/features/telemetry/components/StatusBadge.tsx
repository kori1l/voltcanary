import { MetricStatus } from "../model/telemetry.types";

export function StatusBadge({ status }: { status: MetricStatus }) {
  return <span className={`telemetry-status telemetry-status-${status}`}>{status.replace("_", " ")}</span>;
}
