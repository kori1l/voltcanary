import { apiRequest } from "../api/client";

export interface DeviceSetupStatus {
  deviceId: string;
  sourceId: string;
  setupStatus: "WAITING_FOR_DATA" | "TELEMETRY_READY";
  freshness: string;
}

export function readDeviceSetup(deviceId: string, signal: AbortSignal) {
  return apiRequest<DeviceSetupStatus>(`/ai-agent/device-setups/${encodeURIComponent(deviceId)}`, {
    signal,
  });
}
