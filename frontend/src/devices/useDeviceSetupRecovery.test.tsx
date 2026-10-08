import { cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { clearTokens, setTokens } from "../api/client";
import { getDevice, listDataSources } from "./devicesApi";
import { getSimulationConfig, getSimulationStatus } from "../simulation/simulationApi";
import { writeDeviceSetupCheckpoint } from "./deviceSetupCheckpoint";
import { useDeviceSetupRecovery } from "./useDeviceSetupRecovery";
import type { DeviceSetupProgress } from "./createDeviceFlow";
vi.mock("./devicesApi", () => ({ getDevice: vi.fn(), listDataSources: vi.fn() }));
vi.mock("../simulation/simulationApi", () => ({ getSimulationConfig: vi.fn(), getSimulationStatus: vi.fn() }));
afterEach(() => { cleanup(); clearTokens(); localStorage.clear(); vi.resetAllMocks(); });
const account = { id: "account-A", userId: "user-A", name: "Private", status: "ACTIVE" as const };
function save() {
  setTokens(`${btoa("{}")}\.${btoa(JSON.stringify({ sub: "user-A" }))}.fixture`, "fixture");
  writeDeviceSetupCheckpoint(account, { deviceId: "device-canonical", sourceId: "source-canonical", configurationId: "config-canonical", runId: "run-canonical", name: "Fridge", type: "FRIDGE", mode: "SIMULATOR", externalId: "sim-1", profile: "FRIDGE", preset: "NORMAL_OPERATION", startImmediately: true });
  vi.mocked(getDevice).mockResolvedValue({ id: "device-canonical", name: "Fridge" } as Awaited<ReturnType<typeof getDevice>>);
  vi.mocked(listDataSources).mockResolvedValue([{ id: "source-canonical", deviceId: "device-canonical", externalId: "sim-1" }] as Awaited<ReturnType<typeof listDataSources>>);
  vi.mocked(getSimulationConfig).mockResolvedValue({ id: "config-canonical", deviceId: "device-canonical", dataSourceId: "source-canonical", hasSourceToken: true } as Awaited<ReturnType<typeof getSimulationConfig>>);
  vi.mocked(getSimulationStatus).mockResolvedValue({ runId: "run-canonical", status: "FAILED" } as Awaited<ReturnType<typeof getSimulationStatus>>);
}
it("restores only owned canonical references and the actual failed run across remount", async () => {
  save();
  for (let attempt = 0; attempt < 2; attempt++) {
    const progress: { current: DeviceSetupProgress } = { current: {} };
    const restored = vi.fn();
    const view = renderHook(() => useDeviceSetupRecovery(account, progress, restored));
    await waitFor(() => expect(restored).toHaveBeenCalledTimes(1));
    expect(progress.current).toMatchObject({ device: { id: "device-canonical" }, dataSource: { id: "source-canonical" }, simulationConfig: { hasSourceToken: true }, simulationRun: { status: "FAILED" } });
    expect(progress.current.sourceToken).toBeUndefined();
    view.unmount();
  }
  expect(getDevice).toHaveBeenCalledTimes(2);
});
it("blocks continuation when authorization fails and marks an absent source for review", async () => {
  save(); vi.mocked(getDevice).mockRejectedValueOnce(new Error("Forbidden"));
  const progress: { current: DeviceSetupProgress } = { current: {} };
  const restored = vi.fn();
  const view = renderHook(() => useDeviceSetupRecovery(account, progress, restored));
  await waitFor(() => expect(view.result.current.failed).toBe(true));
  expect(restored).not.toHaveBeenCalled();
  vi.mocked(listDataSources).mockResolvedValue([]);
  view.result.current.retry();
  await waitFor(() => expect(restored).toHaveBeenCalledTimes(1));
  expect(progress.current.unknownStage).toBe("source");
});
