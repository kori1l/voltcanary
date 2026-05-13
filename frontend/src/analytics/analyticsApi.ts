import { apiRequest } from "../api/client";

export function getDeviceRisk(deviceId: string) {
  return apiRequest(`/analytics/devices/${deviceId}/risk`);
}
