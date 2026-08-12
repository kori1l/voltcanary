import { type PointerEvent, useState } from "react";
import type { MlDatasetWorkbenchPreviewResponse } from "../../../analytics/mlModuleApi";
import type { DatasetWorkbenchDraft, DatasetWorkbenchPreviewStatus } from "../model/datasetWorkbench.types";
import {
  dateTimeLocalToIso,
  formatDateTime,
  formatNumber,
  formatPercent,
  isoToDateTimeLocal,
  statusLabel,
  workbenchStatusClass,
} from "../model/datasetWorkbench.utils";

export function TelemetryWindowChart({
  draft,
  preview,
  previewStatus,
  onPatch,
}: {
  draft: DatasetWorkbenchDraft;
  preview: MlDatasetWorkbenchPreviewResponse | null;
  previewStatus: DatasetWorkbenchPreviewStatus;
  onPatch: (patch: Partial<DatasetWorkbenchDraft>, affectsPreview?: boolean) => void;
}) {
  const [selection, setSelection] = useState<{ startX: number; currentX: number } | null>(null);
  const timeline = preview?.timeline ?? [];
  const candidates = preview?.candidateWindows ?? [];
  const width = 1040;
  const height = 420;
  const padding = { top: 34, right: 32, bottom: 72, left: 64 };
  const fromMs = new Date(dateTimeLocalToIso(draft.from)).getTime();
  const toMs = new Date(dateTimeLocalToIso(draft.to)).getTime();
  const rangeStart = Number.isFinite(fromMs) ? fromMs : new Date(preview?.selectedWindow.from ?? Date.now()).getTime();
  const rangeEnd = Number.isFinite(toMs) && toMs > rangeStart ? toMs : new Date(preview?.selectedWindow.to ?? Date.now()).getTime();
  const rangeSpan = Math.max(rangeEnd - rangeStart, 1);
  const chartLeft = padding.left;
  const chartRight = width - padding.right;
  const chartTop = padding.top;
  const chartBottom = height - padding.bottom;
  const xForTime = (value: string) => {
    const ms = new Date(value).getTime();
    if (!Number.isFinite(ms)) {
      return chartLeft;
    }
    return chartLeft + ((ms - rangeStart) / rangeSpan) * (chartRight - chartLeft);
  };
  const clampX = (x: number) => Math.min(chartRight, Math.max(chartLeft, x));
  const timeForX = (x: number) => rangeStart + ((clampX(x) - chartLeft) / Math.max(chartRight - chartLeft, 1)) * rangeSpan;
  const xFromPointer = (event: PointerEvent<SVGSVGElement>) => {
    const bounds = event.currentTarget.getBoundingClientRect();
    const scale = width / Math.max(bounds.width, 1);
    return clampX((event.clientX - bounds.left) * scale);
  };
  const startSelection = (event: PointerEvent<SVGSVGElement>) => {
    if (event.button !== 0 || previewStatus === "loading") return;
    const x = xFromPointer(event);
    setSelection({ startX: x, currentX: x });
    event.currentTarget.setPointerCapture(event.pointerId);
  };
  const updateSelection = (event: PointerEvent<SVGSVGElement>) => {
    if (!selection) return;
    setSelection({ ...selection, currentX: xFromPointer(event) });
  };
  const finishSelection = (event: PointerEvent<SVGSVGElement>) => {
    if (!selection) return;
    const endX = xFromPointer(event);
    const startX = selection.startX;
    setSelection(null);
    if (Math.abs(endX - startX) < 10) return;
    const nextFrom = new Date(Math.min(timeForX(startX), timeForX(endX))).toISOString();
    const nextTo = new Date(Math.max(timeForX(startX), timeForX(endX))).toISOString();
    onPatch({
      from: isoToDateTimeLocal(nextFrom),
      to: isoToDateTimeLocal(nextTo),
      quickRange: "custom",
      selectedCandidateId: null,
    });
  };
  const selectedX1 = xForTime(dateTimeLocalToIso(draft.from));
  const selectedX2 = xForTime(dateTimeLocalToIso(draft.to));
  const points = timeline.map((bucket) => {
    const x = (xForTime(bucket.bucketStart) + xForTime(bucket.bucketEnd)) / 2;
    const y = chartBottom - Math.max(0, Math.min(1, bucket.coverageRatio)) * (chartBottom - chartTop);
    return `${x},${y}`;
  }).join(" ");

  return (
    <section className="workbench-section" aria-labelledby="workbench-timeline-title">
      <div className="workbench-section-header">
        <div>
          <h3 id="workbench-timeline-title">Telemetry timeline</h3>
          <span>{timeline.length} buckets / {candidates.length} candidate windows</span>
        </div>
      </div>
      <div className="workbench-range-controls">
        <label>
          From
          <input
            aria-label="Workbench range from"
            type="datetime-local"
            value={draft.from}
            onChange={(event) => onPatch({ from: event.target.value, quickRange: "custom", selectedCandidateId: null })}
          />
        </label>
        <label>
          To
          <input
            aria-label="Workbench range to"
            type="datetime-local"
            value={draft.to}
            onChange={(event) => onPatch({ to: event.target.value, quickRange: "custom", selectedCandidateId: null })}
          />
        </label>
      </div>
      {!preview && previewStatus !== "loading" ? <div className="empty-state">Select a valid device scope and range to start automatic preview. Use refresh if dependencies need another attempt.</div> : null}
      {previewStatus === "loading" ? <div className="empty-state">Loading telemetry preview...</div> : null}
      {preview ? (
        <div className="workbench-chart-wrap">
          <svg
            className="workbench-chart"
            viewBox={`0 0 ${width} ${height}`}
            role="img"
            aria-label="Telemetry candidate window chart"
            onPointerDown={startSelection}
            onPointerMove={updateSelection}
            onPointerUp={finishSelection}
            onPointerCancel={() => setSelection(null)}
          >
            {[0, 0.5, 1].map((tick) => {
              const y = chartBottom - tick * (chartBottom - chartTop);
              return (
                <g key={tick}>
                  <line className="workbench-chart-grid" x1={chartLeft} x2={chartRight} y1={y} y2={y} />
                  <text className="workbench-chart-label" x={chartLeft - 10} y={y + 4} textAnchor="end">
                    {formatPercent(tick)}
                  </text>
                </g>
              );
            })}
            <line className="workbench-chart-axis" x1={chartLeft} x2={chartRight} y1={chartBottom} y2={chartBottom} />
            <line className="workbench-chart-axis" x1={chartLeft} x2={chartLeft} y1={chartTop} y2={chartBottom} />
            <rect className="workbench-selected-window" x={Math.min(selectedX1, selectedX2)} y={chartTop} width={Math.abs(selectedX2 - selectedX1)} height={chartBottom - chartTop} />
            {candidates.map((candidate) => {
              const x = xForTime(candidate.from);
              const x2 = xForTime(candidate.to);
              const selected = candidate.id === draft.selectedCandidateId;
              return (
                <g
                  aria-label={`Use ${candidate.kind} candidate from ${formatDateTime(candidate.from)} to ${formatDateTime(candidate.to)}`}
                  className={`workbench-candidate-band ${workbenchStatusClass(candidate.status)}${selected ? " workbench-candidate-band-selected" : ""}`}
                  key={candidate.id}
                  onClick={(event) => {
                    event.stopPropagation();
                    onPatch({
                      from: isoToDateTimeLocal(candidate.from),
                      to: isoToDateTimeLocal(candidate.to),
                      quickRange: "custom",
                      selectedCandidateId: candidate.id,
                    });
                  }}
                  role="button"
                  tabIndex={0}
                >
                  <rect x={Math.min(x, x2)} y={chartTop} width={Math.max(Math.abs(x2 - x), 2)} height={chartBottom - chartTop} />
                  <title>{`${statusLabel(candidate.status)} - ${candidate.explanation}`}</title>
                </g>
              );
            })}
            {timeline.map((bucket) => {
              const x = xForTime(bucket.bucketStart);
              const x2 = xForTime(bucket.bucketEnd);
              const heightRatio = Math.max(0.04, Math.min(1, bucket.coverageRatio));
              const barHeight = heightRatio * (chartBottom - chartTop);
              const eventKind = eventKindForBucket(bucket.events);
              return (
                <g key={`${bucket.bucketStart}-${bucket.bucketEnd}`}>
                  <rect
                    className={`workbench-density-bar ${workbenchStatusClass(bucket.status)}`}
                    x={Math.min(x, x2)}
                    y={chartBottom - barHeight}
                    width={Math.max(Math.abs(x2 - x), 2)}
                    height={barHeight}
                  >
                    <title>{`${formatDateTime(bucket.bucketStart)} - ${formatDateTime(bucket.bucketEnd)} / ${formatNumber(bucket.sampleCount)} samples / ${formatPercent(bucket.coverageRatio)} / ${statusLabel(bucket.status)}`}</title>
                  </rect>
                  {(bucket.status === "NO_DATA" || bucket.coverageRatio < 0.65) && (
                    <line className="workbench-gap-marker" x1={(x + x2) / 2} x2={(x + x2) / 2} y1={chartTop} y2={chartBottom} />
                  )}
                  {eventKind && (
                    <circle className={`workbench-event-marker workbench-event-${eventKind}`} cx={(x + x2) / 2} cy={chartTop + 15} r={5}>
                      <title>{bucket.events.join(", ")}</title>
                    </circle>
                  )}
                </g>
              );
            })}
            {points ? <polyline className="workbench-density-line" points={points} /> : null}
            {selection ? (
              <rect
                className="chart-range-selection"
                x={Math.min(selection.startX, selection.currentX)}
                y={chartTop}
                width={Math.abs(selection.currentX - selection.startX)}
                height={chartBottom - chartTop}
              />
            ) : null}
            <text className="workbench-chart-label" x={chartLeft} y={height - 28}>{formatDateTime(dateTimeLocalToIso(draft.from))}</text>
            <text className="workbench-chart-label" x={chartRight} y={height - 28} textAnchor="end">{formatDateTime(dateTimeLocalToIso(draft.to))}</text>
          </svg>
          <div className="workbench-chart-legend" aria-label="Telemetry timeline legend">
            <span><i className="legend-selected" />Selected range</span>
            <span><i className="legend-candidate" />Candidate window</span>
            <span><i className="legend-gap" />Gap or low coverage</span>
            <span><i className="legend-maintenance" />Maintenance</span>
            <span><i className="legend-failure" />Failure evidence</span>
          </div>
        </div>
      ) : null}
    </section>
  );
}

function eventKindForBucket(events: string[]) {
  const text = events.join(" ").toLowerCase();
  if (text.includes("failure") || text.includes("corrective")) {
    return "failure";
  }
  if (text.includes("maintenance")) {
    return "maintenance";
  }
  return "";
}
