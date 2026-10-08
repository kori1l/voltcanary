import { expect, it } from "vitest";
import { mapTelemetryOverview, mapMetricDetail } from "./telemetry.mappers";
it("uses full-range server reducers and keeps sample quality separate from display extrema", () => {
  const timestamp = "2026-10-07T12:00:00Z";
  const overview = mapTelemetryOverview({ deviceId: "device", selectedMetricKeys: ["temperatureC"], latestTimestamp: "2026-10-08T12:00:00Z", range: { from: "2026-10-01T12:00:00Z", to: "2026-10-08T12:00:00Z", preset: "7d" }, charts: { temperatureC: { deviceId: "device", metric: "temperatureC", unit: "C", aggregationMode: "MIN_MAX_ENVELOPE", rawPointCount: 2400, points: [{ timestamp, value: -20 }, { timestamp, value: 100 }], qualityCounts: { BACKFILL: 2400 }, buckets: [], summary: { count: 2400, min: -20, max: 100, average: 170 / 2400, p95: 0, p99: 0, minAt: timestamp, maxAt: timestamp, lastAt: timestamp, lastValue: 0, gapCount: 1 } } } });
  const detail = mapMetricDetail(overview, "temperatureC")!;
  expect(detail.statistics).toMatchObject({ samplesCount: 2400, latest: 0, avg: 170 / 2400, min: -20, max: 100, p95: 0 });
  expect(detail.series.every((sample) => sample.quality === undefined)).toBe(true);
  expect(detail.events).toEqual([]);
  expect(detail.evidence?.qualityCounts).toEqual({ BACKFILL: 2400 });
});
