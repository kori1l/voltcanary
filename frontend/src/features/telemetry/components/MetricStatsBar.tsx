import { MetricDefinition, MetricStatistics } from "../model/telemetry.types";
import { formatMetricValue } from "../model/telemetry.utils";

export function MetricStatsBar({ metric, statistics }: { metric: MetricDefinition; statistics: MetricStatistics }) {
  return (
    <div className="metric-detail-stats">
      <article>
        <span>Latest</span>
        <strong>{formatMetricValue(statistics.latest, metric.unit)}</strong>
      </article>
      <article>
        <span>Avg</span>
        <strong>{formatMetricValue(statistics.avg, metric.unit)}</strong>
      </article>
      <article>
        <span>Min</span>
        <strong>{formatMetricValue(statistics.min, metric.unit)}</strong>
      </article>
      <article>
        <span>Max</span>
        <strong>{formatMetricValue(statistics.max, metric.unit)}</strong>
      </article>
      <article>
        <span>P95</span>
        <strong>{formatMetricValue(statistics.p95, metric.unit)}</strong>
      </article>
      <article>
        <span>P99</span>
        <strong>{formatMetricValue(statistics.p99, metric.unit)}</strong>
      </article>
    </div>
  );
}
