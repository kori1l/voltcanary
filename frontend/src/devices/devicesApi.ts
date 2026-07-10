import { apiRequest, apiTextRequest } from "../api/client";

export type DeviceStatus = "ACTIVE" | "ONLINE" | "DISABLED" | "OFFLINE" | "DECOMMISSIONED";

export interface Device {
  id: string;
  locationId: string | null;
  name: string;
  type: string;
  manufacturer: string | null;
  model: string | null;
  status: DeviceStatus;
  metadata?: Record<string, unknown> | null;
}

export interface Location {
  id: string;
  name: string;
  description: string | null;
  parentId?: string | null;
  building?: string | null;
  floor?: string | null;
  room?: string | null;
  zone?: string | null;
  tags?: string[];
}

export interface DeviceType {
  code: string;
  displayName: string;
}

export interface DeviceAccount {
  id: string;
  userId: string;
  name: string;
  status: "ACTIVE" | "DISABLED" | "DELETED";
}

export interface DeviceListParams {
  locationId?: string;
  type?: string;
  status?: string;
  search?: string;
  limit?: number;
  offset?: number;
}

export interface LocationListParams {
  search?: string;
  limit?: number;
  offset?: number;
}

export interface CreateLocationRequest {
  parentId?: string | null;
  name: string;
  description?: string | null;
  building?: string | null;
  floor?: string | null;
  room?: string | null;
  zone?: string | null;
  tags?: string[];
}

export interface CreateDeviceRequest {
  locationId: string | null;
  name: string;
  type: string;
  manufacturer?: string | null;
  model?: string | null;
  metadata?: Record<string, unknown> | null;
}

export interface CreateDataSourceRequest {
  sourceType: string;
  protocol: string;
  externalId: string;
}

export interface DataSource {
  id: string;
  deviceId: string;
  sourceType: string;
  protocol: string;
  externalId: string;
}

export interface SourceTokenResponse {
  token: string;
  tokenPrefix: string;
}

export interface ImportResponse {
  importedCount: number;
  errors: string[];
}

export interface Sensor {
  id: string;
  deviceId: string;
  name: string;
  type: string;
  unit: string | null;
  metricMapping: string | null;
  metadata: Record<string, unknown> | null;
}

export interface SensorRequest {
  name: string;
  type: string;
  unit?: string | null;
  metricMapping?: string | null;
  metadata?: Record<string, unknown> | null;
}

export interface AnalyticsConfig {
  enabled: boolean;
  config: Record<string, unknown>;
}

export type MaintenanceWindowStatus = "SCHEDULED" | "ACTIVE" | "FINISHED" | "CANCELLED";

export interface MaintenanceWindow {
  id: string;
  accountId: string;
  deviceId: string;
  title: string;
  description: string | null;
  startAt: string;
  endAt: string;
  status: MaintenanceWindowStatus;
  suppressAlerts: boolean;
  excludeFromAnalytics: boolean;
  excludeFromMl: boolean;
  cancelledAt: string | null;
  cancellationReason: string | null;
  createdBy: string | null;
  updatedBy: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface MaintenanceWindowRequest {
  title: string;
  description?: string | null;
  startAt: string;
  endAt: string;
  suppressAlerts: boolean;
  excludeFromAnalytics: boolean;
  excludeFromMl: boolean;
}

function query(params: object) {
  const searchParams = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== "" && value !== "all") {
      searchParams.set(key, String(value));
    }
  });
  const value = searchParams.toString();
  return value ? `?${value}` : "";
}

export function listDevices(params: DeviceListParams = {}) {
  return apiRequest<Device[]>(`/devices${query(params)}`);
}

export function getDevice(deviceId: string) {
  return apiRequest<Device>(`/devices/${deviceId}`);
}

export function updateDevice(deviceId: string, request: CreateDeviceRequest) {
  return apiRequest<Device>(`/devices/${deviceId}`, {
    method: "PATCH",
    body: JSON.stringify(request),
  });
}

export function enableDevice(deviceId: string) {
  return apiRequest<Device>(`/devices/${deviceId}/enable`, { method: "POST" });
}

export function disableDevice(deviceId: string) {
  return apiRequest<Device>(`/devices/${deviceId}/disable`, { method: "POST" });
}

export function decommissionDevice(deviceId: string) {
  return apiRequest<Device>(`/devices/${deviceId}/decommission`, { method: "POST" });
}

export function listLocations(params: LocationListParams = {}) {
  return apiRequest<Location[]>(`/locations${query(params)}`);
}

