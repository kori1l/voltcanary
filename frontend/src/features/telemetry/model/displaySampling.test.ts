import { expect, it } from "vitest";
import { sampleDisplayEvidence } from "./displaySampling";
import type { TelemetrySample } from "./telemetry.types";
const samples = (count: number): TelemetrySample[] => Array.from({ length: count }, (_, index) => ({ metricKey: "temperatureC", timestamp: new Date(Date.UTC(2026, 9, 8) + index * 1000).toISOString(), value: 0 }));
it("retains separate peaks, a trough, endpoints and measured zeros within budget", () => {
  const series = samples(1000); series[131].value = 90; series[417].value = -20; series[802].value = 100;
  const result = sampleDisplayEvidence(series, 90);
  expect(result.mode).toBe("Measured points");
  expect(result.points.map((point) => point.value)).toEqual(expect.arrayContaining([90, -20, 100, 0]));
  expect(result.points[0]).toBe(series[0]); expect(result.points[result.points.length - 1]).toBe(series[999]);
  expect(result.displayCount).toBeLessThanOrEqual(90); expect(result.rawCount).toBe(1000);
});
it("preserves missing intervals and long gaps without inventing zero samples", () => {
  const series = samples(20); series[5].value = null;
  for (let index = 10; index < 20; index++) series[index].timestamp = new Date(Date.parse(series[index].timestamp) + 600_000).toISOString();
  const result = sampleDisplayEvidence(series, 90);
  expect(result.gapCount).toBe(2);
  expect(result.points.filter((point) => point.value === null)).toHaveLength(2);
  expect(result.points.filter((point) => point.value === 0)).toHaveLength(19);
});
it("explicitly represents excessive crossings as extrema envelopes with no connecting curve", () => {
  const series = samples(1000).map((sample, index) => ({ ...sample, value: index % 2 ? 20 : -10 }));
  const result = sampleDisplayEvidence(series, 50, { metricKey: "temperatureC", criticalMin: 0 });
  expect(result.mode).toBe("Min/max envelope"); expect(result.points).toEqual([]);
  expect(result.crossingCount).toBe(999); expect(result.displayCount).toBeLessThanOrEqual(50);
  expect(result.buckets).toHaveLength(25);
  expect(result.buckets.every((bucket) => bucket.min === -10 && bucket.max === 20)).toBe(true);
});
it("merges every server bucket and retains authoritative counts rather than reducing KPIs from extrema", () => {
  const server = Array.from({ length: 100 }, (_, index) => ({ from: String(index), to: String(index + 1), count: 1000, min: -index, max: index, minAt: "", maxAt: "", firstAt: "", firstValue: 0, lastAt: "", lastValue: 0, gapCount: index === 70 ? 1 : 0 }));
  const result = sampleDisplayEvidence([], 50, undefined, server, 100000);
  expect(result.rawCount).toBe(100000); expect(result.displayCount).toBe(50);
  expect(result.buckets.reduce((sum, bucket) => sum + bucket.count, 0)).toBe(100000);
  expect(result.gapCount).toBe(1); expect(result.crossingCount).toBeNull();
  expect(result.buckets[result.buckets.length - 1]).toMatchObject({ min: -99, max: 99 });
});
