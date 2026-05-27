import { MetricDetailResponse, TelemetryEvent } from "../model/telemetry.types";
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
  selectedEventId,
  showPoints,
  maxDisplayPoints,
  rangeLabel,
  onSelectEvent,
  onClose,
}: {
  detail: MetricDetailResponse;
  allEvents: TelemetryEvent[];
  selectedEventId?: string | null;
  showPoints: boolean;
  maxDisplayPoints: number;
  rangeLabel: string;
  onSelectEvent: (eventId: string) => void;
  onClose: () => void;
}) {
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
              {detail.series.at(-1) ? ` to ${formatShortDateTime(detail.series.at(-1)!.timestamp)}` : ""}
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
        </div>

        <TelemetryChart
          series={detail.series}
          label={detail.metric.name}
          unit={detail.metric.unit}
          threshold={detail.threshold}
          events={allEvents}
          showPoints={showPoints}
          maxDisplayPoints={maxDisplayPoints}
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
