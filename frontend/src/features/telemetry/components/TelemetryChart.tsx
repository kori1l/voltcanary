import { type PointerEvent, useState } from "react";
import { MetricThreshold, TelemetryEvent, TelemetryOverviewResponse, TelemetrySample } from "../model/telemetry.types";
import { formatCompactNumber, formatMetricValue, formatShortTime } from "../model/telemetry.utils";

type RenderSample = TelemetrySample & { synthetic?: boolean };

export function TelemetryChart({
  series,
  label,
  unit,
  range,
  threshold,
  events,
  showPoints,
  maxDisplayPoints,
  enableRangeSelection = false,
  onRangeSelect,
  mode = "compact",
}: {
  series: TelemetrySample[];
  label: string;
  unit: string;
  range: TelemetryOverviewResponse["range"];
  threshold?: MetricThreshold;
  events?: TelemetryEvent[];
  showPoints: boolean;
  maxDisplayPoints: number;
  enableRangeSelection?: boolean;
  onRangeSelect?: (from: string, to: string) => void;
  mode?: "compact" | "detailed";
}) {
  const [hovered, setHovered] = useState<{ sample: RenderSample; x: number; y: number; event?: TelemetryEvent } | null>(null);
  const [selection, setSelection] = useState<{ startX: number; currentX: number } | null>(null);
  const renderSeries = buildRenderSeries(series, maxDisplayPoints, range);
  const values = renderSeries.map((sample) => sample.value).filter((value): value is number => value !== null && Number.isFinite(value));
  const width = 820;
  const height = 330;
  const padding = { top: 28, right: 28, bottom: 54, left: 58 };
  const thresholdEntries = [
    { key: "normalMin", value: threshold?.normalMin },
    { key: "normalMax", value: threshold?.normalMax },
    { key: "warningMin", value: threshold?.warningMin },
    { key: "warningMax", value: threshold?.warningMax },
    { key: "criticalMin", value: threshold?.criticalMin },
    { key: "criticalMax", value: threshold?.criticalMax },
    { key: "spikeThreshold", value: threshold?.spikeThreshold },
  ].filter((entry): entry is { key: string; value: number } => entry.value !== undefined);
  const thresholdValues = thresholdEntries.map((entry) => entry.value);
  const allValues = [...values, ...thresholdValues];
  const hasValues = values.length > 0;
  const rawMin = Math.min(...allValues);
  const rawMax = Math.max(...allValues);
  const safeRawMin = Number.isFinite(rawMin) ? rawMin : 0;
  const safeRawMax = Number.isFinite(rawMax) ? rawMax : 1;
  const rawRange = safeRawMax - safeRawMin || Math.max(Math.abs(safeRawMax), 1);
  const paddingValue = rawRange * 0.08;
  const includeZero = ["W", "Wh", "A"].includes(unit) && safeRawMin >= 0;
  const actualMax = hasValues ? Math.max(...values) : safeRawMax;
  const warningReference = threshold?.warningMax ?? threshold?.spikeThreshold ?? threshold?.criticalMax;
  const minValue = includeZero ? 0 : safeRawMin - paddingValue;
  const maxValue = unit === "W"
    ? Math.max(actualMax * 1.15, warningReference !== undefined ? warningReference * 1.3 : actualMax * 1.15, 1)
    : safeRawMax + paddingValue;
  const valueRange = maxValue - minValue || 1;
  const yForValue = (value: number) => height - padding.bottom - ((value - minValue) / valueRange) * (height - padding.top - padding.bottom);
  const rangeStart = new Date(range.from).getTime();
  const rangeEnd = new Date(range.to).getTime();
  const rangeSpan = Math.max(rangeEnd - rangeStart, 1);
  const chartLeft = padding.left;
  const chartRight = width - padding.right;
  const clampChartX = (value: number) => Math.min(chartRight, Math.max(chartLeft, value));
  const timeForX = (x: number) => {
    const ratio = (clampChartX(x) - chartLeft) / Math.max(chartRight - chartLeft, 1);
    return rangeStart + ratio * rangeSpan;
  };
  const chartXFromPointer = (event: PointerEvent<SVGSVGElement>) => {
    const bounds = event.currentTarget.getBoundingClientRect();
    const scale = width / Math.max(bounds.width, 1);
    return clampChartX((event.clientX - bounds.left) * scale);
  };
  const canSelectRange = enableRangeSelection && Boolean(onRangeSelect) && Number.isFinite(rangeStart) && Number.isFinite(rangeEnd) && rangeEnd > rangeStart;
  const startRangeSelection = (event: PointerEvent<SVGSVGElement>) => {
    if (!canSelectRange || event.button !== 0) return;
    const x = chartXFromPointer(event);
    setHovered(null);
    setSelection({ startX: x, currentX: x });
    event.currentTarget.setPointerCapture(event.pointerId);
  };
  const updateRangeSelection = (event: PointerEvent<SVGSVGElement>) => {
    if (!selection) return;
    setSelection({ ...selection, currentX: chartXFromPointer(event) });
  };
  const finishRangeSelection = (event: PointerEvent<SVGSVGElement>) => {
    if (!selection) return;
    const endX = chartXFromPointer(event);
    const startX = selection.startX;
    setSelection(null);
    if (Math.abs(endX - startX) < 10 || !onRangeSelect) return;
    const fromMs = Math.min(timeForX(startX), timeForX(endX));
    const toMs = Math.max(timeForX(startX), timeForX(endX));
    onRangeSelect(new Date(fromMs).toISOString(), new Date(toMs).toISOString());
  };
  const xForTime = (timestamp: string) => {
    const current = new Date(timestamp).getTime();
    if (!Number.isFinite(current)) return padding.left;
    return padding.left + ((current - rangeStart) / rangeSpan) * (width - padding.left - padding.right);
  };
  const coordinates = renderSeries
    .filter((sample) => sample.value !== null)
    .map((sample) => ({
      sample,
      x: xForTime(sample.timestamp),
      y: yForValue(sample.value as number),
    }));
  const eventCoordinates = (events ?? []).map((event) => ({
    event,
    x: xForTimestamp(range, event.timestamp, padding.left, width - padding.right),
  }));
  const maxPoint = coordinates.reduce((current, next) => ((next.sample.value ?? 0) > (current.sample.value ?? 0) ? next : current), coordinates[0]);
  const visiblePoints = showPoints ? coordinates : maxPoint && isAboveThreshold(maxPoint.sample.value, threshold) ? [maxPoint] : [];
  const yTicks = [minValue, minValue + valueRange / 2, maxValue];
  const xTicks = rangeTicks(range, padding.left, width - padding.right);

  return (
    <div className={`chart-wrap annotated-chart chart-${mode === "detailed" ? "large" : "normal"}`}>
      <svg
        className={canSelectRange ? "chart-range-selectable" : undefined}
        viewBox={`0 0 ${width} ${height}`}
        role="img"
        aria-label={`${label} chart`}
        onPointerDown={startRangeSelection}
        onPointerMove={updateRangeSelection}
        onPointerUp={finishRangeSelection}
        onPointerCancel={() => setSelection(null)}
      >
        {yTicks.map((tick) => {
          const y = yForValue(tick);
          return (
            <g key={tick}>
              <line className="chart-grid-line" x1={padding.left} y1={y} x2={width - padding.right} y2={y} />
              <text className="chart-axis-label" x={padding.left - 10} y={y + 4} textAnchor="end">
                {formatCompactNumber(tick)} {unit}
              </text>
            </g>
          );
        })}

        <line className="chart-axis" x1={padding.left} y1={height - padding.bottom} x2={width - padding.right} y2={height - padding.bottom} />
        <line className="chart-axis" x1={padding.left} y1={padding.top} x2={padding.left} y2={height - padding.bottom} />

        <rect className="chart-selection-hit-area" x={padding.left} y={padding.top} width={width - padding.left - padding.right} height={height - padding.top - padding.bottom} />

        {thresholdEntries.map(({ key, value }, index) => (
          <line
            key={`threshold-${key}-${index}-${value}`}
            className={isCriticalThreshold(value, threshold) ? "chart-threshold chart-threshold-critical" : "chart-threshold"}
            x1={padding.left}
            y1={yForValue(value)}
            x2={width - padding.right}
            y2={yForValue(value)}
          />
        ))}

        {eventCoordinates.slice(0, mode === "detailed" ? 24 : 8).map(({ event, x }) => (
          <g key={event.id}>
            <line className={`chart-event-marker chart-event-${event.severity}`} x1={x} y1={padding.top} x2={x} y2={height - padding.bottom} />
            <title>{`${formatShortTime(event.timestamp)} - ${event.title}`}</title>
          </g>
        ))}

        {coordinates.length > 0 ? <polyline points={coordinates.map((coordinate) => `${coordinate.x},${coordinate.y}`).join(" ")} /> : null}

        {coordinates.length === 0 ? (
          <text className="chart-axis-label" x={(padding.left + width - padding.right) / 2} y={(padding.top + height - padding.bottom) / 2} textAnchor="middle">
            No samples in selected range
          </text>
        ) : null}

        {selection ? (
          <rect
            className="chart-range-selection"
            x={Math.min(selection.startX, selection.currentX)}
            y={padding.top}
            width={Math.abs(selection.currentX - selection.startX)}
            height={height - padding.top - padding.bottom}
          />
        ) : null}

        {visiblePoints.map((coordinate, index) => (
          <circle
            key={`visible-${coordinate.sample.timestamp}-${index}`}
            className={isAboveThreshold(coordinate.sample.value, threshold) ? "chart-spike-point" : undefined}
            cx={coordinate.x}
            cy={coordinate.y}
            r={isAboveThreshold(coordinate.sample.value, threshold) ? 6 : 4}
            onMouseEnter={() => setHovered(coordinate)}
            onMouseLeave={() => setHovered(null)}
            onFocus={() => setHovered(coordinate)}
            tabIndex={0}
          >
            <title>{`${formatShortTime(coordinate.sample.timestamp)} - ${formatMetricValue(coordinate.sample.value, unit)}`}</title>
          </circle>
        ))}

        {coordinates.map((coordinate, index) => {
          const nearbyEvent = eventCoordinates.find(({ x }) => Math.abs(x - coordinate.x) < 6)?.event;
          return (
            <circle
              key={`hit-${coordinate.sample.timestamp}-${index}`}
              className="chart-hit-point"
              cx={coordinate.x}
              cy={coordinate.y}
              r={8}
              onMouseEnter={() => setHovered({ ...coordinate, event: nearbyEvent })}
              onMouseLeave={() => setHovered(null)}
              onFocus={() => setHovered({ ...coordinate, event: nearbyEvent })}
              tabIndex={0}
            >
              <title>{`${formatShortTime(coordinate.sample.timestamp)} - ${formatMetricValue(coordinate.sample.value, unit)}`}</title>
            </circle>
          );
        })}

        {xTicks.map((tick) => {
          const lines = formatChartTimeLabel(range, tick.timestamp);
          return (
            <text key={tick.timestamp} className="chart-axis-label" x={tick.x} y={height - 28} textAnchor="middle">
              {lines.map((line, index) => (
                <tspan key={`${line}-${index}`} x={tick.x} dy={index === 0 ? 0 : 14}>
                  {line}
                </tspan>
              ))}
            </text>
          );
        })}
      </svg>
      {hovered ? (
        <div className="chart-tooltip" style={{ left: `${(hovered.x / width) * 100}%`, top: `${(hovered.y / height) * 100}%` }}>
          <strong>{formatShortTime(hovered.sample.timestamp)}</strong>
          <span>{formatMetricValue(hovered.sample.value, unit)}</span>
          {hovered.sample.synthetic ? <em>No data - 0</em> : null}
          {isAboveThreshold(hovered.sample.value, threshold) ? <em>Outside threshold</em> : null}
          {hovered.event ? <em>{hovered.event.title}</em> : null}
        </div>
      ) : null}
    </div>
  );
}

