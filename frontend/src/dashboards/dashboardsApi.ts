import {dashboardCatalogChanged} from "./dashboardCatalogRevision";
import { apiRequest } from "../api/client";
import type { DashboardScope, DashboardTime } from "./dashboardQueriesApi";

export interface RuntimeContext { scope?: DashboardScope; time?: DashboardTime }
export interface DashboardWidget {
  id: string; type: string; typeVersion: number; title: string;
  binding: DashboardScope; query: Record<string, unknown>; presentation: Record<string, unknown>;
  navigation: { href?: string }; payload: Record<string, unknown>;
}
export interface Placement { widgetId: string; x: number; y: number; w: number; h: number }
export type Breakpoint = "desktop" | "tablet" | "mobile";
export interface DashboardGrid {columns:number;rowHeight:number;gap:number}
export interface DashboardScreen {id:string;title:string;widgetIds:string[];grid?:Partial<Record<Breakpoint,DashboardGrid>>}
export interface DashboardDefinition {
  schemaVersion: 1; name: string; description: string; defaultContext: RuntimeContext;
  sections: { id: string; title: string; widgetIds: string[]; screenId?:string }[];
  widgets: DashboardWidget[]; layouts: Record<Breakpoint, Placement[]>;
  screens?:DashboardScreen[];
}
export interface DashboardMetadata {
  id: string; ownerUserId: string; accountId: string; name: string; description: string;
  status: "ACTIVE" | "ARCHIVED"; publishedRevision: number; createdAt: string; updatedAt: string;
}
export interface DashboardView { dashboard: DashboardMetadata; definition: DashboardDefinition | null; revision: number; permissions: string[]; draftEtag: string; unsupportedWidgetIds: string[] }
export interface DashboardDraft { dashboardId: string; authorUserId: string; baseRevision: number; etag: string; definition: DashboardDefinition; updatedAt: string }
export interface DashboardPreferences { defaultDashboardId: string | null; favorites: string[]; defaultContext: RuntimeContext; etag: string; updatedAt: string | null; fallbackReason: string | null }
export interface DashboardRevision { dashboardId: string; revision: number; definition: DashboardDefinition; publishedBy: string; publishedAt: string }
const path = (id: string) => `/dashboards/${encodeURIComponent(id)}`;
const match = (etag: string) => ({ "If-Match": `"${etag}"` });
export const commandKey = () => crypto.randomUUID();
export function listDashboards(status = "ACTIVE", cursor?: string, signal?: AbortSignal) {
  const params = new URLSearchParams({ status, limit: "100" }); if (cursor) params.set("cursor", cursor);
  return apiRequest<{items: DashboardMetadata[]; hasNext: boolean; nextCursor: string | null}>(`/dashboards?${params}`, { signal });
}
export const getDashboard = (id: string, signal?: AbortSignal) => apiRequest<DashboardView>(path(id), { signal });
export const getDashboardDraft = (id: string, signal?: AbortSignal) => apiRequest<DashboardDraft>(`${path(id)}/draft`, { signal });
export const getDashboardPreferences = (signal?: AbortSignal) => apiRequest<DashboardPreferences>("/dashboard-preferences/me", { signal });
export function saveDashboardPreferences(preferences: DashboardPreferences) {
  return apiRequest<DashboardPreferences>("/dashboard-preferences/me", {method:"PUT", headers:match(preferences.etag), body:JSON.stringify({defaultDashboardId:preferences.defaultDashboardId, favorites:preferences.favorites, defaultContext:preferences.defaultContext})}).then(dashboardCatalogChanged);
}
export function createDashboard(definition: DashboardDefinition, key: string) {
  return apiRequest<DashboardMetadata>("/dashboards", {method:"POST", headers:{"Idempotency-Key":key}, body:JSON.stringify({definition})}).then(dashboardCatalogChanged);
}
export function copyDashboard(id: string, name: string, key: string) {
  return apiRequest<DashboardMetadata>(`${path(id)}/copy`, {method:"POST", headers:{"Idempotency-Key":key}, body:JSON.stringify({name})}).then(dashboardCatalogChanged);
}
export function saveDashboardDraft(draft: DashboardDraft, definition: DashboardDefinition) {
  return apiRequest<DashboardDraft>(`${path(draft.dashboardId)}/draft`, {method:"PUT", headers:match(draft.etag), body:JSON.stringify({baseRevision:draft.baseRevision, definition})});
}
export function publishDashboard(draft: DashboardDraft, key: string) {
  return apiRequest<DashboardRevision>(`${path(draft.dashboardId)}/publish`, {method:"POST", headers:{...match(draft.etag), "Idempotency-Key":key}, body:JSON.stringify({draftEtag:draft.etag})}).then(dashboardCatalogChanged);
}
export function transitionDashboard(id: string, etag: string, archive: boolean) {
  return apiRequest<DashboardMetadata>(`${path(id)}/${archive ? "archive" : "restore"}`, {method:"POST", headers:match(etag)}).then(dashboardCatalogChanged);
}
export const dashboardRevisions = (id: string) => apiRequest<{items:{revision:number; publishedAt:string}[]}>(`${path(id)}/revisions`);
export function rollbackDashboardDraft(draft: DashboardDraft, sourceRevision: number) {
  return apiRequest<DashboardDraft>(`${path(draft.dashboardId)}/draft`, {method:"PUT", headers:match(draft.etag), body:JSON.stringify({baseRevision:draft.baseRevision,sourceRevision})});
}