export function createLocation(request: CreateLocationRequest | string) {
  const body = typeof request === "string" ? { name: request, description: null } : request;
  return apiRequest<Location>("/locations", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export function listDeviceTypes() {
  return apiRequest<DeviceType[]>("/device-types");
}

export function getCurrentDeviceAccount() {
  return apiRequest<DeviceAccount>("/accounts/current");
}

export function createDevice(request: CreateDeviceRequest) {
  return apiRequest<Device>("/devices", {
    method: "POST",
    body: JSON.stringify(request),
  });
}

export function importDevicesCsv(csv: string) {
  return apiRequest<ImportResponse>("/devices/imports/csv", {
    method: "POST",
    headers: { "Content-Type": "text/plain" },
    body: csv,
  });
}

export function exportDevicesCsv() {
  return apiTextRequest("/devices/export.csv", {
    headers: { Accept: "text/csv" },
  });
}

export function listDataSources(deviceId: string) {
  return apiRequest<DataSource[]>(`/devices/${deviceId}/data-sources`);
}

export function createDataSource(deviceId: string, request: CreateDataSourceRequest) {
  return apiRequest<DataSource>(`/devices/${deviceId}/data-sources`, {
    method: "POST",
    body: JSON.stringify(request),
  });
}

export function updateDataSource(deviceId: string, dataSourceId: string, request: CreateDataSourceRequest) {
  return apiRequest<DataSource>(`/devices/${deviceId}/data-sources/${dataSourceId}`, {
    method: "PATCH",
    body: JSON.stringify(request),
  });
}

export function disableDataSource(deviceId: string, dataSourceId: string) {
  return apiRequest<void>(`/devices/${deviceId}/data-sources/${dataSourceId}`, {
    method: "DELETE",
  });
}

export function createDataSourceToken(deviceId: string, dataSourceId: string) {
  return apiRequest<SourceTokenResponse>(`/devices/${deviceId}/data-sources/${dataSourceId}/token`, {
    method: "POST",
  });
}

export function revokeDataSourceToken(deviceId: string, dataSourceId: string) {
  return apiRequest<void>(`/devices/${deviceId}/data-sources/${dataSourceId}/token/revoke`, {
    method: "POST",
  });
}

export function importDataSourcesCsv(deviceId: string, csv: string) {
  return apiRequest<ImportResponse>(`/devices/${deviceId}/data-sources/imports/csv`, {
    method: "POST",
    headers: { "Content-Type": "text/plain" },
    body: csv,
  });
}

export function exportDataSourcesCsv(deviceId: string) {
  return apiTextRequest(`/devices/${deviceId}/data-sources/export.csv`, {
    headers: { Accept: "text/csv" },
  });
}

export function listSensors(deviceId: string) {
  return apiRequest<Sensor[]>(`/devices/${deviceId}/sensors`);
}

export function createSensor(deviceId: string, request: SensorRequest) {
  return apiRequest<Sensor>(`/devices/${deviceId}/sensors`, {
    method: "POST",
    body: JSON.stringify(request),
  });
}

export function updateSensor(deviceId: string, sensorId: string, request: SensorRequest) {
  return apiRequest<Sensor>(`/devices/${deviceId}/sensors/${sensorId}`, {
    method: "PATCH",
    body: JSON.stringify(request),
  });
}

export function deleteSensor(deviceId: string, sensorId: string) {
  return apiRequest<void>(`/devices/${deviceId}/sensors/${sensorId}`, {
    method: "DELETE",
  });
}

export function getAnalyticsConfig(deviceId: string) {
  return apiRequest<AnalyticsConfig>(`/devices/${deviceId}/analytics-config`);
}

export function updateAnalyticsConfig(deviceId: string, request: AnalyticsConfig) {
  return apiRequest<AnalyticsConfig>(`/devices/${deviceId}/analytics-config`, {
    method: "PATCH",
    body: JSON.stringify(request),
  });
}

export function listMaintenanceWindows(deviceId: string) {
  return apiRequest<MaintenanceWindow[]>(`/devices/${deviceId}/maintenance-windows`);
}

export function createMaintenanceWindow(deviceId: string, request: MaintenanceWindowRequest) {
  return apiRequest<MaintenanceWindow>(`/devices/${deviceId}/maintenance-windows`, {
    method: "POST",
    body: JSON.stringify(request),
  });
}

export function cancelMaintenanceWindow(deviceId: string, windowId: string, reason: string) {
  return apiRequest<MaintenanceWindow>(`/devices/${deviceId}/maintenance-windows/${windowId}/cancel`, {
    method: "POST",
    body: JSON.stringify({ reason }),
  });
}

export function finishMaintenanceWindowNow(deviceId: string, windowId: string) {
  return apiRequest<MaintenanceWindow>(`/devices/${deviceId}/maintenance-windows/${windowId}/finish-now`, {
    method: "POST",
  });
}
