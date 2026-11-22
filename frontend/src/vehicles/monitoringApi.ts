import { apiRequest } from "../api/client";
import type { LatestPosition, PositionPoint } from "./positionApi";
import type { MonitoringSettings } from "./monitoringModel";
export interface VehicleTarget {
  deviceId: string;
  sourceId: string;
}
export const fleetPositions = (targets: VehicleTarget[], signal: AbortSignal) =>
  apiRequest<LatestPosition[]>("/telemetry/vehicles/positions", {
    method: "POST",
    body: JSON.stringify({ targets, asOf: new Date().toISOString() }),
    signal,
  });
export const readMonitoring = (device: string, signal: AbortSignal) =>
  apiRequest<MonitoringSettings>(`/telemetry/devices/${device}/vehicle-monitoring`, { signal });
export interface MonitoringSnapshot {
  revision: number;
  digest: string;
  settings: MonitoringSettings;
}
export interface MonitoringReview {
  id: string;
  deviceId: string;
  baseRevision: number;
  before: MonitoringSettings;
  after: MonitoringSettings;
  digest: string;
  idempotencyKey: string;
  expiresAt: string;
  status: "PREPARED" | "SUCCEEDED" | "EXPIRED";
  appliedRevision: number | null;
}
const monitoringPath = (device: string) =>
  `/telemetry/devices/${encodeURIComponent(device)}/vehicle-monitoring`;
export const readMonitoringSnapshot = (device: string, signal?: AbortSignal) =>
  apiRequest<MonitoringSnapshot>(`${monitoringPath(device)}/snapshot`, { signal });
export const prepareMonitoring = (
  device: string,
  expectedRevision: number,
  settings: MonitoringSettings,
  key: string,
) =>
  apiRequest<MonitoringReview>(`${monitoringPath(device)}/reviews`, {
    method: "POST",
    body: JSON.stringify({ expectedRevision, settings, idempotencyKey: key }),
  });
export const inspectMonitoring = (device: string, review: string, signal?: AbortSignal) =>
  apiRequest<MonitoringReview>(`${monitoringPath(device)}/reviews/${encodeURIComponent(review)}`, {
    signal,
  });
export const confirmMonitoring = (device: string, review: MonitoringReview) =>
  apiRequest<MonitoringReview>(
    `${monitoringPath(device)}/reviews/${encodeURIComponent(review.id)}/confirm`,
    {
      method: "POST",
      body: JSON.stringify({ digest: review.digest, idempotencyKey: review.idempotencyKey }),
    },
  );
interface RecordingPage {
  points: PositionPoint[];
  nextCursor: string | null;
  hasMore: boolean;
}
export async function recordedPositions(
  target: VehicleTarget,
  from: string,
  to: string,
  signal: AbortSignal,
  onProgress: (count: number) => void,
) {
  const points: PositionPoint[] = [];
  let cursor: string | undefined;
  for (let page = 0; page < 10; page++) {
    const params = new URLSearchParams({
      sourceId: target.sourceId,
      from,
      to,
      limit: "2000",
      ...(cursor ? { cursor } : {}),
    });
    const result = await apiRequest<RecordingPage>(
      `/telemetry/devices/${target.deviceId}/positions/recordings?${params}`,
      { signal },
    );
    points.push(...result.points);
    onProgress(points.length);
    if (!result.hasMore) return { points, truncated: false };
    if (!result.nextCursor || result.nextCursor === cursor)
      throw new Error("Invalid recording cursor; reload the selected range.");
    cursor = result.nextCursor;
  }
  return { points, truncated: true };
}
