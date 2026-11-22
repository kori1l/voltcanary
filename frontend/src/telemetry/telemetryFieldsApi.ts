import { apiRequest } from "../api/client";
export interface TelemetryField {
  key: string;
  label: string;
  type: "number" | "string" | "boolean";
  unit: string;
  value: number | string | boolean;
  display: boolean;
  sourceId: string;
  recordedAt: string;
}
export function getTelemetryFields(deviceId: string, sourceId?: string, signal?: AbortSignal) {
  return apiRequest<TelemetryField[]>(
    `/telemetry/devices/${deviceId}/fields${sourceId ? `?${new URLSearchParams({ sourceId })}` : ""}`,
    { signal },
  );
}
