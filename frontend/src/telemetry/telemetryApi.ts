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

export function getLatestTelemetry(deviceId: string) {
  return apiRequest<LatestTelemetry>(`/telemetry/devices/${deviceId}/latest`);
}

export function getTelemetryReadings(deviceId: string, metric: string, from?: string, to?: string) {
  const rangeTo = to ?? new Date().toISOString();
  const rangeFrom = from ?? new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString();
  return apiRequest<TelemetryChart>(
    `/telemetry/devices/${deviceId}/readings?metric=${encodeURIComponent(metric)}&from=${encodeURIComponent(rangeFrom)}&to=${encodeURIComponent(rangeTo)}`,
  );
}
