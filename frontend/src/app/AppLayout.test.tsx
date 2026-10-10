import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import {listDashboards} from "../dashboards/dashboardsApi";
import { AppLayout } from "./AppLayout";
vi.mock("../auth/authApi", () => ({ me: vi.fn(async () => ({ id:"layout-admin",role: "ADMIN" })), logout: vi.fn() }));
vi.mock("../aiAgent/AssistantDrawer", () => ({ AssistantDrawer: () => null }));
vi.mock("../dashboards/dashboardsApi",async(original)=>({...await original<typeof import("../dashboards/dashboardsApi")>(),listDashboards:vi.fn()}));
beforeEach(()=>{vi.mocked(listDashboards).mockResolvedValue({items:[],hasNext:false,nextCursor:null});});
afterEach(() => { cleanup(); vi.restoreAllMocks(); Object.defineProperty(window,"innerWidth",{configurable:true,value:1024}); });
function setup(width=1280,path="/settings/ml"){Object.defineProperty(window,"innerWidth",{configurable:true,value:width});vi.spyOn(window,"scrollTo").mockImplementation(()=>{});render(<MemoryRouter initialEntries={[path]}><Routes><Route element={<AppLayout/>}><Route path="*" element={<h1>Workspace</h1>}/></Route></Routes></MemoryRouter>);}
it("has one desktop sidebar toggle, specific active navigation and full hide/restore",async()=>{
 setup();await waitFor(()=>expect(screen.getByRole("link",{name:"ML workspace"})).toHaveClass("active"));expect(screen.getByRole("link",{name:/^Settings$/})).not.toHaveAttribute("aria-current");
 expect(screen.getAllByRole("button",{name:"Hide navigation"})).toHaveLength(1);await userEvent.click(screen.getByRole("button",{name:"Hide navigation"}));expect(screen.queryByRole("navigation",{name:"Main navigation"})).not.toBeInTheDocument();expect(screen.getByRole("button",{name:"Show navigation"})).toHaveAttribute("aria-expanded","false");
 await userEvent.click(screen.getByRole("button",{name:"Show navigation"}));expect(screen.getByRole("navigation",{name:"Main navigation"})).toBeVisible();expect(screen.queryByRole("button",{name:"Menu"})).not.toBeInTheDocument();
});
it("opens a native mobile left drawer, restores focus on close and closes on route changes",async()=>{
 setup(390);const trigger=screen.getByRole("button",{name:"Show navigation"});await userEvent.click(trigger);expect(screen.getByRole("dialog",{name:"Navigation"})).toHaveClass("console-dialog-side-left");
 fireEvent(screen.getByRole("dialog",{name:"Navigation"}),new Event("cancel",{cancelable:true}));expect(screen.queryByRole("dialog")).not.toBeInTheDocument();expect(trigger).toHaveFocus();
 await userEvent.click(trigger);fireEvent.click(screen.getByRole("dialog",{name:"Navigation"}),{clientX:1000,clientY:10});expect(screen.queryByRole("dialog")).not.toBeInTheDocument();expect(trigger).toHaveFocus();
 await userEvent.click(trigger);await userEvent.click(screen.getByRole("link",{name:"Devices"}));await waitFor(()=>expect(screen.queryByRole("dialog")).not.toBeInTheDocument());expect(window.scrollTo).toHaveBeenCalledWith({top:0,left:0});
});

it.each(["/dashboards","/dashboards/owned","/dashboards/owned/edit"])("has one active dashboard entry on %s",async(path)=>{
 setup(1280,path);await waitFor(()=>expect(screen.getByRole("link",{name:"Admin"})).toBeInTheDocument());
 expect(screen.queryByRole("link",{name:"My dashboards"})).not.toBeInTheDocument();
 const entry=screen.getByRole("link",{name:"Dashboards"});expect(entry).toHaveAttribute("href","/dashboards");if(path==="/dashboards")expect(entry).toHaveAttribute("aria-current","page");expect(entry).toHaveClass("active");
});

it("shows available dashboards as sidebar children and opens unpublished drafts in Edit",async()=>{
 const item={accountId:"account",ownerUserId:"owner",description:"",status:"ACTIVE" as const,createdAt:"",updatedAt:""};vi.mocked(listDashboards).mockResolvedValue({items:[{...item,id:"owned",name:"Monitoring",publishedRevision:1},{...item,id:"draft",name:"Analyst draft",publishedRevision:0}],hasNext:false,nextCursor:null});setup(1280,"/dashboards/owned/edit");const monitor=await screen.findByRole("link",{name:"Monitoring"});expect(monitor).toHaveAttribute("aria-current","page");expect(screen.getByRole("link",{name:"Analyst draft"})).toHaveAttribute("href","/dashboards/draft/edit");expect(screen.queryByRole("link",{name:"My dashboards"})).not.toBeInTheDocument();
});
