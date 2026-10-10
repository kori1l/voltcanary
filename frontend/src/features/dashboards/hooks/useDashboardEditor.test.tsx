import {act,renderHook,waitFor,cleanup} from "@testing-library/react";
import {afterEach,beforeEach,describe,expect,it,vi} from "vitest";
import {ApiError,clearTokens,setTokens} from "../../../api/client";
import {getDashboardDraft,publishDashboard,saveDashboardDraft,type DashboardDraft,type DashboardMetadata} from "../../../dashboards/dashboardsApi";
import {blankDashboard} from "../model/dashboardLayout";
import {readRecovery} from "../model/dashboardRecovery";
import {useDashboardEditor} from "./useDashboardEditor";
vi.mock("../../../dashboards/dashboardsApi",()=>({getDashboardDraft:vi.fn(),publishDashboard:vi.fn(),saveDashboardDraft:vi.fn(),commandKey:()=>"command-key"}));
const metadata:DashboardMetadata={id:"dashboard",ownerUserId:"owner",accountId:"account",name:"Fleet",description:"",status:"ACTIVE",publishedRevision:1,createdAt:"",updatedAt:""};
const initial:DashboardDraft={dashboardId:"dashboard",authorUserId:"owner",baseRevision:1,etag:"initial-etag",definition:blankDashboard("Fleet"),updatedAt:""};
beforeEach(()=>{vi.resetAllMocks();localStorage.clear();setTokens(`${btoa("{}")}.${btoa(JSON.stringify({sub:"owner"}))}.fixture`,"fixture");});
afterEach(()=>{cleanup();vi.useRealTimers();clearTokens();localStorage.clear();});
describe("dashboard draft lifecycle",()=>{
  it("reports Saved when undo returns to the persisted layout and restores dirty status on redo",()=>{
    const {result}=renderHook(()=>useDashboardEditor(metadata,initial));
    act(()=>result.current.edit({...initial.definition,layouts:{...initial.definition.layouts,desktop:[{widgetId:"card",x:1,y:0,w:3,h:3}]}}));
    expect(result.current.dirty).toBe(true);
    act(()=>result.current.undo());expect(result.current.dirty).toBe(false);expect(result.current.status).toBe("Saved");expect(readRecovery(metadata,1)).toBeNull();
    act(()=>result.current.redo());expect(result.current.dirty).toBe(true);expect(result.current.status).toBe("Unsaved");
  });
  it("limits undo to twenty edits and supports twenty redo steps",()=>{
    const {result}=renderHook(()=>useDashboardEditor(metadata,initial));
    for(let index=1;index<=25;index++)act(()=>result.current.edit({...initial.definition,name:`Edit ${index}`}));
    for(let index=0;index<20;index++)act(()=>result.current.undo());
    expect(result.current.definition.name).toBe("Edit 5");expect(result.current.canUndo).toBe(false);
    for(let index=0;index<20;index++)act(()=>result.current.redo());
    expect(result.current.definition.name).toBe("Edit 25");expect(result.current.canRedo).toBe(false);
  });
  it("autosaves at two seconds, retains safe recovery on failure and never publishes",async()=>{
    vi.useFakeTimers();vi.mocked(saveDashboardDraft).mockRejectedValue(new ApiError("Offline",503));
    const {result}=renderHook(()=>useDashboardEditor(metadata,initial));
    act(()=>result.current.edit({...initial.definition,name:"Unsaved fleet"}));
    await act(async()=>vi.advanceTimersByTimeAsync(1999));expect(saveDashboardDraft).not.toHaveBeenCalled();
    await act(async()=>vi.advanceTimersByTimeAsync(1));
    expect(result.current.status).toContain("Save failed");expect(readRecovery(metadata,1)?.name).toBe("Unsaved fleet");expect(publishDashboard).not.toHaveBeenCalled();
  });
  it("preserves edits made during a pending save, then serializes publish and blocks duplicates",async()=>{
    let finish!:(draft:DashboardDraft)=>void;vi.mocked(saveDashboardDraft).mockImplementationOnce(()=>new Promise((resolve)=>{finish=resolve;})).mockImplementation(async(draft,definition)=>({...draft,etag:"new-etag",definition}));
    let finishPublish!:()=>void;vi.mocked(publishDashboard).mockImplementation(()=>new Promise((resolve)=>{finishPublish=()=>resolve({dashboardId:"dashboard",revision:2,definition:initial.definition,publishedBy:"owner",publishedAt:""});}));
    vi.mocked(getDashboardDraft).mockResolvedValue({...initial,baseRevision:2,etag:"published-etag"});
    const {result}=renderHook(()=>useDashboardEditor(metadata,initial));
    act(()=>result.current.edit({...initial.definition,name:"First edit"}));
    let savePromise!:Promise<DashboardDraft|null>;act(()=>{savePromise=result.current.save();});
    act(()=>result.current.edit({...initial.definition,name:"Second edit"}));
    await act(async()=>{finish({...initial,etag:"saved-first",definition:{...initial.definition,name:"First edit"}});await savePromise;});
    expect(result.current.definition.name).toBe("Second edit");expect(result.current.dirty).toBe(true);
    let publication!:Promise<boolean>;act(()=>{publication=result.current.publish();});
    await waitFor(()=>expect(publishDashboard).toHaveBeenCalledTimes(1));
    await act(async()=>{expect(await result.current.publish()).toBe(false);});
    await act(async()=>{finishPublish();await publication;});
    expect(saveDashboardDraft).toHaveBeenCalledTimes(2);expect(result.current.draft.baseRevision).toBe(2);
  });
  it("requires reviewed latest conflict before reapply, keeps opaque widgets and rejects changed latest",async()=>{
    const opaque={id:"opaque",type:"future-widget",typeVersion:7,title:"Future",binding:{},query:{future:"kept"},presentation:{},navigation:{},payload:{futureField:42}};
    const draft={...initial,definition:{...initial.definition,widgets:[opaque]}};
    vi.mocked(saveDashboardDraft).mockRejectedValue(new ApiError("Concurrent draft",409));
    const latest={...initial,etag:"latest",baseRevision:2,definition:{...draft.definition,name:"Other editor"}};
    vi.mocked(getDashboardDraft).mockResolvedValue(latest);
    const {result}=renderHook(()=>useDashboardEditor(metadata,draft));act(()=>result.current.edit({...draft.definition,name:"Local"}));
    await act(async()=>{await result.current.save();});await waitFor(()=>expect(result.current.latestConflict?.etag).toBe("latest"));
    vi.mocked(getDashboardDraft).mockResolvedValueOnce({...latest,etag:"newer"});
    await act(async()=>{await result.current.resolve(true);});expect(result.current.conflict).toBe(true);expect(result.current.latestConflict?.etag).toBe("newer");
    vi.mocked(getDashboardDraft).mockResolvedValue({...latest,etag:"newer"});
    await act(async()=>{await result.current.resolve(true);});expect(result.current.conflict).toBe(false);expect(result.current.definition.widgets[0]).toEqual(opaque);expect(result.current.definition.name).toBe("Local");expect(result.current.dirty).toBe(true);
  });
  it("clears recovery on access denial and ignores an old actor's pending response",async()=>{
    vi.mocked(saveDashboardDraft).mockRejectedValueOnce(new ApiError("Access removed",403));
    const {result}=renderHook(()=>useDashboardEditor(metadata,initial));act(()=>result.current.edit({...initial.definition,name:"Local"}));
    await act(async()=>{await result.current.save();});expect(result.current.denied).toBe(true);expect(readRecovery(metadata,1)).toBeNull();
    cleanup();let finish!:(draft:DashboardDraft)=>void;vi.mocked(saveDashboardDraft).mockImplementationOnce(()=>new Promise((resolve)=>{finish=resolve;}));
    const next=renderHook(()=>useDashboardEditor(metadata,initial));act(()=>next.result.current.edit({...initial.definition,name:"Actor A"}));let pending!:Promise<DashboardDraft|null>;act(()=>{pending=next.result.current.save();});
    act(()=>clearTokens());await act(async()=>{finish({...initial,etag:"late-actor-a"});await pending;});expect(next.result.current.draft.etag).toBe("initial-etag");
  });
});
