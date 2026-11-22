import type { FeatureCollection, LineString } from "geojson";
import type { PositionHistory } from "./positionApi";
import { positionSegments } from "./positionGeometry";

const colors = [
  "#2563eb",
  "#e11d48",
  "#0891b2",
  "#d97706",
  "#16a34a",
  "#9333ea",
  "#db2777",
  "#0d9488",
  "#4f46e5",
  "#ea580c",
];
export function fleetTrackGeometry(
  tracks: PositionHistory[],
  selected: string,
): FeatureCollection<LineString> {
  const features: FeatureCollection<LineString>["features"] = [];
  tracks.forEach((track, index) => {
    const active = track.deviceId === selected,
      color = active ? "#7c3aed" : colors[index % colors.length];
    const properties = {
      deviceId: track.deviceId,
      color,
      order: active ? 1 : 0,
      width: active ? 5 : 3,
      opacity: active || !selected ? 0.9 : 0.5,
    };
    positionSegments(track.points).forEach((coordinates) =>
      features.push({
        type: "Feature",
        properties: { ...properties, kind: "observed" },
        geometry: { type: "LineString", coordinates },
      }),
    );
    // A faint straight connector denotes missing GPS evidence; never a recorded route.
    for (let i = 1; i < track.points.length; i++) {
      const before = track.points[i - 1],
        after = track.points[i];
      if (
        Date.parse(after.recordedAt) > Date.parse(before.recordedAt) &&
        positionSegments([before, after]).length === 0
      ) {
        features.push({
          type: "Feature",
          properties: { ...properties, kind: "gap" },
          geometry: {
            type: "LineString",
            coordinates: [
              [before.longitude, before.latitude],
              [after.longitude, after.latitude],
            ],
          },
        });
      }
    }
  });
  return { type: "FeatureCollection", features };
}
