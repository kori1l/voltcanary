import { apiRequest } from "../api/client";

export interface AlertResponse {
  id: string;
  deviceId: string;
  alertType: string;
  title: string;
  description: string;
  severity: string;
  status: "OPEN" | "ACKNOWLEDGED" | "RESOLVED";
  createdAt: string;
  acknowledgedAt?: string | null;
  resolvedAt?: string | null;
}

export interface AlertActionResponse {
  status: string;
  alertId: string;
}

export function listAlerts() {
  return apiRequest<AlertResponse[]>("/alerts");
}

export function acknowledgeAlert(alertId: string) {
  return apiRequest<AlertActionResponse>(`/alerts/${alertId}/ack`, {
    method: "POST",
  });
}

export function resolveAlert(alertId: string) {
  return apiRequest<AlertActionResponse>(`/alerts/${alertId}/resolve`, {
    method: "POST",
  });
}
