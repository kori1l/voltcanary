import { apiRequest } from "../../../api/client";

export interface DashboardDataChoice {
  choiceId: string;
  deviceId: string;
  sourceId: string | null;
  externalSourceId: string | null;
  family: "REGISTERED_NUMERIC_METRIC" | "RECORDED_VEHICLE_FIELD" | "DOMAIN_EVIDENCE";
  key: string | null;
  label: string;
  valueType: string;
  unit: string;
  currentStatus: string;
  sourceFreshness: string;
  historyStatus: string;
  views: {
    widgetType: string;
    typeVersion: number;
    purpose: string;
    visualizations: string[];
    readiness: string;
    requirements: string[];
  }[];
}
export function dashboardCapabilities(deviceIds: string[], signal?: AbortSignal) {
  return apiRequest<{
    descriptorVersion: number;
    snapshotId: string;
    choices: DashboardDataChoice[];
    diagnostics: { code: string; message: string }[];
  }>("/dashboards/composition/capabilities", {
    method: "POST",
    body: JSON.stringify({ deviceIds, period: "LAST_1_HOUR" }),
    signal,
  });
}
