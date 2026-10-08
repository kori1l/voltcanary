import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { clearTokens } from "../api/client";
import { queryDashboard, type DashboardQueryBatch } from "./dashboardQueriesApi";
import { useDashboardQuery } from "./useDashboardQuery";
vi.mock("./dashboardQueriesApi", () => ({ queryDashboard: vi.fn() }));
const query = vi.mocked(queryDashboard);
const batch = (hash: string): DashboardQueryBatch => ({ dashboardId: "dashboard", revision: 1, draftEtag: null,
  contextHash: hash, asOf: "2026-10-08T12:00:00Z", returnedAt: "2026-10-08T12:00:01Z", cached: false, panels: [] });
beforeEach(() => query.mockReset());
afterEach(() => vi.restoreAllMocks());
describe("dashboard query scope isolation", () => {
  it("drops an old runtime scope response and aborts its transport", async () => {
    let finishOld!: (value: DashboardQueryBatch) => void;
    query.mockImplementationOnce(() => new Promise(resolve => { finishOld = resolve; })).mockResolvedValueOnce(batch("new"));
    const { result, rerender } = renderHook(({ device }) => useDashboardQuery("dashboard", { runtimeContext: { scope: { deviceIds: [device] } } }),
      { initialProps: { device: "old-device" } });
    await waitFor(() => expect(query).toHaveBeenCalledTimes(1));
    const signal = query.mock.calls[0][2];
    rerender({ device: "new-device" });
    await waitFor(() => expect(result.current.data?.contextHash).toBe("new"));
    await act(async () => finishOld(batch("old")));
    expect(result.current.data?.contextHash).toBe("new");
    expect(signal?.aborted).toBe(true);
  });
  it("purges the previous actor's batch immediately on identity change", async () => {
    query.mockResolvedValueOnce(batch("owner-a")).mockImplementationOnce(() => new Promise(() => {}));
    const { result } = renderHook(() => useDashboardQuery("dashboard", {}));
    await waitFor(() => expect(result.current.data?.contextHash).toBe("owner-a"));
    act(() => clearTokens());
    expect(result.current.data).toBeNull();
    await waitFor(() => expect(query).toHaveBeenCalledTimes(2));
  });
});
