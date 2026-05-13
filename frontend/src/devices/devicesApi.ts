import { apiRequest } from "../api/client";

export interface Device {
  id: string;
  locationId: string | null;
  name: string;
  type: string;
  manufacturer: string | null;
  model: string | null;
  status: string;
}

export interface Location {
  id: string;
  name: string;
  description: string | null;
}

export interface DeviceType {
  code: string;
  displayName: string;
}

export interface CreateDeviceRequest {
  locationId: string;
  name: string;
  type: string;
  manufacturer?: string;
  model?: string;
}

export interface CreateDataSourceRequest {
  sourceType: string;
  protocol: string;
  externalId: string;
}

export function listDevices() {
  return apiRequest<Device[]>("/devices");
}

export function getDevice(deviceId: string) {
  return apiRequest<Device>(`/devices/${deviceId}`);
}

export function listLocations() {
  return apiRequest<Location[]>("/locations");
}

export function createLocation(name: string) {
  return apiRequest<Location>("/locations", {
    method: "POST",
    body: JSON.stringify({ name, description: null }),
  });
}

export function listDeviceTypes() {
  return apiRequest<DeviceType[]>("/device-types");
}

export function createDevice(request: CreateDeviceRequest) {
  return apiRequest<Device>("/devices", {
    method: "POST",
    body: JSON.stringify(request),
  });
}

export function createDataSource(deviceId: string, request: CreateDataSourceRequest) {
  return apiRequest(`/devices/${deviceId}/data-sources`, {
    method: "POST",
    body: JSON.stringify(request),
  });
}
