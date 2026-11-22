import type { LatestPosition, PositionPoint } from "./positionApi";

export type MovementState = "MOVING" | "PARKED" | "STALE" | "NO_DATA" | "UNKNOWN";
export interface VehicleZone {
  id: string;
  name: string;
  latitude: number;
  longitude: number;
  radiusMetres: number;
}
export interface MonitoringSettings {
  speedLimitKmh: number;
  idleMinutes: number;
  zones: VehicleZone[];
}
export const defaultMonitoring: MonitoringSettings = {
  speedLimitKmh: 80,
  idleMinutes: 2,
  zones: [],
};
export function movementState(latest: LatestPosition | undefined): MovementState {
  if (!latest?.position) return "NO_DATA";
  if (latest.freshness !== "FRESH") return "STALE";
  if (latest.position.speedKmh === null) return "UNKNOWN";
  return latest.position.speedKmh > 3 ? "MOVING" : "PARKED";
}
export function metres(
  a: Pick<PositionPoint, "latitude" | "longitude">,
  b: Pick<PositionPoint, "latitude" | "longitude">,
) {
  const radians = Math.PI / 180,
    dlat = (b.latitude - a.latitude) * radians,
    dlon = (b.longitude - a.longitude) * radians;
  const h =
    Math.sin(dlat / 2) ** 2 +
    Math.cos(a.latitude * radians) * Math.cos(b.latitude * radians) * Math.sin(dlon / 2) ** 2;
  return 12742000 * Math.atan2(Math.sqrt(Math.min(1, h)), Math.sqrt(Math.max(0, 1 - h)));
}
export function continuous(a: PositionPoint, b: PositionPoint) {
  const seconds = (Date.parse(b.recordedAt) - Date.parse(a.recordedAt)) / 1000;
  return seconds > 0 && seconds <= 60 && (metres(a, b) / seconds) * 3.6 <= 300;
}
export interface ObservedTrip {
  startIndex: number;
  endIndex: number;
  distanceKm: number;
  elapsedSeconds: number;
  movingSeconds: number;
  maxSpeedKmh: number | null;
  averageSpeedKmh: number;
  incomplete: boolean;
}
export function observedTrips(points: PositionPoint[], idleMinutes: number): ObservedTrip[] {
  const trips: ObservedTrip[] = [];
  let start = -1,
    idle = -1,
    distance = 0,
    moving = 0,
    max: number | null = null,
    incomplete = false;
  function finish(end: number, uncertain: boolean) {
    if (start < 0) return;
    const elapsed = Math.max(
      0,
      (Date.parse(points[end].recordedAt) - Date.parse(points[start].recordedAt)) / 1000,
    );
    trips.push({
      startIndex: start,
      endIndex: end,
      distanceKm: distance / 1000,
      elapsedSeconds: elapsed,
      movingSeconds: moving,
      maxSpeedKmh: max,
      averageSpeedKmh: elapsed ? (distance / elapsed) * 3.6 : 0,
      incomplete: incomplete || uncertain,
    });
    start = -1;
    idle = -1;
    distance = 0;
    moving = 0;
    max = null;
  }
  for (let i = 0; i < points.length; i++) {
    const point = points[i],
      previous = points[i - 1],
      good = !!previous && continuous(previous, point);
    if (previous && !good) finish(i - 1, true);
    const isMoving =
      point.speedKmh !== null ? point.speedKmh > 3 : good && metres(previous, point) > 1;
    if (isMoving && start < 0) {
      start = good ? i - 1 : i;
      incomplete = !good;
    }
    if (start < 0) continue;
    if (good) {
      distance += metres(previous, point);
      if (isMoving)
        moving += (Date.parse(point.recordedAt) - Date.parse(previous.recordedAt)) / 1000;
    }
    if (point.speedKmh !== null) max = Math.max(max ?? 0, point.speedKmh);
    if (isMoving) idle = -1;
    else if (idle < 0) idle = i;
    else if (
      Date.parse(point.recordedAt) - Date.parse(points[idle].recordedAt) >=
      idleMinutes * 60000
    )
      finish(i, false);
  }
  finish(points.length - 1, true);
  return trips;
}
export interface VehicleEvent {
  type: "ENTER" | "EXIT" | "OVERSPEED";
  recordedAt: string;
  label: string;
  pointIndex: number;
}
export function monitoringEvents(
  points: PositionPoint[],
  settings: MonitoringSettings,
): VehicleEvent[] {
  const events: VehicleEvent[] = [];
  let inside = new Map<string, boolean>(),
    over: boolean | undefined;
  points.forEach((point, i) => {
    if (i === 0 || !continuous(points[i - 1], point)) {
      inside = new Map();
      over = undefined;
    }
    for (const zone of settings.zones) {
      const current = metres(point, zone) <= zone.radiusMetres,
        previous = inside.get(zone.id);
      if (previous !== undefined && previous !== current)
        events.push({
          type: current ? "ENTER" : "EXIT",
          recordedAt: point.recordedAt,
          label: zone.name,
          pointIndex: i,
        });
      inside.set(zone.id, current);
    }
    if (point.speedKmh !== null) {
      const current = point.speedKmh > settings.speedLimitKmh;
      if (current && over === false)
        events.push({
          type: "OVERSPEED",
          recordedAt: point.recordedAt,
          label: `${point.speedKmh.toFixed(1)} km/h > ${settings.speedLimitKmh}`,
          pointIndex: i,
        });
      over = current;
    } else over = undefined;
  });
  return events;
}
export function positionsCsv(points: PositionPoint[]) {
  const columns = [
    "recordedAt",
    "latitude",
    "longitude",
    "speedKmh",
    "headingDeg",
    "ignition",
    "fuelPercent",
    "odometerKm",
  ] as const;
  return [
    columns.join(","),
    ...points.map((point) => columns.map((key) => point[key] ?? "").join(",")),
  ].join("\r\n");
}
