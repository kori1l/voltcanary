import { MetricDetailPanel } from "../components/MetricDetailPanel";
import { MetricGrid } from "../components/MetricGrid";
import { TelemetryToolbar, TelemetryToolbarRange } from "../components/TelemetryToolbar";
import { EventTimeline } from "../components/EventTimeline";
import { mapMetricDetail } from "../model/telemetry.mappers";
import { TelemetryOverviewResponse } from "../model/telemetry.types";

const MIN_DETAIL_SELECTION_RANGE_MS = 60 * 1000;

export function TelemetryOverviewPage({
  overview,
  range,
  customFrom,
  customTo,
  showPoints,
  selectedMetricKeys,
  metricSearch,
  openedMetricKey,
  selectedEventId,
  maxDisplayPoints,
  onRangeChange,
  onCustomFromChange,
  onCustomToChange,
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
  showPoints: boolean;
  selectedMetricKeys: string[];
  metricSearch: string;
  openedMetricKey: string | null;
  selectedEventId: string | null;
  maxDisplayPoints: number;
  onRangeChange: (range: TelemetryToolbarRange) => void;
  onCustomFromChange: (value: string) => void;
  onCustomToChange: (value: string) => void;
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
  const applyDetailRangeSelection = (from: string, to: string) => {
    const normalized = ensureMinimumRange(new Date(from), new Date(to));
    onCustomFromChange(toDateTimeLocalValue(normalized.from));
    onCustomToChange(toDateTimeLocalValue(normalized.to));
    onRangeChange("custom");
  };

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
        showPoints={showPoints}
        metrics={selectedMetricDefinitions}
        selectedMetricKeys={selectedMetricKeys}
        metricSearch={metricSearch}
        onRangeChange={onRangeChange}
        onCustomFromChange={onCustomFromChange}
        onCustomToChange={onCustomToChange}
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
        range={overview.range}
        showPoints={showPoints}
        maxDisplayPoints={maxDisplayPoints}
        onAnalyze={onOpenMetric}
      />
      <EventTimeline events={overview.events} selectedEventId={selectedEventId} onSelectEvent={onSelectEvent} />
      {detail ? (
        <MetricDetailPanel
          detail={detail}
          allEvents={overview.events}
          range={overview.range}
          selectedEventId={selectedEventId}
          showPoints={showPoints}
          maxDisplayPoints={maxDisplayPoints * 2}
          rangeLabel={rangeLabel}
          onRangeSelect={applyDetailRangeSelection}
          onSelectEvent={onSelectEvent}
          onClose={onCloseMetric}
        />
      ) : null}
    </section>
  );
}

function toDateTimeLocalValue(date: Date) {
  const offsetMs = date.getTimezoneOffset() * 60 * 1000;
  return new Date(date.getTime() - offsetMs).toISOString().slice(0, 19);
}

function ensureMinimumRange(from: Date, to: Date) {
  const fromMs = from.getTime();
  const toMs = to.getTime();
  if (!Number.isFinite(fromMs) || !Number.isFinite(toMs) || fromMs >= toMs) {
    const now = Date.now();
    return {
      from: new Date(now - MIN_DETAIL_SELECTION_RANGE_MS),
      to: new Date(now),
    };
  }
  const spanMs = toMs - fromMs;
  if (spanMs >= MIN_DETAIL_SELECTION_RANGE_MS) {
    return { from, to };
  }
  const midpoint = fromMs + spanMs / 2;
  return {
    from: new Date(midpoint - MIN_DETAIL_SELECTION_RANGE_MS / 2),
    to: new Date(midpoint + MIN_DETAIL_SELECTION_RANGE_MS / 2),
  };
}
