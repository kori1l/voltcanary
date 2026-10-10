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
  it("clears the old screen snapshot and treats its cancellation as a normal switch",async()=>{
    query.mockResolvedValueOnce(batch("monitoring")).mockImplementationOnce((_id,_request,signal)=>new Promise((_resolve,reject)=>signal?.addEventListener("abort",()=>reject(new DOMException("Obsolete read","AbortError"))))).mockResolvedValueOnce(batch("incidents"));
    const {result,rerender}=renderHook(({screen,ids,enabled})=>useDashboardQuery("dashboard",{widgetIds:ids},enabled,screen),{initialProps:{screen:"monitoring",ids:["a"],enabled:true}});
    await waitFor(()=>expect(result.current.data?.contextHash).toBe("monitoring"));
    rerender({screen:"analytics",ids:["b"],enabled:true});expect(result.current.data).toBeNull();
    await waitFor(()=>expect(query).toHaveBeenCalledTimes(2));
    rerender({screen:"analytics",ids:[],enabled:false});
    await waitFor(()=>expect(result.current.refreshing).toBe(false));expect(result.current.error).toBeNull();expect(result.current.data).toBeNull();
    rerender({screen:"incidents",ids:["c"],enabled:true});await waitFor(()=>expect(result.current.data?.contextHash).toBe("incidents"));expect(result.current.error).toBeNull();
  });
  it("retains read offscreen panels as visibility changes without leaking another runtime",async()=>{
    const panel=(widgetId:string)=>({widgetId,status:"ready" as const,data:{value:1},contextHash:"context",time:null,requestedCount:1,evaluatedCount:1,receivedAt:"2026-10-08T12:00:01Z",measuredAt:null,provenance:{},errorCode:null,explanation:null});
    query.mockResolvedValueOnce({...batch("one"),panels:[panel("one")]}).mockResolvedValueOnce({...batch("two"),panels:[panel("two")]}).mockImplementationOnce(()=>new Promise(()=>{}));
    const {result,rerender}=renderHook(({ids,device})=>useDashboardQuery("dashboard",{widgetIds:ids,runtimeContext:{scope:{deviceIds:[device]}}}),{initialProps:{ids:["one"],device:"first"}});
    await waitFor(()=>expect(result.current.data?.panels).toHaveLength(1));
    rerender({ids:["two"],device:"first"});await waitFor(()=>expect(result.current.data?.panels).toHaveLength(2));
    expect(query.mock.calls[1][1].widgetIds).toEqual(["two"]);
    rerender({ids:["two"],device:"other"});expect(result.current.data).toBeNull();
  });
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
