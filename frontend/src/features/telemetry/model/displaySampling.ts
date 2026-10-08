import type { TelemetryEnvelopeBucket } from "../../../telemetry/telemetryApi";
import { splitSampleSegments } from "./chartSegments";
import type { MetricThreshold, TelemetrySample } from "./telemetry.types";
export interface DisplayBucket { from: string; to: string; min: number; max: number; count: number; gapCount: number }
export interface DisplayEvidence { mode: "Measured points" | "Min/max envelope"; points: TelemetrySample[]; buckets: DisplayBucket[]; rawCount: number; displayCount: number; crossingCount: number | null; gapCount: number }
export function mergeEnvelopeBuckets(input: DisplayBucket[], budget: number): DisplayBucket[] {
  const size = Math.max(1, Math.ceil(input.length / Math.max(1, Math.floor(budget / 2))));
  const result: DisplayBucket[] = [];
  for (let index = 0; index < input.length; index += size) {
    const group = input.slice(index, index + size);
    result.push({ from: group[0].from, to: group[group.length - 1].to, min: Math.min(...group.map((bucket) => bucket.min)), max: Math.max(...group.map((bucket) => bucket.max)), count: group.reduce((sum, bucket) => sum + bucket.count, 0), gapCount: group.reduce((sum, bucket) => sum + bucket.gapCount, 0) });
  }
  return result;
}
/** The line mode preserves every crossing and gap; excessive evidence becomes explicit bars. */
export function sampleDisplayEvidence(input: TelemetrySample[], budget: number, threshold?: MetricThreshold, serverBuckets?: TelemetryEnvelopeBucket[], serverRawCount?: number): DisplayEvidence {
  const ordered = [...input].filter((sample) => Number.isFinite(Date.parse(sample.timestamp))).sort((a, b) => Date.parse(a.timestamp) - Date.parse(b.timestamp));
  const finite = ordered.filter((sample) => sample.value !== null && Number.isFinite(sample.value));
  const segments = splitSampleSegments(ordered);
  const missingRuns = ordered.filter((sample, index) => (sample.value === null || !Number.isFinite(sample.value)) && (index === 0 || ordered[index - 1].value !== null && Number.isFinite(ordered[index - 1].value))).length;
  const gaps = Math.max(Math.max(0, segments.length - 1), missingRuns);
  const thresholds = Object.entries(threshold ?? {}).filter(([key, value]) => key !== "metricKey" && typeof value === "number" && Number.isFinite(value)).map(([, value]) => value as number);
  const critical = new Set<TelemetrySample>(); let crossingCount = 0;
  for (const segment of segments) {
    critical.add(segment[0]); critical.add(segment[segment.length - 1]);
    for (let index = 1; index < segment.length; index++) {
      if (thresholds.some((limit) => (segment[index - 1].value! <= limit) !== (segment[index].value! <= limit))) { crossingCount++; critical.add(segment[index - 1]); critical.add(segment[index]); }
    }
  }
  const maxPoints = Math.max(2, Math.floor(budget));
  if (serverBuckets) {
    const buckets = mergeEnvelopeBuckets(serverBuckets, maxPoints);
    return { mode: "Min/max envelope", points: [], buckets, rawCount: serverRawCount ?? serverBuckets.reduce((sum, bucket) => sum + bucket.count, 0), displayCount: buckets.length * 2, crossingCount: null, gapCount: serverBuckets.reduce((sum, bucket) => sum + bucket.gapCount, 0) };
  }
  const groupSize = Math.max(1, Math.ceil(finite.length / Math.max(1, Math.floor((maxPoints - critical.size - gaps) / 2))));
  const buckets: DisplayBucket[] = [];
  for (let index = 0; index < finite.length; index += groupSize) {
    const group = finite.slice(index, index + groupSize);
    const low = group.reduce((a, b) => a.value! <= b.value! ? a : b), high = group.reduce((a, b) => a.value! >= b.value! ? a : b);
    critical.add(low); critical.add(high);
    buckets.push({ from: group[0].timestamp, to: group[group.length - 1].timestamp, min: low.value!, max: high.value!, count: group.length, gapCount: 0 });
  }
  if (finite.length <= maxPoints && ordered.length <= maxPoints) finite.forEach((sample) => critical.add(sample));
  const points = segments.flatMap((segment, index) => [...(index ? [{ ...segment[0], value: null }] : []), ...segment.filter((sample) => critical.has(sample))]);
  if (points.length <= maxPoints) return { mode: "Measured points", points, buckets: [], rawCount: ordered.length, displayCount: points.length, crossingCount, gapCount: gaps };
  const fallback: DisplayBucket[] = [];
  const fallbackSize = Math.max(1, Math.ceil(finite.length / Math.max(1, Math.floor(maxPoints / 2))));
  for (let index = 0; index < finite.length; index += fallbackSize) {
    const group = finite.slice(index, index + fallbackSize);
    fallback.push({ from: group[0].timestamp, to: group[group.length - 1].timestamp, min: Math.min(...group.map((sample) => sample.value!)), max: Math.max(...group.map((sample) => sample.value!)), count: group.length, gapCount: index === 0 ? gaps : 0 });
  }
  const envelope = mergeEnvelopeBuckets(fallback, maxPoints);
  return { mode: "Min/max envelope", points: [], buckets: envelope, rawCount: ordered.length, displayCount: envelope.length * 2, crossingCount, gapCount: gaps };
}
