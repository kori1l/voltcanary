import { apiRequest } from "../api/client";

export interface AlertResponse {
  id: string;
  deviceId: string;
  accountId?: string | null;
  sourceEventId?: string | null;
  anomalyId?: string | null;
  riskScoreId?: string | null;
  ruleId?: string | null;
  calculationWindowStart?: string | null;
  calculationWindowEnd?: string | null;
  calculationInputHash?: string | null;
  alertType: string;
  title: string;
  description: string;
  severity: string;
  status: "OPEN" | "ACKNOWLEDGED" | "RESOLVED";
  createdAt: string;
  acknowledgedAt?: string | null;
  acknowledgedBy?: string | null;
  resolvedAt?: string | null;
  resolvedBy?: string | null;
  assignedTo?: string | null;
  resolutionReason?: string | null;
}

export interface AlertActionResponse {
  status: string;
  alertId?: string;
  id?: string;
}

export interface AlertFilters {
  deviceId?: string;
  status?: string;
  severity?: string;
  alertType?: string;
  limit?: number;
}

export interface AlertSummaryResponse {
  total: number;
  byStatus: Record<string, number>;
  bySeverity: Record<string, number>;
  byDevice: Record<string, number>;
  generatedAt: string;
}

export interface NotificationAttemptResponse {
  id: string;
  alertId: string;
  channelId: string;
  accountId: string;
  channelType: string;
  status: "PENDING" | "SENT" | "FAILED" | "RETRYING" | "DISABLED";
  retryCount: number;
  lastErrorCode?: string | null;
  providerResponse?: string | null;
  createdAt: string;
  sentAt?: string | null;
}

export interface NotificationChannelResponse {
  id: string;
  accountId: string;
  name: string;
  channelType: string;
  config: Record<string, unknown>;
  credentialsRef?: string | null;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface NotificationChannelRequest {
  name: string;
  channelType: string;
  config: Record<string, unknown>;
  credentialsRef?: string | null;
  enabled: boolean;
}

export function listAlerts(filters: AlertFilters = {}) {
  return apiRequest<AlertResponse[]>(`/alerts${query(filters)}`);
}

export function getAlertSummary(filters: AlertFilters = {}) {
  return apiRequest<AlertSummaryResponse>(`/alerts/summary${query(filters)}`);
}

export function listNotificationAttempts(alertId: string) {
  return apiRequest<NotificationAttemptResponse[]>(`/alerts/${alertId}/notification-attempts`);
}

export function listNotificationChannels() {
  return apiRequest<NotificationChannelResponse[]>("/alerts/notification-channels");
}

export function createNotificationChannel(request: NotificationChannelRequest) {
  return apiRequest<NotificationChannelResponse>("/alerts/notification-channels", {
    method: "POST",
    body: JSON.stringify(request),
  });
}

export function enableNotificationChannel(channelId: string) {
  return apiRequest<NotificationChannelResponse>(`/alerts/notification-channels/${channelId}/enable`, {
    method: "POST",
  });
}

export function disableNotificationChannel(channelId: string) {
  return apiRequest<NotificationChannelResponse>(`/alerts/notification-channels/${channelId}/disable`, {
    method: "POST",
  });
}

export function deleteNotificationChannel(channelId: string) {
  return apiRequest<void>(`/alerts/notification-channels/${channelId}`, {
    method: "DELETE",
  });
}

export function acknowledgeAlert(alertId: string) {
  return apiRequest<AlertActionResponse>(`/alerts/${alertId}/ack`, {
    method: "POST",
  });
}

export function resolveAlert(alertId: string, reason: string) {
  return apiRequest<AlertActionResponse>(`/alerts/${alertId}/resolve`, {
    method: "POST",
    body: JSON.stringify({ reason }),
  });
}

function query(params: Record<string, string | number | undefined>) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== "" && value !== "all") {
      search.set(key, String(value));
    }
  });
  const value = search.toString();
  return value ? `?${value}` : "";
}
