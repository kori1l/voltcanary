import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { useMlWorkspaceData } from "./useMlWorkspaceData";

afterEach(() => { cleanup(); vi.restoreAllMocks(); });
it("keeps the current settings scope when an older device response finishes later", async () => {
  const deferred = new Map<string, (response: Response) => void>();
  const settings = { mlEnabled: true, predictionMode: "MODEL_OR_BASELINE_HEURISTIC", confidenceWarningThreshold: 0.6, maintenanceBehavior: "REDUCE_CONFIDENCE", explanationsVisible: true };
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    const url = String(input);
    if (url.includes("/ml-settings/devices/")) return new Promise<Response>((resolve) => deferred.set(url.split("/").pop()!, resolve));
    return new Response(JSON.stringify(url.endsWith("/ml-settings") ? settings : []), { status: 200 });
  });
  const { result } = renderHook(() => useMlWorkspaceData({ datasetStatusFilter: "default", trainingStatusFilter: "default", modelStatusFilter: "default", policyStatusFilter: "default" }));
  await waitFor(() => expect(result.current.effective).not.toBeNull());
  act(() => result.current.setDeviceId("device-a"));
  let first: Promise<void>;
  act(() => { first = result.current.loadDevice(); });
  act(() => result.current.setDeviceId("device-b"));
  let second: Promise<void>;
  act(() => { second = result.current.loadDevice(); });
  await act(async () => { deferred.get("device-b")!(new Response(JSON.stringify({ ...settings, deviceId: "device-b", source: "DEVICE" }), { status: 200 })); await second; });
  await act(async () => { deferred.get("device-a")!(new Response(JSON.stringify({ ...settings, deviceId: "device-a", source: "DEVICE" }), { status: 200 })); await first; });
  expect(result.current.effective?.deviceId).toBe("device-b");
});
