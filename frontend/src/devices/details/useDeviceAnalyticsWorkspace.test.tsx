import { act, cleanup, renderHook } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { useDeviceAnalyticsWorkspace } from "./useDeviceAnalyticsWorkspace";

afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks(); });
it("retains an accepted job through status outages and refreshes it without submitting another recalculation", async () => {
  vi.useFakeTimers();
  let statusAvailable = false;
  const fetch = vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    const url = String(input);
    if (url.endsWith("/recalculate")) return new Response(JSON.stringify({ jobId: "job-1" }), { status: 202 });
    if (url.endsWith("/jobs/job-1")) return new Response(JSON.stringify(statusAvailable ? { id: "job-1", deviceId: "device-1", status: "SUCCEEDED" } : { message: "Status unavailable" }), { status: statusAvailable ? 200 : 503 });
    return new Response(JSON.stringify([]), { status: 200 });
  });
  const { result } = renderHook(() => useDeviceAnalyticsWorkspace("device-1"));
  let pending: Promise<void>;
  await act(async () => { pending = result.current.recalculateDeviceAnalytics(); await vi.advanceTimersByTimeAsync(13_000); await pending; });
  expect(result.current.analyticsJob).toMatchObject({ id: "job-1", status: "PENDING" });
  await act(async () => { await result.current.recalculateDeviceAnalytics(); });
  expect(fetch.mock.calls.filter(([url]) => String(url).endsWith("/recalculate"))).toHaveLength(1);
  statusAvailable = true;
  await act(async () => { await result.current.refreshAnalyticsJob(); });
  expect(result.current.analyticsJob?.status).toBe("SUCCEEDED");
  expect(fetch.mock.calls.filter(([url]) => String(url).endsWith("/recalculate"))).toHaveLength(1);
});
