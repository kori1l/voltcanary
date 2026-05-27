import { MetricDetailPanel } from "../components/MetricDetailPanel";
import { MetricGrid } from "../components/MetricGrid";
import { TelemetryToolbar, TelemetryToolbarDensity, TelemetryToolbarRange } from "../components/TelemetryToolbar";
import { EventTimeline } from "../components/EventTimeline";
import { mapMetricDetail } from "../model/telemetry.mappers";
import { TelemetryOverviewResponse } from "../model/telemetry.types";

export function TelemetryOverviewPage({
  overview,
  range,
  customFrom,
  customTo,
  density,
  showPoints,
  selectedMetricKeys,
  metricSearch,
  openedMetricKey,
  selectedEventId,
  maxDisplayPoints,
  onRangeChange,
  onCustomFromChange,
  onCustomToChange,
  onDensityChange,
  onShowPointsChange,
  onMetricSearchChange,
  onToggleMetric,
  onSelectAll,
  onResetMetrics,
  onClearMetrics,
  onOpenMetric,
  onCloseMetric,
  onSelectEvent,
}: {
  overview: TelemetryOverviewResponse;
  range: TelemetryToolbarRange;
  customFrom: string;
  customTo: string;
  density: TelemetryToolbarDensity;
  showPoints: boolean;
  selectedMetricKeys: string[];
  metricSearch: string;
  openedMetricKey: string | null;
  selectedEventId: string | null;
  maxDisplayPoints: number;
  onRangeChange: (range: TelemetryToolbarRange) => void;
  onCustomFromChange: (value: string) => void;
  onCustomToChange: (value: string) => void;
  onDensityChange: (density: TelemetryToolbarDensity) => void;
  onShowPointsChange: (value: boolean) => void;
  onMetricSearchChange: (value: string) => void;
  onToggleMetric: (metricKey: string) => void;
  onSelectAll: () => void;
  onResetMetrics: () => void;
  onClearMetrics: () => void;
  onOpenMetric: (metricKey: string) => void;
  onCloseMetric: () => void;
  onSelectEvent: (eventId: string) => void;
}) {
  const selectedMetrics = overview.metrics.filter((metric) => selectedMetricKeys.includes(metric.definition.key));
  const selectedMetricDefinitions = overview.metrics.map((metric) => metric.definition);
  const detail = openedMetricKey ? mapMetricDetail(overview, openedMetricKey, selectedEventId) : null;
  const rangeLabel = range === "custom" ? "Custom range" : range;

  return (
    <section className="panel telemetry-monitoring-panel compact-telemetry-panel">
      <div className="compact-telemetry-head">
        <div>
          <h2>Telemetry monitoring</h2>
          <p>
            Device health: {overview.health.status.replace("_", " ")} - {overview.health.reason}
          </p>
        </div>
        <div className="compact-telemetry-meta">
          <span>Quality: {overview.health.dataQuality}</span>
          <span>Range: {rangeLabel}</span>
          <span>Metrics: {selectedMetricKeys.length}</span>
        </div>
      </div>
      <TelemetryToolbar
        range={range}
        customFrom={customFrom}
        customTo={customTo}
        density={density}
        showPoints={showPoints}
        metrics={selectedMetricDefinitions}
        selectedMetricKeys={selectedMetricKeys}
        metricSearch={metricSearch}
        onRangeChange={onRangeChange}
        onCustomFromChange={onCustomFromChange}
        onCustomToChange={onCustomToChange}
        onDensityChange={onDensityChange}
        onShowPointsChange={onShowPointsChange}
        onMetricSearchChange={onMetricSearchChange}
        onToggleMetric={onToggleMetric}
        onSelectAll={onSelectAll}
        onReset={onResetMetrics}
        onClear={onClearMetrics}
      />
      <MetricGrid
        metrics={selectedMetrics}
        events={overview.events}
        showPoints={showPoints}
        maxDisplayPoints={maxDisplayPoints}
        onAnalyze={onOpenMetric}
      />
      <EventTimeline events={overview.events} selectedEventId={selectedEventId} onSelectEvent={onSelectEvent} />
      {detail ? (
        <MetricDetailPanel
          detail={detail}
          allEvents={overview.events}
          selectedEventId={selectedEventId}
          showPoints={showPoints}
          maxDisplayPoints={maxDisplayPoints * 2}
          rangeLabel={rangeLabel}
          onSelectEvent={onSelectEvent}
          onClose={onCloseMetric}
        />
      ) : null}
    </section>
  );
}
