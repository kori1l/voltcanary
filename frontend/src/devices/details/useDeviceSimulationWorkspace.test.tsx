import { act, cleanup, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { setTokens } from "../../api/client";
import { createDataSourceToken, updateDataSource } from "../devicesApi";
import * as api from "../../simulation/simulationApi";
import { useDeviceSimulationWorkspace } from "./useDeviceSimulationWorkspace";

vi.mock("../devicesApi", () => ({
  createDataSourceToken: vi.fn(),
  updateDataSource: vi.fn(),
}));
vi.mock("../../simulation/simulationApi", () => ({
  getSimulationConfig: vi.fn(),
  getSimulationRuns: vi.fn(),
  getSimulationStatus: vi.fn(),
  getSimulationTemplate: vi.fn(),
  resetSimulationTemplate: vi.fn(),
  saveSimulationConfig: vi.fn(),
  startSimulation: vi.fn(),
  stopSimulation: vi.fn(),
  validateSimulationConfig: vi.fn(),
}));
vi.mock("../useDeviceSimulationPresets", () => ({
  useDeviceSimulationPresets: () => ({
    data: [],
    refreshing: false,
    retry: vi.fn(),
  }),
}));
vi.mock("../../components/useActionConfirmation", () => ({
  useActionConfirmation: () => ({ confirm: async () => "", dialog: null }),
}));

const config: api.SimulationConfig = {
  id: "config-a",
  deviceId: "device-a",
  dataSourceId: "source-a",
  name: "Simulator",
  profile: "FRIDGE",
  protocol: "HTTP",
  yamlConfig: "profile: FRIDGE\nprotocol: HTTP\nsourceId: source-a",
  yamlVersion: 1,
  status: "STOPPED",
  updatedAt: "2026-06-13T08:00:00Z",
  hasSourceToken: true,
};
const stopped: api.SimulationRun = {
  runId: null,
  status: "STOPPED",
  profile: "FRIDGE",
  protocol: "HTTP",
  generatedPoints: 0,
  startedAt: null,
  stoppedAt: null,
  lastError: null,
};
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
function workspace() {
  const onSourceTokenGenerated = vi.fn();
  const hook = renderHook(
    ({ deviceId }) =>
      useDeviceSimulationWorkspace({
        deviceId,
        activeTab: "overview",
        onSourceTokenGenerated,
      }),
    { initialProps: { deviceId: "device-a" } },
  );
  return { ...hook, onSourceTokenGenerated };
}

beforeEach(() => {
  vi.resetAllMocks();
  vi.useFakeTimers();
  setTokens("access", "refresh");
  vi.mocked(api.getSimulationConfig).mockResolvedValue(config);
  vi.mocked(api.getSimulationStatus).mockResolvedValue(stopped);
  vi.mocked(api.getSimulationRuns).mockResolvedValue([]);
  vi.mocked(api.validateSimulationConfig).mockResolvedValue({
    valid: true,
    errors: [],
  });
  vi.mocked(updateDataSource).mockResolvedValue({} as never);
  vi.mocked(api.saveSimulationConfig).mockResolvedValue(config);
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe("simulation workspace asynchronous boundaries", () => {
  it("keeps the latest selected profile when template responses arrive out of order", async () => {
    const first = deferred<{ yamlConfig: string }>();
    const second = deferred<{ yamlConfig: string }>();
    vi.mocked(api.getSimulationTemplate)
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);
    const { result } = workspace();
    act(() => {
      result.current.changeSimulationProfile("PUMP");
      result.current.changeSimulationProfile("HEATER");
    });
    await act(async () => {
      second.resolve({ yamlConfig: "profile: HEATER" });
    });
    await act(async () => {
      first.resolve({ yamlConfig: "profile: PUMP" });
    });
    expect(result.current.simulationProfile).toBe("HEATER");
    expect(result.current.simulationYaml).toBe("profile: HEATER");
    expect(result.current.simulationMessage).toContain("Profile template loaded");
  });

  it("preserves a manual draft edited while its template is loading", async () => {
    const template = deferred<{ yamlConfig: string }>();
    vi.mocked(api.getSimulationTemplate).mockReturnValue(template.promise);
    const { result } = workspace();
    act(() => result.current.changeSimulationProfile("PUMP"));
    act(() => result.current.setSimulationYaml("operator draft"));
    await act(async () => {
      template.resolve({ yamlConfig: "late template" });
    });
    expect(result.current.simulationYaml).toBe("operator draft");
  });

  it("does not carry an old device's config or drafts into a new device", async () => {
    const response = deferred<api.SimulationConfig>();
    vi.mocked(api.getSimulationConfig).mockReturnValue(response.promise);
    const { result, rerender } = workspace();
    let load!: Promise<void>;
    act(() => {
      load = result.current.loadSimulation();
    });
    rerender({ deviceId: "device-b" });
    await act(async () => {
      response.resolve(config);
      await load;
    });
    expect(result.current.simulationConfig).toBeNull();
    expect(result.current.simulationYaml).toBe("");
    expect(api.getSimulationStatus).not.toHaveBeenCalled();
  });

  it("retains the latest runtime read when a preceding refresh finishes later", async () => {
    const first = deferred<api.SimulationRun>();
    const second = deferred<api.SimulationRun>();
    vi.mocked(api.getSimulationStatus)
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);
    const { result } = workspace();
    let a!: Promise<void>;
    let b!: Promise<void>;
    act(() => {
      a = result.current.refreshSimulationRuntime();
      b = result.current.refreshSimulationRuntime();
    });
    await act(async () => {
      second.resolve({ ...stopped, generatedPoints: 20 });
      await b;
    });
    await act(async () => {
      first.resolve({ ...stopped, generatedPoints: 10 });
      await a;
    });
    expect(result.current.simulationStatus?.generatedPoints).toBe(20);
  });

  it("surfaces failed saves and stops before issuing dependent mutations", async () => {
    const { result } = workspace();
    await act(async () => {
      await result.current.loadSimulation();
    });
    vi.mocked(updateDataSource).mockRejectedValue(new Error("Source update unavailable"));
    await act(async () => {
      await result.current.saveYaml();
    });
    expect(result.current.simulationMessage).toBe("Source update unavailable");
    expect(result.current.isSimulationBusy).toBe(false);
    expect(api.saveSimulationConfig).not.toHaveBeenCalled();
  });

  it("reports validation and reset failures without unhandled command rejections", async () => {
    const { result } = workspace();
    await act(async () => {
      await result.current.loadSimulation();
    });
    vi.mocked(api.validateSimulationConfig).mockRejectedValue(new Error("Validation unavailable"));
    await act(async () => {
      await result.current.validateYaml();
    });
    expect(result.current.simulationMessage).toBe("Validation unavailable");
    expect(result.current.simulationValidation.valid).toBe(false);
    vi.mocked(api.resetSimulationTemplate).mockRejectedValue(new Error("Template unavailable"));
    await act(async () => {
      await result.current.resetYamlTemplate();
    });
    expect(result.current.simulationMessage).toBe("Template unavailable");
    expect(result.current.simulationYaml).toBe(config.yamlConfig);
  });

  it("keeps operator edits made during a successful save", async () => {
    const saved = deferred<api.SimulationConfig>();
    vi.mocked(api.saveSimulationConfig).mockReturnValue(saved.promise);
    const { result } = workspace();
    await act(async () => {
      await result.current.loadSimulation();
    });
    let save!: Promise<void>;
    await act(async () => {
      save = result.current.saveYaml();
    });
    act(() => result.current.setSimulationYaml("newer draft"));
    await act(async () => {
      saved.resolve(config);
      await save;
    });
    expect(result.current.simulationYaml).toBe("newer draft");
    expect(result.current.hasUnsavedSimulation).toBe(true);
  });

  it("does not rotate credentials or start after the session changes during a command", async () => {
    const update = deferred<Awaited<ReturnType<typeof updateDataSource>>>();
    vi.mocked(updateDataSource).mockReturnValue(update.promise);
    const { result, onSourceTokenGenerated } = workspace();
    await act(async () => {
      await result.current.loadSimulation();
    });
    let start!: Promise<void>;
    await act(async () => {
      start = result.current.toggleSimulation();
    });
    act(() => setTokens("another-access", "another-refresh"));
    await act(async () => {
      update.resolve({} as never);
      await start;
    });
    expect(createDataSourceToken).not.toHaveBeenCalled();
    expect(api.startSimulation).not.toHaveBeenCalled();
    expect(onSourceTokenGenerated).not.toHaveBeenCalled();
    expect(result.current.simulationConfig).toBeNull();
  });
  it("queues credential synchronization behind a save and preserves the latest saved configuration", async () => {
    const pending = deferred<api.SimulationConfig>();
    const latest = {
      ...config,
      name: "New saved name",
      yamlConfig: "profile: PUMP",
      profile: "PUMP",
      yamlVersion: 2,
    };
    const { result } = workspace();
    await act(async () => {
      await result.current.loadSimulation();
    });
    vi.mocked(api.saveSimulationConfig)
      .mockReturnValueOnce(pending.promise)
      .mockResolvedValue(latest);
    vi.mocked(api.getSimulationConfig).mockResolvedValue(latest);
    let save!: Promise<void>;
    let sync!: Promise<void>;
    await act(async () => {
      save = result.current.saveYaml();
    });
    act(() => {
      sync = result.current.syncSourceToken("source-a", "rotated");
    });
    expect(api.saveSimulationConfig).toHaveBeenCalledTimes(1);
    await act(async () => {
      pending.resolve(latest);
      await save;
      await sync;
    });
    expect(api.saveSimulationConfig).toHaveBeenCalledTimes(2);
    expect(api.saveSimulationConfig).toHaveBeenLastCalledWith(
      "device-a",
      expect.objectContaining({
        name: latest.name,
        profile: latest.profile,
        yamlConfig: latest.yamlConfig,
        sourceToken: "rotated",
      }),
    );
  });

  it("propagates credential synchronization failures to the registry workflow", async () => {
    const { result } = workspace();
    await act(async () => {
      await result.current.loadSimulation();
    });
    vi.mocked(api.getSimulationConfig).mockRejectedValue(new Error("Credential sync unavailable"));
    await act(async () => {
      await expect(result.current.syncSourceToken("source-a", "rotated")).rejects.toThrow(
        "Credential sync unavailable",
      );
    });
    expect(result.current.simulationMessage).toBe("Credential sync unavailable");
  });
});
