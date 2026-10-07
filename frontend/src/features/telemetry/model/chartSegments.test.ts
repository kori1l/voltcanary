import { expect, it } from "vitest";
import { splitSampleSegments } from "./chartSegments";
it("keeps missing values and long gaps separate from measured zero", () => {
  const points = [0, 1, 2, 20, 21].map((minute, index) => ({ metricKey: "activePowerW", timestamp: new Date(minute * 60_000).toISOString(), value: index === 1 ? null : index }));
  const segments = splitSampleSegments(points);
  expect(segments.map((segment) => segment.map((sample) => sample.value))).toEqual([[0], [2], [3, 4]]);
});
