import type { PositionPoint } from "./positionApi";

export function positionSegments(points: PositionPoint[]): [number, number][][] {
  const segments: [number, number][][] = [];
  let current: [number, number][] = [];
  for (let i = 0; i < points.length; i++) {
    const point = points[i],
      previous = points[i - 1];
    if (previous) {
      const seconds = (Date.parse(point.recordedAt) - Date.parse(previous.recordedAt)) / 1000;
      const lat1 = (previous.latitude * Math.PI) / 180,
        lat2 = (point.latitude * Math.PI) / 180;
      const h =
        Math.sin((lat2 - lat1) / 2) ** 2 +
        Math.cos(lat1) *
          Math.cos(lat2) *
          Math.sin(((point.longitude - previous.longitude) * Math.PI) / 360) ** 2;
      const metres =
        6371000 * 2 * Math.atan2(Math.sqrt(Math.min(1, h)), Math.sqrt(Math.max(0, 1 - h)));
      if (seconds > 30 || seconds <= 0 || (metres / seconds) * 3.6 > 300) {
        if (current.length > 1) segments.push(current);
        current = [];
      }
    }
    current.push([point.longitude, point.latitude]);
  }
  if (current.length > 1) segments.push(current);
  return segments;
}
