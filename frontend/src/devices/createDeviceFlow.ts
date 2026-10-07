import {
  createDataSource,
  createDataSourceToken,
  createDevice,
  CreateDeviceRequest,
  DataSource,
  Device,
} from "./devicesApi";
import { getSimulationTemplate, saveSimulationConfig, SimulationConfig, SimulationRun, startSimulation } from "../simulation/simulationApi";

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
}

export async function createDeviceWithSource(deviceRequest: CreateDeviceRequest, source: DeviceSourceInput, progress: DeviceSetupProgress = {}): Promise<DeviceSetupResult> {
  const device = progress.device ?? await createDevice(deviceRequest);
  progress.device = device;
  const dataSource = progress.dataSource ?? await createDataSource(device.id, {
    sourceType: source.mode === "MQTT" ? "MQTT" : "SMART_PLUG",
    protocol: source.mode === "MQTT" ? "MQTT" : "REST",
    externalId: source.externalId,
  });
  progress.dataSource = dataSource;
  return { device, dataSource };
}

export async function createDemoDevice(deviceRequest: CreateDeviceRequest, source: DemoDeviceInput, progress: DeviceSetupProgress = {}): Promise<DeviceSetupResult> {
  const device = progress.device ?? await createDevice({
    ...deviceRequest,
    metadata: {
      ...(deviceRequest.metadata ?? {}),
      setupMode: "demo",
      preset: source.preset,
    },
  });
  progress.device = device;
  const dataSource = progress.dataSource ?? await createDataSource(device.id, {
    sourceType: "SIMULATOR",
    protocol: "REST",
    externalId: source.externalId,
  });
  progress.dataSource = dataSource;
  if (!progress.sourceToken) progress.sourceToken = (await createDataSourceToken(device.id, dataSource.id)).token;
  if (!progress.simulationConfig) {
    const template = await getSimulationTemplate(source.profile, source.externalId, source.preset);
    progress.simulationConfig = await saveSimulationConfig(device.id, {
    dataSourceId: dataSource.id,
    name: `${deviceRequest.name} Simulator`,
    profile: source.profile,
    protocol: "HTTP",
    yamlConfig: template.yamlConfig,
    sourceToken: progress.sourceToken,
  });
  }
  if (source.startImmediately && !progress.simulationRun) progress.simulationRun = await startSimulation(device.id);
  return { device, dataSource, simulationConfig: progress.simulationConfig, simulationRun: progress.simulationRun };
}

export function defaultSourceId(mode: DeviceSourceMode, name: string) {
  const slug = name.trim().toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "device";
  const suffix = Math.random().toString(36).slice(2, 8);
  return `${mode === "SIMULATOR" ? "sim" : mode.toLowerCase()}-${slug}-${suffix}`;
}
