import { ApiError } from "../api/client";
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
  vi.mocked(createDataSource).mockImplementation(async (deviceId, source) => ({ id: "source", deviceId, ...source }));
  vi.mocked(createDataSourceToken).mockResolvedValue({ token: "memory-only", tokenPrefix: "memory" });
  vi.mocked(getSimulationTemplate).mockResolvedValue({ yamlConfig: "profile: FRIDGE" } as Awaited<ReturnType<typeof getSimulationTemplate>>);
  vi.mocked(saveSimulationConfig).mockResolvedValue({ id: "config" } as Awaited<ReturnType<typeof saveSimulationConfig>>);
});
describe("resumable onboarding", () => {
  it("blocks another source creation after a lost response until a canonical source is reviewed", async () => {
    const progress: DeviceSetupProgress = {};
    vi.mocked(createDataSource).mockRejectedValueOnce(new ApiError("Response lost", 0));
    const source = { mode: "API" as const, externalId: "plug" };
    await expect(createDeviceWithSource(request, source, progress)).rejects.toMatchObject({ stage: "source" });
    await expect(createDeviceWithSource(request, source, progress)).rejects.toMatchObject({ stage: "source" });
    expect(createDevice).toHaveBeenCalledTimes(1); expect(createDataSource).toHaveBeenCalledTimes(1);
    progress.dataSource = { id: "source-reviewed", deviceId: "device", sourceType: "SMART_PLUG", protocol: "REST", externalId: "plug" }; progress.unknownStage = undefined;
    expect((await createDeviceWithSource(request, source, progress)).dataSource.id).toBe("source-reviewed");
    expect(createDataSource).toHaveBeenCalledTimes(1);
  });
  it("reuses an authorized saved simulator configuration after reload without issuing or rotating credentials", async () => {
    const device = await createDevice(request);
    vi.mocked(createDevice).mockClear();
    const progress: DeviceSetupProgress = { device, dataSource: { id: "source", deviceId: "device", sourceType: "SIMULATOR", protocol: "REST", externalId: "sim" }, simulationConfig: { id: "config", hasSourceToken: true } as Awaited<ReturnType<typeof saveSimulationConfig>> };
    vi.mocked(startSimulation).mockResolvedValue({ runId: "run-canonical", status: "FAILED", generatedPoints: 0 } as Awaited<ReturnType<typeof startSimulation>>);
    const result = await createDemoDevice(request, { mode: "SIMULATOR", externalId: "sim", profile: "FRIDGE", preset: "NORMAL_OPERATION", startImmediately: true }, progress);
    expect(result.simulationRun?.status).toBe("FAILED");
    expect(createDevice).not.toHaveBeenCalled(); expect(createDataSource).not.toHaveBeenCalled(); expect(createDataSourceToken).not.toHaveBeenCalled(); expect(saveSimulationConfig).not.toHaveBeenCalled();
  });
  it("continues a failed source setup without creating another device", async () => {
    const progress: DeviceSetupProgress = {};
    vi.mocked(createDataSource).mockRejectedValueOnce(new ApiError("source unavailable", 422));
    await expect(createDeviceWithSource(request, { mode: "MQTT", externalId: "meter" }, progress)).rejects.toThrow("source unavailable");
    expect(progress.device?.id).toBe("device");
    await createDeviceWithSource(request, { mode: "MQTT", externalId: "meter" }, progress);
    expect(createDevice).toHaveBeenCalledTimes(1);
    expect(createDataSource).toHaveBeenLastCalledWith("device", { sourceType: "MQTT", protocol: "MQTT", externalId: "meter" });
  });
  it("retains simulator configuration and credential when starting fails", async () => {
    const progress: DeviceSetupProgress = {};
    const source = { mode: "SIMULATOR" as const, externalId: "sim", profile: "FRIDGE", preset: "NORMAL_OPERATION", startImmediately: true };
    vi.mocked(startSimulation).mockRejectedValueOnce(new ApiError("runtime offline", 422));
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
