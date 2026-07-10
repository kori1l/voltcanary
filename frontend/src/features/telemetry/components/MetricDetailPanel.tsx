import { useState } from "react";
import { MetricDetailResponse, TelemetryEvent, TelemetryOverviewResponse } from "../model/telemetry.types";
import { formatShortDateTime } from "../model/telemetry.utils";
import { CorrelationPanel } from "./CorrelationPanel";
import { EventTimeline } from "./EventTimeline";
import { MetricStatsBar } from "./MetricStatsBar";
import { RecommendationPanel } from "./RecommendationPanel";
import { StatusBadge } from "./StatusBadge";
import { TelemetryChart } from "./TelemetryChart";
import { ThresholdLegend } from "./ThresholdLegend";

export function MetricDetailPanel({
  detail,
  allEvents,
  range,
  selectedEventId,
  showPoints,
  maxDisplayPoints,
  rangeLabel,
  onRangeSelect,
  onSelectEvent,
  onClose,
}: {
  detail: MetricDetailResponse;
  allEvents: TelemetryEvent[];
  range: TelemetryOverviewResponse["range"];
  selectedEventId?: string | null;
  showPoints: boolean;
  maxDisplayPoints: number;
  rangeLabel: string;
  onRangeSelect: (from: string, to: string) => void;
  onSelectEvent: (eventId: string) => void;
  onClose: () => void;
}) {
  const [isBrushZoomEnabled, setIsBrushZoomEnabled] = useState(true);
  const lastSample = detail.series[detail.series.length - 1];

  return (
    <div className="metric-detail-overlay" role="presentation" onMouseDown={onClose}>
      <section
        className="metric-detail-surface metric-detail-panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="metric-detail-title"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <div className="section-header">
          <div>
            <div className="telemetry-monitor-title">
              <h2 id="metric-detail-title">{detail.metric.name}</h2>
              <StatusBadge status={detail.status} />
            </div>
            <p>
              {detail.statistics.samplesCount} samples - {rangeLabel}
              {detail.series[0] ? ` - ${formatShortDateTime(detail.series[0].timestamp)}` : ""}
              {lastSample ? ` to ${formatShortDateTime(lastSample.timestamp)}` : ""}
            </p>
          </div>
          <button className="ghost-button icon-button-text" type="button" onClick={onClose}>
            Close
          </button>
        </div>

        <div className="metric-detail-toolbar" aria-label="Analysis tools">
          <span>{rangeLabel}</span>
          <span>{detail.statistics.samplesCount} samples</span>
          <span>{detail.events.length} metric events</span>
          <span>{allEvents.length} total events</span>
          <span>{showPoints ? "Points on" : "Points off"}</span>
          <span>{maxDisplayPoints} display points</span>
          <label className="chart-option metric-detail-option">
            <input type="checkbox" checked={isBrushZoomEnabled} onChange={(event) => setIsBrushZoomEnabled(event.target.checked)} />
            <span>Brush zoom</span>
          </label>
        </div>

        <TelemetryChart
          series={detail.series}
          label={detail.metric.name}
          unit={detail.metric.unit}
          range={range}
          threshold={detail.threshold}
          events={allEvents}
          showPoints={showPoints}
          maxDisplayPoints={maxDisplayPoints}
          enableRangeSelection={isBrushZoomEnabled}
          onRangeSelect={onRangeSelect}
          mode="detailed"
        />

        <MetricStatsBar metric={detail.metric} statistics={detail.statistics} />

        <div className="metric-detail-layout">
          <ThresholdLegend threshold={detail.threshold} unit={detail.metric.unit} />
          <RecommendationPanel
            summary={detail.analysis?.summary}
            possibleCause={detail.analysis?.possibleCause}
            recommendedAction={detail.analysis?.recommendedAction}
          />
          <CorrelationPanel relatedSignals={detail.relatedSignals} />
        </div>

        <EventTimeline events={detail.events} selectedEventId={selectedEventId} onSelectEvent={onSelectEvent} />
      </section>
    </div>
  );
}
