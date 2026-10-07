import { MetricDetailPanel } from "../components/MetricDetailPanel";
import { MetricGrid } from "../components/MetricGrid";
import { TelemetryToolbar, TelemetryToolbarRange } from "../components/TelemetryToolbar";
import { EventTimeline } from "../components/EventTimeline";
import { mapMetricDetail } from "../model/telemetry.mappers";
import { TelemetryOverviewResponse } from "../model/telemetry.types";


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
  const unavailable = selectedMetricKeys.filter((key) => !overview.metrics.some((metric) => metric.definition.key === key));
  const selectedMetricDefinitions = overview.metrics.map((metric) => metric.definition);
  const detail = openedMetricKey ? mapMetricDetail(overview, openedMetricKey, selectedEventId) : null;
  const rangeLabel = range === "custom" ? "Custom range" : range;
  const applyDetailRangeSelection = (from: string, to: string) => {
    const fromDate = new Date(from);
    const toDate = new Date(to);
    if (!Number.isFinite(fromDate.getTime()) || !Number.isFinite(toDate.getTime()) || fromDate >= toDate) return;
    onCustomFromChange(toDateTimeLocalValue(fromDate));
    onCustomToChange(toDateTimeLocalValue(toDate));
    onRangeChange("custom");
  };

  return (
    <section className="panel telemetry-monitoring-panel compact-telemetry-panel">
      {range === "custom" && (!Number.isFinite(Date.parse(customFrom)) || !Number.isFinite(Date.parse(customTo)) || Date.parse(customFrom) >= Date.parse(customTo)) ? <p className="error-message" role="alert">Custom interval end must be after start. Displayed evidence belongs to the last valid interval.</p> : null}
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
          <span>Metrics shown: {selectedMetrics.length} / {overview.metrics.length} available</span>
        </div>
      </div>
      {unavailable.length ? <p className="status-note">Selected metrics unavailable in this response: {unavailable.join(", ")}. Selection is preserved for later data.</p> : null}
      {!selectedMetrics.some((metric) => metric.series.length) ? <p className="status-note">No samples for the selected metrics and interval. Change the range or check the source.</p> : null}
      {overview.health.lastSampleAt ? <button type="button" className="ghost-button" onClick={() => { const end = new Date(overview.health.lastSampleAt!); applyDetailRangeSelection(new Date(end.getTime() - 3_600_000).toISOString(), new Date(end.getTime() + 60_000).toISOString()); }}>Show range around latest sample</button> : null}
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
