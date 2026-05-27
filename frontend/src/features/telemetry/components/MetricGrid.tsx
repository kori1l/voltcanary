import { TelemetryEvent, TelemetryMetricOverview } from "../model/telemetry.types";
import { MetricCard } from "./MetricCard";

export function MetricGrid({
  metrics,
  events,
  showPoints,
  maxDisplayPoints,
  onAnalyze,
}: {
  metrics: TelemetryMetricOverview[];
  events: TelemetryEvent[];
  showPoints: boolean;
  maxDisplayPoints: number;
  onAnalyze: (metricKey: string) => void;
}) {
  if (metrics.length === 0) {
    return (
      <div className="empty-state">
        <strong>No charts selected</strong>
        <span>Select at least one metric in chart settings.</span>
      </div>
    );
  }

  return (
    <div className="telemetry-monitor-grid">
      {metrics.map((metric) => (
        <MetricCard
          key={metric.definition.key}
          metric={metric}
          events={events}
          showPoints={showPoints}
          maxDisplayPoints={maxDisplayPoints}
          onAnalyze={() => onAnalyze(metric.definition.key)}
        />
      ))}
    </div>
  );
}
