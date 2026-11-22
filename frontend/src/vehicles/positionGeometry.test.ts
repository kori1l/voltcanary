import { describe, it, expect } from "vitest";
import { positionSegments } from "./positionGeometry";
import type { PositionPoint } from "./positionApi";
const point = (seconds: number, longitude: number): PositionPoint => ({
  recordedAt: new Date(seconds * 1000).toISOString(),
  receivedAt: new Date(seconds * 1000).toISOString(),
  latitude: 44.8,
  longitude,
  speedKmh: 36,
  headingDeg: 90,
  quality: ["OK"],
});
describe("observed GPS trails", () => {
  it("uses longitude first and preserves short observed segments", () => {
    expect(positionSegments([point(0, 20.4), point(5, 20.4005)])).toEqual([
      [
        [20.4, 44.8],
        [20.4005, 44.8],
      ],
    ]);
  });
  it("breaks missing observations and impossible restart jumps", () => {
    expect(
      positionSegments([
        point(0, 20.4),
        point(5, 20.4005),
        point(40, 20.401),
        point(45, 20.4015),
        point(50, 22.4),
      ]),
    ).toHaveLength(2);
    expect(positionSegments([point(0, 20.4)])).toEqual([]);
  });
});
