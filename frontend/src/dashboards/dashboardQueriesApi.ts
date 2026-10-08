import { apiRequest } from "../api/client";

export type WidgetType = "fleet-count" | "latest-value" | "time-series" | "metric-summary" | "incident-list"
  | "maintenance-agenda" | "risk-list" | "quality-and-freshness" | "location-matrix" | "prediction-evidence" | "context-note";
export interface DashboardScope {
  mode?: "ALL" | "DYNAMIC" | "FIXED" | "UNBOUND";
  deviceIds?: string[];
  locationIds?: string[];
  includeSubtree?: boolean;
  deviceTypes?: string[];
  locationTags?: string[];
}
export interface DashboardTime {
  mode?: "INHERITED" | "RELATIVE" | "CALENDAR" | "FIXED";
  preset?: "LAST_24_HOURS" | "LAST_7_DAYS" | "LAST_30_DAYS" | "TODAY" | "WEEK_TO_DATE" | "MONTH_TO_DATE" | "NEXT_7_DAYS" | "NEXT_30_DAYS";
  timezone?: string;
  from?: string;
  to?: string;
}
export interface DashboardQueryRequest {
  revision?: number;
  draftEtag?: string;
  runtimeContext?: { scope?: DashboardScope; time?: DashboardTime };
  forceRefresh?: boolean;
  widgetIds?: string[];
}
export interface QueryFrame {
  from: string;
  to: string;
  asOf: string;
  timezone: string;
  override: boolean;
  compareFrom: string | null;
  compareTo: string | null;
  comparisonBasis: string;
}
export interface WidgetQueryPanel {
  widgetId: string;
  status: "ready" | "no_data" | "unavailable" | "no_access" | "unsupported" | "invalid" | "valid";
  data: Record<string, unknown> | null;
  contextHash: string;
  time: QueryFrame | null;
  requestedCount: number;
  evaluatedCount: number;
  receivedAt: string;
  measuredAt: string | null;
  provenance: Record<string, unknown>;
  errorCode: string | null;
  explanation: string | null;
}
export interface DashboardQueryBatch {
  dashboardId: string;
  revision: number;
  draftEtag: string | null;
  contextHash: string;
  asOf: string;
  returnedAt: string;
  cached: boolean;
  panels: WidgetQueryPanel[];
}
export interface WidgetManifest {
  type: WidgetType | string;
  typeVersion: number;
  title: string;
  resultType: string;
  supported: boolean;
  dependency: string | null;
  reducers: string[];
  units: string[];
  maxSeries: number;
  maxDetailDevices: number;
  maxRows: number;
  timeBasis: string;
}
export function queryDashboard(id: string, request: DashboardQueryRequest, signal?: AbortSignal) {
  return apiRequest<DashboardQueryBatch>(`/dashboards/${encodeURIComponent(id)}/query`, {
    method: "POST", body: JSON.stringify(request), signal,
  });
}
export function validateDashboardQuery(id: string, request: DashboardQueryRequest, signal?: AbortSignal) {
  return apiRequest<DashboardQueryBatch>(`/dashboards/${encodeURIComponent(id)}/query-validation`, {
    method: "POST", body: JSON.stringify(request), signal,
  });
}
export function listWidgetTypes(signal?: AbortSignal) {
  return apiRequest<WidgetManifest[]>("/dashboard-widget-types", { signal });
}
