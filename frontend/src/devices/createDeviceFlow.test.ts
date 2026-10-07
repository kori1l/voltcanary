import { beforeEach, describe, expect, it, vi } from "vitest";
import { createDevice, createDataSource, createDataSourceToken } from "./devicesApi";
import { getSimulationTemplate, saveSimulationConfig, startSimulation } from "../simulation/simulationApi";
import { createDemoDevice, createDeviceWithSource, DeviceSetupProgress } from "./createDeviceFlow";
import { inspectImportCsv } from "./CsvImportPreview";
vi.mock("./devicesApi", () => ({ createDevice: vi.fn(), createDataSource: vi.fn(), createDataSourceToken: vi.fn() }));
vi.mock("../simulation/simulationApi", () => ({ getSimulationTemplate: vi.fn(), saveSimulationConfig: vi.fn(), startSimulation: vi.fn() }));
const request = { name: "Fridge", type: "FRIDGE", locationId: "room" };
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(createDevice).mockResolvedValue({ ...request, id: "device", status: "ACTIVE", manufacturer: null, model: null });
  vi.mocked(createDataSource).mockResolvedValue({ id: "source", deviceId: "device", sourceType: "SIMULATOR", protocol: "REST", externalId: "sim" });
  vi.mocked(createDataSourceToken).mockResolvedValue({ token: "memory-only", tokenPrefix: "memory" });
  vi.mocked(getSimulationTemplate).mockResolvedValue({ yamlConfig: "profile: FRIDGE" } as Awaited<ReturnType<typeof getSimulationTemplate>>);
  vi.mocked(saveSimulationConfig).mockResolvedValue({ id: "config" } as Awaited<ReturnType<typeof saveSimulationConfig>>);
});
describe("resumable onboarding", () => {
  it("continues a failed source setup without creating another device", async () => {
    const progress: DeviceSetupProgress = {};
    vi.mocked(createDataSource).mockRejectedValueOnce(new Error("source unavailable"));
    await expect(createDeviceWithSource(request, { mode: "MQTT", externalId: "meter" }, progress)).rejects.toThrow("source unavailable");
    expect(progress.device?.id).toBe("device");
    await createDeviceWithSource(request, { mode: "MQTT", externalId: "meter" }, progress);
    expect(createDevice).toHaveBeenCalledTimes(1);
    expect(createDataSource).toHaveBeenLastCalledWith("device", { sourceType: "MQTT", protocol: "MQTT", externalId: "meter" });
  });
  it("retains simulator configuration and credential when starting fails", async () => {
    const progress: DeviceSetupProgress = {};
    const source = { mode: "SIMULATOR" as const, externalId: "sim", profile: "FRIDGE", preset: "NORMAL_OPERATION", startImmediately: true };
    vi.mocked(startSimulation).mockRejectedValueOnce(new Error("runtime offline"));
    await expect(createDemoDevice(request, source, progress)).rejects.toThrow("runtime offline");
    await createDemoDevice(request, source, progress);
    for (const action of [createDevice, createDataSource, createDataSourceToken, saveSimulationConfig]) expect(action).toHaveBeenCalledTimes(1);
    expect(startSimulation).toHaveBeenCalledTimes(2);
    expect(saveSimulationConfig).toHaveBeenCalledWith("device", expect.objectContaining({ sourceToken: "memory-only", dataSourceId: "source" }));
  });
  it("rejects unsupported quoted CSV and numbers row errors without rewriting text", () => {
    expect(inspectImportCsv('name,type\n"Kitchen, fridge",FRIDGE', ["name", "type"]).rows[0]).toEqual(expect.objectContaining({ line: 2, errors: expect.arrayContaining([expect.stringContaining("quoted")]) }));
    expect(inspectImportCsv("name,type\nFridge,FRIDGE\n", ["name", "type"]).valid).toBe(true);
    expect(inspectImportCsv("name,type\n", ["name", "type"]).valid).toBe(false);
  });
});
