import { MetricDefinition } from "../model/telemetry.types";
import { MetricSelector } from "./MetricSelector";

export type TelemetryToolbarRange = "15m" | "1h" | "6h" | "24h" | "7d" | "custom";
export type TelemetryToolbarDensity = "compact" | "balanced" | "detailed";

export function TelemetryToolbar({
  range,
  customFrom,
  customTo,
  density,
  showPoints,
  metrics,
  selectedMetricKeys,
  metricSearch,
  onRangeChange,
  onCustomFromChange,
  onCustomToChange,
  onDensityChange,
  onShowPointsChange,
  onMetricSearchChange,
  onToggleMetric,
  onSelectAll,
  onReset,
  onClear,
}: {
  range: TelemetryToolbarRange;
  customFrom: string;
  customTo: string;
  density: TelemetryToolbarDensity;
  showPoints: boolean;
  metrics: MetricDefinition[];
  selectedMetricKeys: string[];
  metricSearch: string;
  onRangeChange: (range: TelemetryToolbarRange) => void;
  onCustomFromChange: (value: string) => void;
  onCustomToChange: (value: string) => void;
  onDensityChange: (density: TelemetryToolbarDensity) => void;
  onShowPointsChange: (value: boolean) => void;
  onMetricSearchChange: (value: string) => void;
  onToggleMetric: (metricKey: string) => void;
  onSelectAll: () => void;
  onReset: () => void;
  onClear: () => void;
}) {
  return (
    <div className="telemetry-toolbar">
      <div className="chart-time-controls">
        <div className="chart-range-buttons" aria-label="Chart time range">
          {(["15m", "1h", "6h", "24h", "7d", "custom"] as const).map((item) => (
            <button
              className={`chart-range-button${range === item ? " chart-range-button-active" : ""}`}
              type="button"
              key={item}
              onClick={() => onRangeChange(item)}
            >
              {item === "custom" ? "Custom" : item}
            </button>
          ))}
        </div>
        {range === "custom" ? (
          <div className="chart-custom-range">
            <label>
              <span>From</span>
              <input type="datetime-local" value={customFrom} onChange={(event) => onCustomFromChange(event.target.value)} />
            </label>
            <label>
              <span>To</span>
              <input type="datetime-local" value={customTo} onChange={(event) => onCustomToChange(event.target.value)} />
            </label>
          </div>
        ) : null}
      </div>
      <div className="telemetry-toolbar-actions">
        <MetricSelector
          metrics={metrics}
          selectedMetricKeys={selectedMetricKeys}
          search={metricSearch}
          onSearchChange={onMetricSearchChange}
          onToggleMetric={onToggleMetric}
          onSelectAll={onSelectAll}
          onReset={onReset}
          onClear={onClear}
        />
        <label className="chart-option">
          <input type="checkbox" checked={showPoints} onChange={(event) => onShowPointsChange(event.target.checked)} />
          <span>Show points</span>
        </label>
        <label className="chart-select">
          <span>Detail</span>
          <select value={density} onChange={(event) => onDensityChange(event.target.value as TelemetryToolbarDensity)}>
            <option value="compact">Compact</option>
            <option value="balanced">Balanced</option>
            <option value="detailed">Detailed</option>
          </select>
        </label>
      </div>
    </div>
  );
}
