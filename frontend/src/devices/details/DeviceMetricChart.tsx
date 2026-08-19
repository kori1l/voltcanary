import { type PointerEvent, useState } from "react";
import { AnomalyResponse } from "../../analytics/analyticsApi";
import { ChartPoint } from "../../telemetry/telemetryApi";
import { MaintenanceWindow } from "../devicesApi";
import { formatCompactNumber, formatMetricValue, formatShortTime } from "./deviceDetailsFormatting";

type RenderChartPoint = ChartPoint & { synthetic?: boolean };

export function DeviceMetricChart({
  points,
  label,
  unit,
  timeRange,
  baseline,
  threshold,
  anomaly,
  maintenanceWindows = [],
  showPoints,
  maxDisplayPoints,
  interactive = true,
  enableRangeSelection = false,
  onRangeSelect,
  size = "normal",
}: {
  points: ChartPoint[];
  label: string;
  unit: string;
  timeRange?: { from: string; to: string } | null;
  baseline?: number;
  threshold?: number;
  anomaly?: AnomalyResponse | null;
  maintenanceWindows?: MaintenanceWindow[];
  showPoints: boolean;
  maxDisplayPoints: number;
  interactive?: boolean;
  enableRangeSelection?: boolean;
  onRangeSelect?: (from: string, to: string) => void;
  size?: "normal" | "large";
}) {
  const [hovered, setHovered] = useState<{ point: RenderChartPoint; x: number; y: number } | null>(null);
  const [selection, setSelection] = useState<{ startX: number; currentX: number } | null>(null);
  const renderPoints = buildChartRenderPoints(points, maxDisplayPoints, timeRange);
  if (renderPoints.length === 0) {
    return (
      <div className={`chart-wrap annotated-chart chart-${size}`}>
        <div className="empty-state">
          <strong>No samples</strong>
          <span>Try a wider time range.</span>
        </div>
      </div>
    );
  }

  const width = 820;
  const height = 330;
  const padding = { top: 28, right: 28, bottom: 54, left: 58 };
  const values = [...renderPoints.map((point) => point.value), baseline, threshold].filter((value): value is number => value !== undefined);
  const rawMin = Math.min(...values);
  const rawMax = Math.max(...values);
  const rawRange = rawMax - rawMin || Math.max(Math.abs(rawMax), 1);
  const paddingValue = rawRange * 0.08;
  const includeZero = ["W", "Wh", "A"].includes(unit) && rawMin >= 0;
  const minValue = includeZero ? Math.min(0, rawMin - paddingValue) : rawMin - paddingValue;
  const maxValue = rawMax + paddingValue;
  const range = maxValue - minValue || 1;
  const yForValue = (value: number) => height - padding.bottom - ((value - minValue) / range) * (height - padding.top - padding.bottom);
  const timeStart = timeRange ? new Date(timeRange.from).getTime() : new Date(renderPoints[0].timestamp).getTime();
  const timeEnd = timeRange ? new Date(timeRange.to).getTime() : new Date(renderPoints[renderPoints.length - 1].timestamp).getTime();
  const timeSpan = Math.max(timeEnd - timeStart, 1);
  const xForTime = (timestamp: string) => {
    const time = new Date(timestamp).getTime();
    if (!Number.isFinite(time)) {
      return padding.left;
    }
    return padding.left + ((time - timeStart) / timeSpan) * (width - padding.left - padding.right);
  };
  const chartLeft = padding.left;
  const chartRight = width - padding.right;
  const clampChartX = (value: number) => Math.min(chartRight, Math.max(chartLeft, value));
  const timeForX = (x: number) => {
    const ratio = (clampChartX(x) - chartLeft) / Math.max(chartRight - chartLeft, 1);
    return timeStart + ratio * timeSpan;
  };
  const chartXFromPointer = (event: PointerEvent<SVGSVGElement>) => {
    const bounds = event.currentTarget.getBoundingClientRect();
    const scale = width / Math.max(bounds.width, 1);
    return clampChartX((event.clientX - bounds.left) * scale);
  };
  const canSelectRange = enableRangeSelection && Boolean(onRangeSelect) && Number.isFinite(timeStart) && Number.isFinite(timeEnd) && timeEnd > timeStart;
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
  const coordinates = renderPoints.map((point) => ({
    point,
    x: xForTime(point.timestamp),
    y: yForValue(point.value),
  }));
  const spike = coordinates.reduce((current, next) => (next.point.value > current.point.value ? next : current), coordinates[0]);
  const visiblePointCoordinates = showPoints
    ? coordinates
    : threshold !== undefined && spike.point.value >= threshold
      ? [spike]
      : [];
  const yTicks = [minValue, minValue + range / 2, maxValue];
  const xTicks = metricChartRangeTicks(timeRange, padding.left, width - padding.right);
  const maintenanceBands = maintenanceWindows
    .filter((window) => window.status === "ACTIVE" || window.status === "SCHEDULED")
    .map((window) => {
      const start = Math.max(new Date(window.startAt).getTime(), timeStart);
      const end = Math.min(new Date(window.endAt).getTime(), timeEnd);
      return Number.isFinite(start) && Number.isFinite(end) && end > start
        ? {
            id: window.id,
            title: window.title,
            status: window.status,
            x: padding.left + ((start - timeStart) / timeSpan) * (width - padding.left - padding.right),
            width: ((end - start) / timeSpan) * (width - padding.left - padding.right),
          }
        : null;
    })
    .filter((band): band is { id: string; title: string; status: MaintenanceWindow["status"]; x: number; width: number } => band !== null);

  return (
    <div className={`chart-wrap annotated-chart chart-${size}`}>
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

        {maintenanceBands.map((band) => (
          <g key={band.id}>
            <rect
              className={`chart-maintenance-band chart-maintenance-band-${band.status.toLowerCase()}`}
              x={band.x}
              y={padding.top}
              width={band.width}
              height={height - padding.top - padding.bottom}
            />
            <title>{band.title}</title>
          </g>
        ))}

        {baseline !== undefined ? (
          <g>
            <line className="chart-baseline" x1={padding.left} y1={yForValue(baseline)} x2={width - padding.right} y2={yForValue(baseline)} />
            <text className="chart-line-label" x={width - padding.right - 8} y={yForValue(baseline) - 6} textAnchor="end">
              Typical range
            </text>
          </g>
        ) : null}

        {threshold !== undefined ? (
          <g>
            <line className="chart-threshold" x1={padding.left} y1={yForValue(threshold)} x2={width - padding.right} y2={yForValue(threshold)} />
            <text className="chart-line-label threshold-label" x={width - padding.right - 8} y={yForValue(threshold) - 6} textAnchor="end">
              Alert threshold
            </text>
          </g>
        ) : null}

        <polyline points={coordinates.map((coordinate) => `${coordinate.x},${coordinate.y}`).join(" ")} />

        {selection ? (
          <rect
            className="chart-range-selection"
            x={Math.min(selection.startX, selection.currentX)}
            y={padding.top}
            width={Math.abs(selection.currentX - selection.startX)}
            height={height - padding.top - padding.bottom}
          />
        ) : null}

        {visiblePointCoordinates.map((coordinate, index) => {
          const isSpike = coordinate === spike && (threshold === undefined || coordinate.point.value >= threshold);
          return (
            <circle
              key={`visible-${coordinate.point.timestamp}-${index}`}
              className={isSpike ? "chart-spike-point" : undefined}
              cx={coordinate.x}
              cy={coordinate.y}
              r={isSpike ? 6 : 4}
              onMouseEnter={interactive ? () => setHovered(coordinate) : undefined}
              onMouseLeave={interactive ? () => setHovered(null) : undefined}
              onFocus={interactive ? () => setHovered(coordinate) : undefined}
              tabIndex={interactive ? 0 : -1}
            >
              <title>{`${formatShortTime(coordinate.point.timestamp)} - ${formatMetricValue(coordinate.point.value, unit)}`}</title>
            </circle>
          );
        })}

        {coordinates.map((coordinate, index) => (
          <circle
            key={`hit-${coordinate.point.timestamp}-${index}`}
            className="chart-hit-point"
            cx={coordinate.x}
            cy={coordinate.y}
            r={8}
            onMouseEnter={interactive ? () => setHovered(coordinate) : undefined}
            onMouseLeave={interactive ? () => setHovered(null) : undefined}
            onFocus={interactive ? () => setHovered(coordinate) : undefined}
            tabIndex={interactive ? 0 : -1}
          >
            <title>{`${formatShortTime(coordinate.point.timestamp)} - ${formatMetricValue(coordinate.point.value, unit)}`}</title>
          </circle>
        ))}

        {anomaly && spike ? (
          <text className="chart-spike-label" x={spike.x} y={Math.max(spike.y - 14, 16)} textAnchor="middle">
            Power spike
          </text>
        ) : null}

        {xTicks.map((tick) => {
          const lines = formatMetricChartTimeLabel(timeRange, tick.timestamp);
          return (
            <text key={tick.timestamp} className="chart-axis-label" x={tick.x} y={height - 28} textAnchor="middle">
              {lines.map((line, index) => (
                <tspan key={line} x={tick.x} dy={index === 0 ? 0 : 14}>
                  {line}
                </tspan>
              ))}
            </text>
          );
        })}
      </svg>
      {hovered ? (
        <div className="chart-tooltip" style={{ left: `${(hovered.x / width) * 100}%`, top: `${(hovered.y / height) * 100}%` }}>
          <strong>{formatShortTime(hovered.point.timestamp)}</strong>
          <span>{hovered.point.synthetic ? "No data - 0" : formatMetricValue(hovered.point.value, unit)}</span>
          {threshold !== undefined && hovered.point.value >= threshold ? <em>Above threshold</em> : null}
        </div>
      ) : null}
    </div>
  );
}

