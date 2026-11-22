import { it, expect } from "vitest";
import { fleetTrackGeometry } from "./fleetTrackGeometry";
import type { PositionHistory, PositionPoint } from "./positionApi";
it("keeps recorded paths distinct from faint straight gap connectors and highlights the selected vehicle", () => {
  const points = [0, 5, 90, 95].map(
    (seconds, index) =>
      ({
        latitude: 60.18,
        longitude: 24.92 + index * 0.0001,
        recordedAt: new Date(Date.UTC(2026, 0, 1) + seconds * 1000).toISOString(),
      }) as PositionPoint,
  );
  const history = { deviceId: "tram", points } as PositionHistory;
  const geometry = fleetTrackGeometry([history], "tram");
  expect(
    geometry.features.filter((feature) => feature.properties?.kind === "observed"),
  ).toHaveLength(2);
  const gaps = geometry.features.filter((feature) => feature.properties?.kind === "gap");
  expect(gaps).toHaveLength(1);
  expect(gaps[0].geometry.coordinates).toEqual([
    [points[1].longitude, 60.18],
    [points[2].longitude, 60.18],
  ]);
  expect(gaps[0].properties?.color).toBe("#7c3aed");
  expect(fleetTrackGeometry([{ ...history, points: [] }], "").features).toHaveLength(0);
});
