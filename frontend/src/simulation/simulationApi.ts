import { apiRequest } from "../api/client";

export interface SimulationConfig {
  id: string;
  deviceId: string;
  dataSourceId: string;
  name: string;
  profile: string;
  protocol: string;
  yamlConfig: string;
  yamlVersion: number;
  status: string;
  updatedAt: string;
  hasSourceToken: boolean;
}

export interface SimulationRun {
  runId: string | null;
  status: string;
  profile: string;
  protocol: string;
  generatedPoints: number;
  startedAt: string | null;
  stoppedAt: string | null;
  lastError: string | null;
}

export interface SimulationPreset {
  id: string;
  name: string;
  description: string;
  metrics: string[];
  expectedAlerts: string[];
  defaultDuration: string;
  defaultInterval: string;
}

export function getSimulationConfig(deviceId: string) {
  return apiRequest<SimulationConfig>(`/simulation/devices/${deviceId}/config`);
}

export function saveSimulationConfig(
  deviceId: string,
  request: {
    dataSourceId: string;
    name: string;
    profile: string;
    protocol: string;
    yamlConfig: string;
    sourceToken?: string;
  },
) {
  return apiRequest<SimulationConfig>(`/simulation/devices/${deviceId}/config`, {
    method: "PUT",
    body: JSON.stringify(request),
  });
}

export function validateSimulationConfig(deviceId: string, yamlConfig: string) {
  return apiRequest<{ valid: boolean; errors: { path: string; message: string }[] }>(`/simulation/devices/${deviceId}/config/validate`, {
    method: "POST",
    body: JSON.stringify({ yamlConfig }),
  });
}

export function listSimulationPresets() {
  return apiRequest<SimulationPreset[]>("/simulation/presets");
}

export function getSimulationTemplate(profile = "FRIDGE", sourceId = "sim-fridge-001", preset = "NORMAL_OPERATION") {
  const params = new URLSearchParams({ sourceId, preset });
  return apiRequest<{ yamlConfig: string }>(`/simulation/templates/${profile}?${params.toString()}`);
}

export function resetSimulationTemplate(deviceId: string, profile = "FRIDGE", sourceId = "sim-fridge-001") {
  return apiRequest<{ yamlConfig: string }>(
    `/simulation/devices/${deviceId}/config/reset-template?profile=${encodeURIComponent(profile)}&sourceId=${encodeURIComponent(sourceId)}`,
    { method: "POST" },
  );
}

export function startSimulation(deviceId: string) {
  return apiRequest<SimulationRun>(`/simulation/devices/${deviceId}/start`, { method: "POST" });
}

export function stopSimulation(deviceId: string) {
  return apiRequest<SimulationRun>(`/simulation/devices/${deviceId}/stop`, { method: "POST" });
}

export function getSimulationStatus(deviceId: string, signal?: AbortSignal) {
  return apiRequest<SimulationRun>(`/simulation/devices/${deviceId}/status`, { signal });
}

export function getSimulationRuns(deviceId: string) {
  return apiRequest<SimulationRun[]>(`/simulation/devices/${deviceId}/runs`);
}
