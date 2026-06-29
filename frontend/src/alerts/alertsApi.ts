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
  ruleId?: string;
  from?: string;
  to?: string;
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

export interface AlertRule {
  id: string;
  accountId: string;
  name: string;
  description: string | null;
  ruleType: string;
  enabled: boolean;
  scopeType: string;
  scopeId: string | null;
  deviceType: string | null;
  metric: string;
  conditionType: string;
  threshold: number | null;
  thresholdUnit: string | null;
  baselineMultiplier: number | null;
  minPoints: number | null;
  severity: string;
  cooldownSeconds: number;
  deduplicationWindowSeconds: number;
  notificationChannelIds: string[];
  config: Record<string, unknown>;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface AlertRuleRequest {
  name: string;
  description?: string | null;
  ruleType: string;
  enabled?: boolean;
  scopeType: string;
  scopeId?: string | null;
  deviceType?: string | null;
  metric: string;
  conditionType: string;
  threshold?: number | null;
  thresholdUnit?: string | null;
  baselineMultiplier?: number | null;
  minPoints?: number | null;
  severity: string;
  cooldownSeconds?: number;
  deduplicationWindowSeconds?: number;
  notificationChannelIds?: string[];
  config?: Record<string, unknown>;
}

export interface AlertRuleFilters {
  enabled?: boolean;
  ruleType?: string;
  scopeType?: string;
  metric?: string;
}

export interface AlertRuleTestRequest {
  deviceId?: string;
  rule: AlertRuleRequest;
  from?: string;
  to?: string;
}

export interface AlertRuleTestResponse {
  matched: boolean;
  matchCount: number;
  wouldCreateAlert: boolean;
  severity: string;
  previewTitle: string;
  previewDescription: string;
  evaluationStatus: string;
  evidence: {
    timestamp: string;
    metric: string;
    value: number | null;
    threshold: number | null;
    alertId: string | null;
  }[];
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

export function listAlertRules(filters: AlertRuleFilters = {}) {
  return apiRequest<AlertRule[]>(`/alert-rules${query(filters)}`);
}

export function getAlertRule(ruleId: string) {
  return apiRequest<AlertRule>(`/alert-rules/${ruleId}`);
}

export function createAlertRule(request: AlertRuleRequest) {
  return apiRequest<AlertRule>("/alert-rules", {
    method: "POST",
    body: JSON.stringify(request),
  });
}

export function updateAlertRule(ruleId: string, request: Partial<AlertRuleRequest>) {
  return apiRequest<AlertRule>(`/alert-rules/${ruleId}`, {
    method: "PUT",
    body: JSON.stringify(request),
  });
}

export function deleteAlertRule(ruleId: string) {
  return apiRequest<void>(`/alert-rules/${ruleId}`, { method: "DELETE" });
}

export function enableAlertRule(ruleId: string) {
  return apiRequest<AlertRule>(`/alert-rules/${ruleId}/enable`, { method: "POST" });
}

export function disableAlertRule(ruleId: string) {
  return apiRequest<AlertRule>(`/alert-rules/${ruleId}/disable`, { method: "POST" });
}

export function testAlertRule(request: AlertRuleTestRequest) {
  return apiRequest<AlertRuleTestResponse>("/alert-rules/test", {
    method: "POST",
    body: JSON.stringify(request),
  });
}

export function listEffectiveAlertRules(deviceId: string) {
  return apiRequest<AlertRule[]>(`/alert-rules/effective?deviceId=${encodeURIComponent(deviceId)}`);
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
