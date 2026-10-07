import { useEffect, useState } from "react";
import { Dialog } from "../../../components/Dialog";
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
  const localTime = (value: string) => { const date = new Date(value); return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 19); };
  const [exactFrom, setExactFrom] = useState(localTime(range.from));
  const [exactTo, setExactTo] = useState(localTime(range.to));
  useEffect(() => { setExactFrom(localTime(range.from)); setExactTo(localTime(range.to)); }, [range.from, range.to]);
  const validRange = Number.isFinite(Date.parse(exactFrom)) && Number.isFinite(Date.parse(exactTo)) && Date.parse(exactFrom) < Date.parse(exactTo);

  return (
    <Dialog title={detail.metric.name} onClose={onClose} wide>
      <section className="console-metric-detail">
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
        <form className="form-grid-two" onSubmit={(event) => { event.preventDefault(); if (validRange) onRangeSelect(new Date(exactFrom).toISOString(), new Date(exactTo).toISOString()); }}>
          <label>Analyze from<input type="datetime-local" step="1" value={exactFrom} onChange={(event) => setExactFrom(event.target.value)} /></label><label>Analyze to<input type="datetime-local" step="1" value={exactTo} onChange={(event) => setExactTo(event.target.value)} /></label>
          <small>Exact interval in {Intl.DateTimeFormat().resolvedOptions().timeZone}. Keyboard alternative to brush selection.</small><button className="ghost-button" disabled={!validRange}>Apply analysis interval</button>
        </form>
        <p className="muted-text">Measurements are recorded samples. Thresholds and related-signal explanations are inferred evidence; recommended checks are not a confirmed diagnosis.</p>

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
    </Dialog>
  );
}
