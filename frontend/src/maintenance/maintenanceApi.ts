import { apiRequest } from "../api/client";

export type MaintenanceOperationStatus = "SCHEDULED" | "ACTIVE" | "FINISHED" | "CANCELLED";

export interface MaintenanceOperationDevice {
  deviceId: string;
  deviceName: string;
  deviceType: string | null;
  locationId: string | null;
  locationName: string | null;
  windowId: string;
  status: MaintenanceOperationStatus;
}

export interface MaintenanceOperation {
  id: string;
  accountId: string;
  title: string;
  description: string | null;
  startAt: string;
  endAt: string;
  status: MaintenanceOperationStatus;
  suppressAlerts: boolean;
  excludeFromAnalytics: boolean;
  excludeFromMl: boolean;
  cancelledAt: string | null;
  cancellationReason: string | null;
  devices: MaintenanceOperationDevice[];
  createdBy: string | null;
  updatedBy: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface MaintenanceOperationRequest {
  title: string;
  description?: string | null;
  deviceIds: string[];
  startAt: string;
  endAt: string;
  suppressAlerts: boolean;
  excludeFromAnalytics: boolean;
  excludeFromMl: boolean;
}

export interface MaintenanceOperationListParams {
  from?: string;
  to?: string;
  deviceIds?: string[];
  locationId?: string;
  status?: string;
  includeCancelled?: boolean;
  limit?: number;
  offset?: number;
}

function query(params: MaintenanceOperationListParams) {
  const searchParams = new URLSearchParams();
  if (params.from) searchParams.set("from", params.from);
  if (params.to) searchParams.set("to", params.to);
  if (params.locationId && params.locationId !== "all") searchParams.set("locationId", params.locationId);
  if (params.status && params.status !== "all") searchParams.set("status", params.status);
  if (params.includeCancelled) searchParams.set("includeCancelled", "true");
  if (params.limit !== undefined) searchParams.set("limit", String(params.limit));
  if (params.offset !== undefined) searchParams.set("offset", String(params.offset));
  params.deviceIds?.forEach((deviceId) => searchParams.append("deviceIds", deviceId));
  const value = searchParams.toString();
  return value ? `?${value}` : "";
}

export function listMaintenanceOperations(params: MaintenanceOperationListParams = {}) {
  return apiRequest<MaintenanceOperation[]>(`/maintenance-operations${query(params)}`);
}

export function createMaintenanceOperation(request: MaintenanceOperationRequest) {
  return apiRequest<MaintenanceOperation>("/maintenance-operations", {
    method: "POST",
    body: JSON.stringify(request),
  });
}

export function updateMaintenanceOperation(operationId: string, request: MaintenanceOperationRequest) {
  return apiRequest<MaintenanceOperation>(`/maintenance-operations/${operationId}`, {
    method: "PATCH",
    body: JSON.stringify(request),
  });
}

export function cancelMaintenanceOperation(operationId: string, reason: string) {
  return apiRequest<MaintenanceOperation>(`/maintenance-operations/${operationId}/cancel`, {
    method: "POST",
    body: JSON.stringify({ reason }),
  });
}

export function finishMaintenanceOperationNow(operationId: string) {
  return apiRequest<MaintenanceOperation>(`/maintenance-operations/${operationId}/finish-now`, {
    method: "POST",
  });
}
