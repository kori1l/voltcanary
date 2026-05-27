import { TelemetryOverviewResponse } from "../model/telemetry.types";
import { formatMetricValue } from "../model/telemetry.utils";

export function HealthSummaryCards({ overview }: { overview: TelemetryOverviewResponse }) {
  const problematic = overview.metrics.filter((metric) => !["normal", "no_data"].includes(metric.status));
  const worst = problematic[0] ?? overview.metrics[0];
  const totalSamples = overview.metrics.reduce((sum, metric) => sum + metric.statistics.samplesCount, 0);

  return (
    <div className="telemetry-summary-grid">
      <article>
        <span>Health</span>
        <strong>{overview.health.status.replace("_", " ")}</strong>
        <p>{overview.health.reason}</p>
      </article>
      <article>
        <span>Problem metric</span>
        <strong>{worst ? worst.definition.name : "No data"}</strong>
        <p>{worst ? worst.conclusion : "No telemetry samples in range."}</p>
      </article>
      <article>
        <span>Events</span>
        <strong>{overview.events.length}</strong>
        <p>{overview.events.length > 0 ? "Events are marked on charts and timeline." : "No events in selected range."}</p>
      </article>
      <article>
        <span>Latest value</span>
        <strong>{worst ? formatMetricValue(worst.statistics.latest, worst.definition.unit) : "No data"}</strong>
        <p>{totalSamples.toLocaleString()} samples loaded.</p>
      </article>
    </div>
  );
}