function buildRenderSeries(series: TelemetrySample[], maxPoints: number, range: TelemetryOverviewResponse["range"]): RenderSample[] {
  const sorted = [...series].sort((first, second) => new Date(first.timestamp).getTime() - new Date(second.timestamp).getTime());
  const fromMs = new Date(range.from).getTime();
  const toMs = new Date(range.to).getTime();
  if (!Number.isFinite(fromMs) || !Number.isFinite(toMs) || fromMs >= toMs) {
    return sampleSeries(sorted, maxPoints);
  }

  const bucketCount = Math.max(2, maxPoints);
  const bucketSizeMs = (toMs - fromMs) / (bucketCount - 1);
  const buckets = Array.from({ length: bucketCount }, (_, index) => {
    const timestampMs = index === bucketCount - 1 ? toMs : fromMs + index * bucketSizeMs;
    return {
      timestamp: new Date(timestampMs).toISOString(),
      metricKey: sorted[0]?.metricKey ?? "",
      value: null as number | null,
      quality: "partial" as TelemetrySample["quality"],
      synthetic: true,
      sourceCount: 0,
    };
  });

  for (const sample of sorted) {
    const sampleMs = new Date(sample.timestamp).getTime();
    if (!Number.isFinite(sampleMs) || sampleMs < fromMs || sampleMs > toMs || sample.value === null || !Number.isFinite(sample.value)) {
      continue;
    }
    const index = Math.min(bucketCount - 1, Math.max(0, Math.round((sampleMs - fromMs) / bucketSizeMs)));
    const bucket = buckets[index];
    if (bucket.sourceCount === 0) {
      bucket.value = sample.value;
    } else {
      bucket.value = ((bucket.value ?? 0) * bucket.sourceCount + sample.value) / (bucket.sourceCount + 1);
    }
    bucket.timestamp = sample.timestamp;
    bucket.metricKey = sample.metricKey;
    bucket.quality = sample.quality ?? "partial";
    bucket.synthetic = false;
    bucket.sourceCount += 1;
  }

  return buckets.map(({ sourceCount: _sourceCount, ...sample }) => sample);
}

