import {
  createDataSource,
  createDataSourceToken,
  createDevice,
  CreateDeviceRequest,
  DataSource,
  Device,
} from "./devicesApi";
import { getSimulationTemplate, saveSimulationConfig, SimulationConfig, SimulationRun, startSimulation } from "../simulation/simulationApi";
import { ApiError } from "../api/client";

export type DeviceSourceMode = "API" | "MQTT" | "SIMULATOR";

export interface DeviceSetupResult {
  device: Device;
  dataSource: DataSource;
  simulationConfig?: SimulationConfig;
  simulationRun?: SimulationRun;
}

export interface DeviceSourceInput {
  mode: DeviceSourceMode;
  externalId: string;
}

export interface DemoDeviceInput extends DeviceSourceInput {
  profile: string;
  preset: string;
  startImmediately: boolean;
}

/** Kept in memory while the wizard is open. Never persist source credentials. */
export interface DeviceSetupProgress {
  device?: Device;
  dataSource?: DataSource;
  sourceToken?: string;
  simulationConfig?: SimulationConfig;
  simulationRun?: SimulationRun;
  unknownStage?: DeviceSetupStage;
  checkpoint?: () => void;
}
export type DeviceSetupStage = "location" | "device" | "source" | "credential" | "configuration" | "run";
export class UnknownDeviceSetupResult extends Error {
  constructor(public readonly stage: DeviceSetupStage) { super(`The ${stage} response was not confirmed. Review existing records before continuing setup.`); }
}
export async function deviceSetupStep<T>(stage: DeviceSetupStage, progress: DeviceSetupProgress, action: () => Promise<T>): Promise<T> {
  if (progress.unknownStage) throw new UnknownDeviceSetupResult(progress.unknownStage);
  try { return await action(); }
  catch (error) {
    if (!(error instanceof ApiError) || error.status === 0 || error.status >= 500) { progress.unknownStage = stage; progress.checkpoint?.(); throw new UnknownDeviceSetupResult(stage); }
    throw error;
  }
}
async function registerDevice(request: CreateDeviceRequest) {
  const device = await createDevice(request);
  if (typeof device.id !== "string" || !device.id || typeof device.name !== "string") throw new SyntaxError("Device confirmation is incomplete");
  return device;
}
async function configureSource(deviceId: string, request: Parameters<typeof createDataSource>[1]) {
  const source = await createDataSource(deviceId, request);
  if (typeof source.id !== "string" || !source.id || source.deviceId !== deviceId || source.externalId !== request.externalId || source.protocol !== request.protocol || source.sourceType !== request.sourceType) throw new SyntaxError("Source confirmation is incomplete");
  return source;
}

export async function createDeviceWithSource(deviceRequest: CreateDeviceRequest, source: DeviceSourceInput, progress: DeviceSetupProgress = {}): Promise<DeviceSetupResult> {
  const device = progress.device ?? await deviceSetupStep("device", progress, () => registerDevice(deviceRequest));
  progress.device = device;
  progress.checkpoint?.();
  const dataSource = progress.dataSource ?? await deviceSetupStep("source", progress, () => configureSource(device.id, {
    sourceType: source.mode === "MQTT" ? "MQTT" : "SMART_PLUG",
    protocol: source.mode === "MQTT" ? "MQTT" : "REST",
    externalId: source.externalId,
  }));
  progress.dataSource = dataSource;
  progress.checkpoint?.();
  return { device, dataSource };
}

export async function createDemoDevice(deviceRequest: CreateDeviceRequest, source: DemoDeviceInput, progress: DeviceSetupProgress = {}): Promise<DeviceSetupResult> {
  const device = progress.device ?? await deviceSetupStep("device", progress, () => registerDevice({
    ...deviceRequest,
    metadata: {
      ...(deviceRequest.metadata ?? {}),
      setupMode: "demo",
      preset: source.preset,
    },
  }));
  progress.device = device;
  progress.checkpoint?.();
  const dataSource = progress.dataSource ?? await deviceSetupStep("source", progress, () => configureSource(device.id, {
    sourceType: "SIMULATOR",
    protocol: "REST",
    externalId: source.externalId,
  }));
  progress.dataSource = dataSource;
  progress.checkpoint?.();
  if (!progress.simulationConfig && !progress.sourceToken) progress.sourceToken = await deviceSetupStep("credential", progress, async () => { const response = await createDataSourceToken(device.id, dataSource.id); if (typeof response.token !== "string" || !response.token) throw new SyntaxError("Credential confirmation is incomplete"); return response.token; });
  if (progress.simulationConfig && !progress.sourceToken && !progress.simulationConfig.hasSourceToken) throw new ApiError("Review the saved simulator source credential before starting.", 422);
  if (!progress.simulationConfig) {
    const template = await getSimulationTemplate(source.profile, source.externalId, source.preset);
    progress.simulationConfig = await deviceSetupStep("configuration", progress, () => saveSimulationConfig(device.id, {
    dataSourceId: dataSource.id,
    name: `${deviceRequest.name} Simulator`,
    profile: source.profile,
    protocol: "HTTP",
    yamlConfig: template.yamlConfig,
    sourceToken: progress.sourceToken,
  }));
    progress.checkpoint?.();
  }
  if (source.startImmediately && !progress.simulationRun) { progress.simulationRun = await deviceSetupStep("run", progress, () => startSimulation(device.id)); progress.checkpoint?.(); }
  return { device, dataSource, simulationConfig: progress.simulationConfig, simulationRun: progress.simulationRun };
}

export function defaultSourceId(mode: DeviceSourceMode, name: string) {
  const slug = name.trim().toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "device";
  const suffix = Math.random().toString(36).slice(2, 8);
  return `${mode === "SIMULATOR" ? "sim" : mode.toLowerCase()}-${slug}-${suffix}`;
}
