import { TelemetrySample } from "./telemetry.types";
/** A gap is explicit missing data or more than three typical sampling intervals. */
export function splitSampleSegments(series: TelemetrySample[]) {
  const intervals = series.slice(1).map((sample, index) => Date.parse(sample.timestamp) - Date.parse(series[index].timestamp)).filter((value) => value > 0).sort((a, b) => a - b);
  const typical = intervals.length ? intervals[Math.floor((intervals.length - 1) / 2)] : 60_000;
  const limit = Math.max(typical * 3, 60_000);
  const segments: TelemetrySample[][] = [];
  let segment: TelemetrySample[] = [];
  for (const sample of series) {
    if (sample.value === null || !Number.isFinite(sample.value) || !Number.isFinite(Date.parse(sample.timestamp))) {
      if (segment.length) segments.push(segment); segment = []; continue;
    }
    if (segment.length && Date.parse(sample.timestamp) - Date.parse(segment[segment.length - 1].timestamp) > limit) { segments.push(segment); segment = []; }
    segment.push(sample);
  }
  if (segment.length) segments.push(segment);
  return segments;
}
