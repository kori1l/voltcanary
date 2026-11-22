import { apiRequest } from "../../../api/client";
import type { DashboardMetadata, DashboardDefinition } from "../../../dashboards/dashboardsApi";
export interface Audience {
  subjectId: string;
  subjectKind: "USER" | "TEAM";
  capabilities: string[];
}
export interface DashboardAudience {
  dashboardId: string;
  workspaceId: string;
  version: number;
  audience: Audience[];
}
export const listTeamDashboards = (signal?: AbortSignal) =>
  apiRequest<DashboardMetadata[]>("/dashboards/team", { signal });
export const dashboardAudience = (id: string, signal?: AbortSignal) =>
  apiRequest<DashboardAudience>(`/dashboards/${id}/grants`, { signal });
export const associateDashboardWorkspace = (id: string, workspaceId: string) =>
  apiRequest(`/dashboards/${id}/workspace`, {
    method: "POST",
    body: JSON.stringify({ workspaceId, reviewed: true }),
  });
export const saveDashboardAudience = (id: string, audience: DashboardAudience) =>
  apiRequest(`/dashboards/${id}/grants`, {
    method: "PUT",
    body: JSON.stringify({
      audience: audience.audience,
      expectedVersion: audience.version,
      reviewed: true,
    }),
  });
export const previewDashboardAudience = (id: string, userIds: string[]) =>
  apiRequest<{
    recipients: {
      userId: string;
      widgets: { widgetId: string; usable: boolean; resourceAccessGranted: boolean }[];
    }[];
  }>(`/dashboards/${id}/sharing-preview`, { method: "POST", body: JSON.stringify({ userIds }) });
export const transferDashboardOwner = (id: string, userId: string) =>
  apiRequest(`/dashboards/${id}/transfer`, {
    method: "POST",
    body: JSON.stringify({ userId, reviewed: true }),
  });
export const exportDashboardTemplate = (id: string) =>
  apiRequest<DashboardDefinition>(`/dashboards/${id}/export`);
