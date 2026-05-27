import { MetricThreshold } from "../model/telemetry.types";
import { formatCompactNumber } from "../model/telemetry.utils";

export function ThresholdLegend({ threshold, unit }: { threshold?: MetricThreshold; unit: string }) {
  if (!threshold) {
    return (
      <section className="analysis-panel">
        <h3>Thresholds</h3>
        <p className="muted-text">No threshold rule is configured for this metric yet.</p>
      </section>
    );
  }

  return (
    <section className="analysis-panel">
      <h3>Thresholds</h3>
      <dl className="threshold-list">
        <div>
          <dt>Normal range</dt>
          <dd>{formatRange(threshold.normalMin, threshold.normalMax, unit)}</dd>
        </div>
        <div>
          <dt>Warning</dt>
          <dd>{formatWarning(threshold.warningMin, threshold.warningMax, unit)}</dd>
        </div>
        <div>
          <dt>Critical</dt>
          <dd>{formatWarning(threshold.criticalMin, threshold.criticalMax, unit)}</dd>
        </div>
        {threshold.spikeThreshold !== undefined ? (
          <div>
            <dt>Spike</dt>
            <dd>
              Above {formatCompactNumber(threshold.spikeThreshold)} {unit}
              {threshold.spikeDurationSec ? ` for ${threshold.spikeDurationSec}s` : ""}
            </dd>
          </div>
        ) : null}
      </dl>
      <button className="ghost-button" type="button" disabled>
        Configure alert
      </button>
    </section>
  );
}

function formatRange(min: number | undefined, max: number | undefined, unit: string) {
  if (min !== undefined && max !== undefined) return `${formatCompactNumber(min)}-${formatCompactNumber(max)} ${unit}`;
  if (min !== undefined) return `Above ${formatCompactNumber(min)} ${unit}`;
  if (max !== undefined) return `Below ${formatCompactNumber(max)} ${unit}`;
  return "Not configured";
}

function formatWarning(min: number | undefined, max: number | undefined, unit: string) {
  const parts = [];
  if (min !== undefined) parts.push(`< ${formatCompactNumber(min)} ${unit}`);
  if (max !== undefined) parts.push(`> ${formatCompactNumber(max)} ${unit}`);
  return parts.length > 0 ? parts.join(" or ") : "Not configured";
}
