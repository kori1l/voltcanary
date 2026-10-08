import { apiRequest } from "../api/client";

export interface LatestTelemetry {
  deviceId: string;
  readings: Record<string, number>;
  timestamp: string;
}

export interface ChartPoint {
  timestamp: string;
  value: number;
  sourceId?: string;
  quality?: string;
}

export interface TelemetryChart {
  deviceId: string;
  metric: string;
  points: ChartPoint[];
  unit?: string;
  rawPointCount?: number;
  aggregationMode?: "MIN_MAX_ENVELOPE";
  buckets?: TelemetryEnvelopeBucket[];
  summary?: TelemetryRangeSummary;
  qualityCounts?: Record<string, number>;
}
export interface TelemetryEnvelopeBucket { from: string; to: string; count: number; min: number; max: number; minAt: string; maxAt: string; firstAt: string; firstValue: number; lastAt: string; lastValue: number; gapCount: number }
export interface TelemetryRangeSummary { count: number; min: number | null; max: number | null; average: number | null; p95: number | null; p99: number | null; minAt: string | null; maxAt: string | null; lastAt: string | null; lastValue: number | null; gapCount: number }
export interface TelemetryMetricMetadata { name: string; unit: string; type: string; min: number; max: number; displayName: string }
export interface TelemetryExportPage { deviceId: string; metric: string; points: ChartPoint[]; nextCursor: string | null; nextCursorSourceId?: string | null }
export function getTelemetryMetrics(deviceId: string, from: string, to: string, signal?: AbortSignal) {
  return apiRequest<TelemetryMetricMetadata[]>(`/telemetry/devices/${deviceId}/metrics?${new URLSearchParams({ from, to })}`, { signal });
}
export function getTelemetryEnvelope(deviceId: string, metric: string, from: string, to: string, signal?: AbortSignal) {
  return apiRequest<TelemetryChart>(`/telemetry/devices/${deviceId}/readings/envelope?${new URLSearchParams({ metric, from, to, buckets: "200" })}`, { signal });
}
export function getTelemetryExportPage(deviceId: string, metric: string, from: string, to: string, cursor?: string, signal?: AbortSignal, cursorSourceId?: string) {
  const query = new URLSearchParams({ metric, from, to, limit: "50" }); if (cursor) query.set("cursor", cursor);
  if (cursorSourceId) query.set("cursorSourceId", cursorSourceId);
  return apiRequest<TelemetryExportPage>(`/telemetry/devices/${deviceId}/readings/export?${query}`, { signal });
}

export function getLatestTelemetry(deviceId: string, signal?: AbortSignal) {
  return apiRequest<LatestTelemetry>(`/telemetry/devices/${deviceId}/latest`, { signal });
}

export function getTelemetryReadings(deviceId: string, metric: string, from?: string, to?: string) {
  const rangeTo = to ?? new Date().toISOString();
  const rangeFrom = from ?? new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString();
  return apiRequest<TelemetryChart>(
    `/telemetry/devices/${deviceId}/readings?metric=${encodeURIComponent(metric)}&from=${encodeURIComponent(rangeFrom)}&to=${encodeURIComponent(rangeTo)}`,
  );
}
