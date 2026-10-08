import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { useMlWorkspaceData } from "./useMlWorkspaceData";

afterEach(() => { cleanup(); vi.restoreAllMocks(); });
it("does not display obsolete dataset labels after selecting another dataset", async () => {
  const pending = new Map<string, (response: Response) => void>();
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    const url = new URL(String(input));
    if (url.pathname === "/analytics/ml/labels") return new Promise<Response>((resolve) => pending.set(url.searchParams.get("datasetVersionId")!, resolve));
    return new Response(JSON.stringify(url.pathname.endsWith("ml-settings") ? { mlEnabled: true } : []), { status: 200 });
  });
  const { result } = renderHook(() => useMlWorkspaceData({ datasetStatusFilter: "default", trainingStatusFilter: "default", modelStatusFilter: "default", policyStatusFilter: "default" }));
  let a!: Promise<unknown>, b!: Promise<unknown>;
  act(() => { a = result.current.refreshLabels("A"); });
  act(() => { b = result.current.refreshLabels("B"); });
  await act(async () => { pending.get("B")!(new Response(JSON.stringify([{ id: "B-label", datasetVersionId: "B" }]))); await b; });
  await act(async () => { pending.get("A")!(new Response(JSON.stringify([{ id: "A-label", datasetVersionId: "A" }]))); await a; });
  expect(result.current.labelsDatasetVersionId).toBe("B");
  expect(result.current.labels).toEqual([{ id: "B-label", datasetVersionId: "B" }]);
});
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
  let first: Promise<unknown>;
  act(() => { first = result.current.loadDevice(); });
  act(() => result.current.setDeviceId("device-b"));
  let second: Promise<unknown>;
  act(() => { second = result.current.loadDevice(); });
  await act(async () => { deferred.get("device-b")!(new Response(JSON.stringify({ ...settings, deviceId: "device-b", source: "DEVICE" }), { status: 200 })); await second; });
  await act(async () => { deferred.get("device-a")!(new Response(JSON.stringify({ ...settings, deviceId: "device-a", source: "DEVICE" }), { status: 200 })); await first; });
  expect(result.current.effective?.deviceId).toBe("device-b");
});
