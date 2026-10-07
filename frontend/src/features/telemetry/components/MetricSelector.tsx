import { MetricDefinition } from "../model/telemetry.types";

export function MetricSelector({
  metrics,
  selectedMetricKeys,
  search,
  onSearchChange,
  onToggleMetric,
  onSelectAll,
  onReset,
  onClear,
}: {
  metrics: MetricDefinition[];
  selectedMetricKeys: string[];
  search: string;
  onSearchChange: (value: string) => void;
  onToggleMetric: (metricKey: string) => void;
  onSelectAll: () => void;
  onReset: () => void;
  onClear: () => void;
}) {
  const query = search.trim().toLowerCase();
  const filtered = metrics.filter((metric) => !query || metric.key.toLowerCase().includes(query) || metric.name.toLowerCase().includes(query));

  return (
    <details className="metric-selector">
      <summary>
        Metrics <strong>{metrics.filter((metric) => selectedMetricKeys.includes(metric.key)).length} shown</strong>
      </summary>
      <div className="metric-selector-panel">
        <div className="chart-settings-actions">
          <button className="chart-text-button" type="button" onClick={onSelectAll}>
            All
          </button>
          <button className="chart-text-button" type="button" onClick={onReset}>
            Default
          </button>
          <button className="chart-text-button" type="button" onClick={onClear}>
            Clear
          </button>
        </div>
        <input
          className="chart-metric-search"
          type="search"
          aria-label="Search telemetry metrics"
          value={search}
          onChange={(event) => onSearchChange(event.target.value)}
          placeholder="Filter metrics"
        />
        <div className="chart-metric-list">
          {!filtered.length ? <p>No metrics match this search.</p> : null}
          {filtered.map((metric) => (
            <label className="chart-metric-toggle" key={metric.key}>
              <input type="checkbox" checked={selectedMetricKeys.includes(metric.key)} onChange={() => onToggleMetric(metric.key)} />
              <span>{metric.name}</span>
            </label>
          ))}
        </div>
      </div>
    </details>
  );
}
