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

export async function createDeviceWithSource(deviceRequest: CreateDeviceRequest, source: DeviceSourceInput): Promise<DeviceSetupResult> {
  const device = await createDevice(deviceRequest);
  const dataSource = await createDataSource(device.id, {
    sourceType: source.mode === "MQTT" ? "MQTT" : "SMART_PLUG",
    protocol: source.mode === "MQTT" ? "MQTT" : "REST",
    externalId: source.externalId,
  });
  return { device, dataSource };
}

export async function createDemoDevice(deviceRequest: CreateDeviceRequest, source: DemoDeviceInput): Promise<DeviceSetupResult> {
  const device = await createDevice({
    ...deviceRequest,
    metadata: {
      ...(deviceRequest.metadata ?? {}),
      setupMode: "demo",
      preset: source.preset,
    },
  });
  const dataSource = await createDataSource(device.id, {
    sourceType: "SIMULATOR",
    protocol: "REST",
    externalId: source.externalId,
  });
  const token = await createDataSourceToken(device.id, dataSource.id);
  const template = await getSimulationTemplate(source.profile, source.externalId, source.preset);
  const simulationConfig = await saveSimulationConfig(device.id, {
    dataSourceId: dataSource.id,
    name: `${deviceRequest.name} Simulator`,
    profile: source.profile,
    protocol: "HTTP",
    yamlConfig: template.yamlConfig,
    sourceToken: token.token,
  });
  const simulationRun = source.startImmediately ? await startSimulation(device.id) : undefined;
  return { device, dataSource, simulationConfig, simulationRun };
}

export function defaultSourceId(mode: DeviceSourceMode, name: string) {
  const slug = name.trim().toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "device";
  const suffix = Math.random().toString(36).slice(2, 8);
  return `${mode === "SIMULATOR" ? "sim" : mode.toLowerCase()}-${slug}-${suffix}`;
}