function buildChartRenderPoints(points: ChartPoint[], maxPoints: number, timeRange?: { from: string; to: string } | null): RenderChartPoint[] {
  const sortedPoints = [...points].sort((first, second) => new Date(first.timestamp).getTime() - new Date(second.timestamp).getTime());
  if (!timeRange) {
    return sampleChartPoints(sortedPoints, maxPoints);
  }

  const fromMs = new Date(timeRange.from).getTime();
  const toMs = new Date(timeRange.to).getTime();
  if (!Number.isFinite(fromMs) || !Number.isFinite(toMs) || fromMs >= toMs) {
    return sampleChartPoints(sortedPoints, maxPoints);
  }

  const bucketCount = Math.max(2, maxPoints);
  const bucketSizeMs = (toMs - fromMs) / (bucketCount - 1);
  const buckets = Array.from({ length: bucketCount }, (_, index) => {
    const timestampMs = index === bucketCount - 1 ? toMs : fromMs + index * bucketSizeMs;
    return {
      timestamp: new Date(timestampMs).toISOString(),
      value: 0,
      synthetic: true,
      sourceCount: 0,
    };
  });

  for (const point of sortedPoints) {
    const pointMs = new Date(point.timestamp).getTime();
    if (!Number.isFinite(pointMs) || pointMs < fromMs || pointMs > toMs) {
      continue;
    }
    const index = Math.min(bucketCount - 1, Math.max(0, Math.round((pointMs - fromMs) / bucketSizeMs)));
    const bucket = buckets[index];
    if (bucket.sourceCount === 0) {
      bucket.value = point.value;
    } else {
      bucket.value = (bucket.value * bucket.sourceCount + point.value) / (bucket.sourceCount + 1);
    }
    bucket.timestamp = point.timestamp;
    bucket.synthetic = false;
    bucket.sourceCount += 1;
  }

  return buckets.map(({ sourceCount: _sourceCount, ...point }) => point);
}

