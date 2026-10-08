import { TelemetryEvent, TelemetryMetricOverview, TelemetryOverviewResponse } from "../model/telemetry.types";
import { formatMetricValue } from "../model/telemetry.utils";
import { StatusBadge } from "./StatusBadge";
import { TelemetryChart } from "./TelemetryChart";

export function MetricCard({
  metric,
  events,
  range,
  showPoints,
  maxDisplayPoints,
  onAnalyze,
}: {
  metric: TelemetryMetricOverview;
  events: TelemetryEvent[];
  range: TelemetryOverviewResponse["range"];
  showPoints: boolean;
  maxDisplayPoints: number;
  onAnalyze: () => void;
}) {
  return (
    <article className={`telemetry-monitor-card telemetry-monitor-card-${metric.status}`}>
      <div className="telemetry-card-head">
        <div>
          <h3>{metric.definition.name}</h3>
          <p>{metric.conclusion}</p>
        </div>
        <StatusBadge status={metric.status} />
      </div>
      <div className="telemetry-card-stats">
        <span>
          Latest in range <strong>{formatMetricValue(metric.statistics.latest, metric.definition.unit)}</strong>
        </span>
        <span>
          Avg <strong>{formatMetricValue(metric.statistics.avg, metric.definition.unit)}</strong>
        </span>
        <span>
          Min <strong>{formatMetricValue(metric.statistics.min, metric.definition.unit)}</strong>
        </span>
        <span>
          Max <strong>{formatMetricValue(metric.statistics.max, metric.definition.unit)}</strong>
        </span>
        <span>
          Evidence events <strong>{metric.statistics.alertsCount}</strong>
        </span>
      </div>
      <TelemetryChart
        evidence={metric.evidence}
        series={metric.series}
        label={metric.definition.name}
        unit={metric.definition.unit}
        range={range}
        threshold={metric.threshold}
        events={events.filter((event) => event.metricKey === metric.definition.key)}
        showPoints={showPoints}
        maxDisplayPoints={maxDisplayPoints}
        mode="compact"
      />
      <div className="telemetry-card-actions">
        <button className="ghost-button" type="button" onClick={onAnalyze}>
          Analyze
        </button>
      </div>
    </article>
  );
}
