import type { MlDatasetWorkbenchPreviewResponse } from "../../../analytics/mlModuleApi";
import type { DatasetWorkbenchDraft, DatasetWorkbenchPreviewStatus } from "../model/datasetWorkbench.types";
import {
  formatNumber,
  formatPercent,
  formatSeconds,
  humanizeFeatureName,
  metricStatusReason,
  statusLabel,
  workbenchStatusClass,
} from "../model/datasetWorkbench.utils";

export function MetricCoverageMatrix({
  draft,
  preview,
  previewStatus,
  staleMetrics,
  onPatch,
}: {
  draft: DatasetWorkbenchDraft;
  preview: MlDatasetWorkbenchPreviewResponse | null;
  previewStatus: DatasetWorkbenchPreviewStatus;
  staleMetrics: string[];
  onPatch: (patch: Partial<DatasetWorkbenchDraft>, affectsPreview?: boolean) => void;
}) {
  const metrics = preview?.availableMetrics ?? [];

  return (
    <section className="workbench-section workbench-metric-section" aria-labelledby="workbench-metrics-title">
      <div className="workbench-section-header">
        <div>
          <h3 id="workbench-metrics-title">Metric coverage</h3>
          <span>{metrics.length} preview metrics / {draft.selectedMetrics.length} selected</span>
        </div>
        <fieldset className="workbench-segment" aria-label="Metric selection mode">
          <label>
            <input
              checked={draft.metricSelectionMode === "COMMON"}
              name="metric-selection-mode"
              onChange={() => onPatch({ metricSelectionMode: "COMMON", selectedMetrics: [] })}
              type="radio"
            />
            Common metrics
          </label>
          <label>
            <input
              checked={draft.metricSelectionMode === "ALL_AVAILABLE"}
              name="metric-selection-mode"
              onChange={() => onPatch({ metricSelectionMode: "ALL_AVAILABLE", selectedMetrics: [] })}
              type="radio"
            />
            All available with coverage
          </label>
        </fieldset>
      </div>

      {previewStatus === "idle" && <div className="empty-state">Run preview to discover real telemetry metrics.</div>}
      {previewStatus === "loading" && <div className="empty-state">Loading preview metrics...</div>}
      {previewStatus === "error" && <div className="empty-state">Metric coverage is unavailable until preview succeeds.</div>}
      {previewStatus === "stale" && <div className="warning-message">Preview metrics are stale. Refresh preview before creating.</div>}
      {staleMetrics.length > 0 && <div className="warning-message">Unavailable metrics in draft: {staleMetrics.join(", ")}</div>}

      {preview && metrics.length === 0 ? (
        <div className="empty-state">Selected devices expose no telemetry metrics in the preview scope.</div>
      ) : null}

      {metrics.length > 0 ? (
        <div className="workbench-metric-table" aria-label="Metric selector">
          <div className="workbench-metric-head">
            <span>Metric</span>
            <span>Devices</span>
            <span>Coverage</span>
            <span>Samples</span>
            <span>Max gap</span>
            <span>Quality</span>
            <span>Status</span>
          </div>
          {metrics.map((metric) => {
            const checked = draft.selectedMetrics.includes(metric.metricName);
            return (
              <label className="workbench-metric-row" key={metric.metricName}>
                <span className="workbench-metric-name">
                  <input
                    checked={checked}
                    onChange={(event) => {
                      const selectedMetrics = event.target.checked
                        ? [...draft.selectedMetrics, metric.metricName]
                        : draft.selectedMetrics.filter((item) => item !== metric.metricName);
                      onPatch({
                        selectedMetrics,
                        focusedMetric: event.target.checked && !draft.focusedMetric ? metric.metricName : draft.focusedMetric,
                      });
                    }}
                    type="checkbox"
                  />
                  <span>
                    <strong>{metric.displayName || humanizeFeatureName(metric.metricName)}</strong>
                    <small>{metric.metricName} / {metric.canonicalMetricName}{metric.unit ? ` / ${metric.unit}` : ""}</small>
                  </span>
                </span>
                <span>{metric.devicesWithMetric}/{metric.selectedDeviceCount}</span>
                <span>{formatPercent(metric.coverageRatio)}</span>
                <span>{formatNumber(metric.sampleCount)}</span>
                <span>{formatSeconds(metric.maxSamplingGapSeconds)}</span>
                <span>{metric.duplicateTimestampRate > 0 ? `${formatPercent(metric.duplicateTimestampRate)} duplicates` : "Clean"} / {metric.nonFiniteImpossibleValueCount} invalid</span>
                <span className={`workbench-status-pill ${workbenchStatusClass(metric.status)}`} title={metricStatusReason(metric)}>
                  {statusLabel(metric.status)}
                </span>
              </label>
            );
          })}
        </div>
      ) : null}

      {metrics.length > 0 ? (
        <label className="workbench-focused-metric">
          Focused metric
          <select value={draft.focusedMetric} onChange={(event) => onPatch({ focusedMetric: event.target.value })}>
            <option value="">Coverage density</option>
            {metrics.map((metric) => (
              <option key={metric.metricName} value={metric.metricName}>
                {metric.displayName || metric.metricName}
              </option>
            ))}
          </select>
        </label>
      ) : null}
    </section>
  );
}