function sampleChartPoints(points: ChartPoint[], maxPoints: number): RenderChartPoint[] {
  if (points.length <= maxPoints) {
    return points;
  }

  const lastIndex = points.length - 1;
  const maxIndex = points.reduce((currentIndex, point, index) => (point.value > points[currentIndex].value ? index : currentIndex), 0);
  const indexes = new Set<number>([0, lastIndex, maxIndex]);
  for (let index = 1; index < maxPoints - 1; index += 1) {
    indexes.add(Math.round((index / (maxPoints - 1)) * lastIndex));
  }

  return [...indexes].sort((first, second) => first - second).map((sourceIndex) => points[sourceIndex]);
}

function metricChartRangeTicks(timeRange: { from: string; to: string } | null | undefined, left: number, right: number) {
  if (!timeRange) {
    return [];
  }
  const fromMs = new Date(timeRange.from).getTime();
  const toMs = new Date(timeRange.to).getTime();
  if (!Number.isFinite(fromMs) || !Number.isFinite(toMs) || fromMs >= toMs) {
    return [];
  }
  return [
    { timestamp: new Date(fromMs).toISOString(), x: left },
    { timestamp: new Date(fromMs + (toMs - fromMs) / 2).toISOString(), x: left + (right - left) / 2 },
    { timestamp: new Date(toMs).toISOString(), x: right },
  ];
}

function formatMetricChartTimeLabel(timeRange: { from: string; to: string } | null | undefined, timestamp: string) {
  if (!timeRange) {
    return [formatShortTime(timestamp)];
  }
  const fromMs = new Date(timeRange.from).getTime();
  const toMs = new Date(timeRange.to).getTime();
  const value = new Date(timestamp);
  if (Number.isFinite(toMs - fromMs) && toMs - fromMs >= 24 * 60 * 60 * 1000) {
    return [
      value.toLocaleDateString([], { month: "short", day: "numeric" }),
      value.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" }),
    ];
  }
  return [formatShortTime(timestamp)];
}
