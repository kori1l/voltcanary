import { apiRequest } from "../api/client";

export interface LatestTelemetry {
  deviceId: string;
  readings: Record<string, number>;
  timestamp: string;
}

export interface ChartPoint {
  timestamp: string;
  value: number;
}

export interface TelemetryChart {
  deviceId: string;
  metric: string;
  points: ChartPoint[];
}

export interface SendTelemetryRequest {
  deviceId: string;
  sourceId: string;
  timestamp: string;
  metrics: Record<string, number | string>;
}

export function getLatestTelemetry(deviceId: string) {
  return apiRequest<LatestTelemetry>(`/telemetry/devices/${deviceId}/latest`);
}

export function getTelemetryReadings(deviceId: string, metric: string) {
  const to = new Date().toISOString();
  const from = new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString();
  return apiRequest<TelemetryChart>(
    `/telemetry/devices/${deviceId}/readings?metric=${encodeURIComponent(metric)}&from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
  );
}

export function sendTelemetryReading(request: SendTelemetryRequest) {
  return apiRequest<{ status: string; acceptedCount: number }>("/ingestion/readings", {
    method: "POST",
    body: JSON.stringify(request),
  });
}
