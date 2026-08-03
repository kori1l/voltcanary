import { apiRequest } from "../api/client";

export type PredictionMode = "MODEL_ONLY" | "MODEL_OR_BASELINE_HEURISTIC" | "RULE_BASED_ONLY";
export type MaintenanceMlBehavior = "SUPPRESS_ML" | "REDUCE_CONFIDENCE";
export type MlSettingsSource = "DEFAULT" | "ACCOUNT" | "INHERITED" | "DEVICE_OVERRIDE";
export interface MlSettingsValues { mlEnabled: boolean; predictionMode: PredictionMode; confidenceWarningThreshold: number; maintenanceBehavior: MaintenanceMlBehavior; explanationsVisible: boolean; }
export interface MlSettingsResponse extends MlSettingsValues { accountId: string; deviceId: string | null; source: MlSettingsSource; updatedAt: string | null; }
export const getAccountMlSettings = () => apiRequest<MlSettingsResponse>("/analytics/ml-settings");
export const saveAccountMlSettings = (body: MlSettingsValues) => apiRequest<MlSettingsResponse>("/analytics/ml-settings", { method: "PUT", body: JSON.stringify(body) });
export const getDeviceMlSettings = (deviceId: string) => apiRequest<MlSettingsResponse>(`/analytics/ml-settings/devices/${deviceId}`);
export const saveDeviceMlSettings = (deviceId: string, body: MlSettingsValues) => apiRequest<MlSettingsResponse>(`/analytics/ml-settings/devices/${deviceId}`, { method: "PUT", body: JSON.stringify(body) });
export const clearDeviceMlSettings = (deviceId: string) => apiRequest<void>(`/analytics/ml-settings/devices/${deviceId}`, { method: "DELETE" });

export function safeMlError(error: unknown) {
  const message = normalizeApiError(error instanceof Error ? error.message : "ML settings request failed");
  return /https?:|stack|exception|localhost/i.test(message) ? "ML settings are temporarily unavailable. Try again later." : message;
}

function normalizeApiError(message: string) {
  const trimmed = message.trim();
  if (!trimmed) {
    return "ML settings request failed";
  }
  if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
    try {
      const parsed = JSON.parse(trimmed) as {
        error?: string;
        message?: string;
        path?: string;
        status?: number;
      };
      const summary = parsed.message ?? parsed.error;
      if (summary) {
        return parsed.path ? `${summary} for ${parsed.path}` : summary;
      }
      if (parsed.status) {
        return parsed.path ? `Request failed with status ${parsed.status} for ${parsed.path}` : `Request failed with status ${parsed.status}`;
      }
    } catch {
      return trimmed;
    }
  }
  return trimmed.length > 220 ? `${trimmed.slice(0, 217)}...` : trimmed;
}
