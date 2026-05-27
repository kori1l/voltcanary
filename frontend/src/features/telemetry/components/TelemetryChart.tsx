import { useState } from "react";
import { MetricThreshold, TelemetryEvent, TelemetrySample } from "../model/telemetry.types";
import { formatCompactNumber, formatMetricValue, formatShortTime } from "../model/telemetry.utils";

type SampledPoint = { sample: TelemetrySample; sourceIndex: number };

export function TelemetryChart({
  series,
  label,
  unit,
  threshold,
  events,
  showPoints,
  maxDisplayPoints,
  mode = "compact",
}: {
  series: TelemetrySample[];
  label: string;
  unit: string;
  threshold?: MetricThreshold;
  events?: TelemetryEvent[];
  showPoints: boolean;
  maxDisplayPoints: number;
  mode?: "compact" | "detailed";
}) {
  const [hovered, setHovered] = useState<{ sample: TelemetrySample; x: number; y: number; event?: TelemetryEvent } | null>(null);
  const values = series.map((sample) => sample.value).filter((value): value is number => value !== null && Number.isFinite(value));

  if (values.length === 0) {
    return (
      <div className={`chart-wrap annotated-chart chart-${mode === "detailed" ? "large" : "normal"}`}>
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
  const thresholdValues = [
    threshold?.normalMin,
    threshold?.normalMax,
    threshold?.warningMin,
    threshold?.warningMax,
    threshold?.criticalMin,
    threshold?.criticalMax,
    threshold?.spikeThreshold,
  ].filter((value): value is number => value !== undefined);
  const allValues = [...values, ...thresholdValues];
  const rawMin = Math.min(...allValues);
  const rawMax = Math.max(...allValues);
  const rawRange = rawMax - rawMin || Math.max(Math.abs(rawMax), 1);
  const paddingValue = rawRange * 0.08;
  const includeZero = ["W", "Wh", "A"].includes(unit) && rawMin >= 0;
  const actualMax = Math.max(...values);
  const warningReference = threshold?.warningMax ?? threshold?.spikeThreshold ?? threshold?.criticalMax;
  const minValue = includeZero ? 0 : rawMin - paddingValue;
  const maxValue = unit === "W"
    ? Math.max(actualMax * 1.15, warningReference !== undefined ? warningReference * 1.3 : actualMax * 1.15, 1)
    : rawMax + paddingValue;
  const range = maxValue - minValue || 1;
  const sampled = sampleSeries(series, maxDisplayPoints);
  const yForValue = (value: number) => height - padding.bottom - ((value - minValue) / range) * (height - padding.top - padding.bottom);
  const xForIndex = (index: number) =>
    padding.left + (index / Math.max(series.length - 1, 1)) * (width - padding.left - padding.right);
  const coordinates = sampled
    .filter((item) => item.sample.value !== null)
    .map(({ sample, sourceIndex }) => ({
      sample,
      x: xForIndex(sourceIndex),
      y: yForValue(sample.value as number),
    }));
  const eventCoordinates = (events ?? []).map((event) => ({
    event,
    x: xForTimestamp(series, event.timestamp, padding.left, width - padding.right),
  }));
  const maxPoint = coordinates.reduce((current, next) => ((next.sample.value ?? 0) > (current.sample.value ?? 0) ? next : current), coordinates[0]);
  const visiblePoints = showPoints ? coordinates : maxPoint && isAboveThreshold(maxPoint.sample.value, threshold) ? [maxPoint] : [];
  const yTicks = [minValue, minValue + range / 2, maxValue];
  const xTicks = [coordinates[0], coordinates[Math.floor(coordinates.length / 2)], coordinates[coordinates.length - 1]].filter(Boolean);

  return (
    <div className={`chart-wrap annotated-chart chart-${mode === "detailed" ? "large" : "normal"}`}>
      <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label={`${label} chart`}>
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

        {thresholdValues.map((value) => (
          <line
            key={`threshold-${value}`}
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

        <polyline points={coordinates.map((coordinate) => `${coordinate.x},${coordinate.y}`).join(" ")} />

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

        {xTicks.map((tick) => (
          <text key={tick.sample.timestamp} className="chart-axis-label" x={tick.x} y={height - 20} textAnchor="middle">
            {formatShortTime(tick.sample.timestamp)}
          </text>
        ))}
      </svg>
      {hovered ? (
        <div className="chart-tooltip" style={{ left: `${(hovered.x / width) * 100}%`, top: `${(hovered.y / height) * 100}%` }}>
          <strong>{formatShortTime(hovered.sample.timestamp)}</strong>
          <span>{formatMetricValue(hovered.sample.value, unit)}</span>
          {isAboveThreshold(hovered.sample.value, threshold) ? <em>Outside threshold</em> : null}
          {hovered.event ? <em>{hovered.event.title}</em> : null}
        </div>
      ) : null}
    </div>
  );
}

function sampleSeries(series: TelemetrySample[], maxPoints: number): SampledPoint[] {
  if (series.length <= maxPoints) {
    return series.map((sample, sourceIndex) => ({ sample, sourceIndex }));
  }
  const lastIndex = series.length - 1;
  const values = series.map((sample) => sample.value ?? Number.NEGATIVE_INFINITY);
  const maxIndex = values.reduce((currentIndex, value, index) => (value > values[currentIndex] ? index : currentIndex), 0);
  const indexes = new Set<number>([0, lastIndex, maxIndex]);
  for (let index = 1; index < maxPoints - 1; index += 1) {
    indexes.add(Math.round((index / (maxPoints - 1)) * lastIndex));
  }
  return [...indexes].sort((first, second) => first - second).map((sourceIndex) => ({ sample: series[sourceIndex], sourceIndex }));
}

function xForTimestamp(series: TelemetrySample[], timestamp: string, left: number, right: number) {
  const first = new Date(series[0]?.timestamp ?? timestamp).getTime();
  const last = new Date(series[series.length - 1]?.timestamp ?? timestamp).getTime();
  const current = new Date(timestamp).getTime();
  if (last === first) return left;
  return left + ((current - first) / (last - first)) * (right - left);
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