function sampleSeries(series: TelemetrySample[], maxPoints: number): RenderSample[] {
  if (series.length <= maxPoints) {
    return series;
  }
  const lastIndex = series.length - 1;
  const values = series.map((sample) => sample.value ?? Number.NEGATIVE_INFINITY);
  const maxIndex = values.reduce((currentIndex, value, index) => (value > values[currentIndex] ? index : currentIndex), 0);
  const indexes = new Set<number>([0, lastIndex, maxIndex]);
  for (let index = 1; index < maxPoints - 1; index += 1) {
    indexes.add(Math.round((index / (maxPoints - 1)) * lastIndex));
  }
  return [...indexes].sort((first, second) => first - second).map((sourceIndex) => series[sourceIndex]);
}

function xForTimestamp(range: TelemetryOverviewResponse["range"], timestamp: string, left: number, right: number) {
  const first = new Date(range.from).getTime();
  const last = new Date(range.to).getTime();
  const current = new Date(timestamp).getTime();
  if (last === first) return left;
  return left + ((current - first) / (last - first)) * (right - left);
}

function rangeTicks(range: TelemetryOverviewResponse["range"], left: number, right: number) {
  const fromMs = new Date(range.from).getTime();
  const toMs = new Date(range.to).getTime();
  if (!Number.isFinite(fromMs) || !Number.isFinite(toMs) || fromMs >= toMs) {
    return [];
  }
  return [
    { timestamp: new Date(fromMs).toISOString(), x: left },
    { timestamp: new Date(fromMs + (toMs - fromMs) / 2).toISOString(), x: left + (right - left) / 2 },
    { timestamp: new Date(toMs).toISOString(), x: right },
  ];
}

function formatChartTimeLabel(range: TelemetryOverviewResponse["range"], timestamp: string) {
  const fromMs = new Date(range.from).getTime();
  const toMs = new Date(range.to).getTime();
  const value = new Date(timestamp);
  const rangeSpanMs = toMs - fromMs;
  if (Number.isFinite(rangeSpanMs) && rangeSpanMs >= 24 * 60 * 60 * 1000) {
    return [
      value.toLocaleDateString([], { month: "short", day: "numeric" }),
      value.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" }),
    ];
  }
  return [formatShortTime(timestamp)];
}

function isAboveThreshold(value: number | null, threshold?: MetricThreshold) {
  if (value === null) return false;
  return (
    (threshold?.criticalMax !== undefined && value > threshold.criticalMax) ||
    (threshold?.warningMax !== undefined && value > threshold.warningMax) ||
    (threshold?.spikeThreshold !== undefined && value > threshold.spikeThreshold) ||
    (threshold?.criticalMin !== undefined && value < threshold.criticalMin) ||
    (threshold?.warningMin !== undefined && value < threshold.warningMin)
  );
}

function isCriticalThreshold(value: number, threshold?: MetricThreshold) {
  return value === threshold?.criticalMin || value === threshold?.criticalMax;
}
