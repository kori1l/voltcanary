import { apiRequest } from "../api/client";

export function getLatestTelemetry(deviceId: string) {
  return apiRequest(`/telemetry/devices/${deviceId}/latest`);
}
