import {cleanup,render,screen,waitFor,within,fireEvent} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {MemoryRouter,Route,Routes} from "react-router-dom";
import {afterEach,expect,it,vi} from "vitest";
import {AppLayout} from "../../../app/AppLayout";
import {DashboardDefaultPage,PersonalDashboardPage,PersonalDashboardsPage} from "../../../pages/PersonalDashboardsPage";
import {clearTokens,setTokens} from "../../../api/client";
import {blankDashboard} from "../model/dashboardLayout";
import {DashboardEditor} from "./DashboardEditor";
import type {DashboardDraft,DashboardMetadata,DashboardPreferences} from "../../../dashboards/dashboardsApi";
vi.mock("../../../auth/authApi",()=>({me:async()=>({role:"USER"}),logout:vi.fn()}));
vi.mock("../../../aiAgent/AssistantDrawer",()=>({AssistantDrawer:()=>null}));
afterEach(()=>{cleanup();vi.restoreAllMocks();clearTokens();localStorage.clear();});
it("blocks publication when an inactive screen fails renewed validation",async()=>{
  const definition={...blankDashboard("Screens"),widgets:["visible","hidden"].map((id)=>({id,type:"fleet-count",typeVersion:1,title:id,binding:{mode:"ALL" as const},query:{},presentation:{},navigation:{},payload:{}})),screens:[{id:"monitor",title:"Monitoring",widgetIds:["visible"]},{id:"analyst",title:"Analytics",widgetIds:["hidden"]}]};
  const metadata:DashboardMetadata={id:"owned",ownerUserId:"owner",accountId:"account",name:"Screens",description:"",status:"ACTIVE",publishedRevision:0,createdAt:"",updatedAt:""};
  const requests:{path:string;body:Record<string,unknown>}[]=[];
  vi.spyOn(globalThis,"fetch").mockImplementation(async(input,init)=>{
    const path=new URL(String(input)).pathname,body=init?.body?JSON.parse(String(init.body)):{};requests.push({path,body});
    const result=path.endsWith("query-validation")?{panels:[{widgetId:"hidden",status:"no_access"}]}:path.endsWith("/query")?{panels:[{widgetId:"visible",status:"ready",data:{counts:{registered:1}},provenance:{}}]}:path.endsWith("revisions")?{items:[]}:[];
    return new Response(JSON.stringify(result),{headers:{"Content-Type":"application/json"}});
  });
  render(<MemoryRouter><DashboardEditor metadata={metadata} initial={{dashboardId:"owned",authorUserId:"owner",baseRevision:0,etag:"saved",definition,updatedAt:""}}/></MemoryRouter>);
  await waitFor(()=>expect(screen.getByRole("button",{name:"Review publication"})).toBeEnabled());
  await userEvent.click(screen.getByRole("button",{name:"Review publication"}));
  expect(await screen.findByText(/1 widgets across the screens need configuration or renewed access/)).toBeInTheDocument();
  expect(requests.find((request)=>request.path.endsWith("query-validation"))?.body).not.toHaveProperty("widgetIds");
  expect(requests.find((request)=>request.path.endsWith("/query"))?.body.widgetIds).toEqual(["visible"]);
  expect(requests.some((request)=>request.path.endsWith("/publish"))).toBe(false);expect(screen.queryByRole("button",{name:"Confirm publish"})).not.toBeInTheDocument();
  expect(screen.getByRole("link",{name:"View published dashboard"})).toHaveAttribute("href","/dashboards/owned?screenId=monitor");
});
it("lets USER create, preview three-device facts, publish, default, reload and archive with safe fallback",async()=>{
  localStorage.clear();setTokens(`${btoa("{}")}.${btoa(JSON.stringify({sub:"owner"}))}.fixture`,"fixture");vi.spyOn(window,"scrollTo").mockImplementation(()=>{});
  let metadata:DashboardMetadata|null=null;
  let draft:DashboardDraft={dashboardId:"created",authorUserId:"owner",baseRevision:0,etag:"draft-0",definition:blankDashboard("New"),updatedAt:""};
  let preferences:DashboardPreferences={defaultDashboardId:null,favorites:[],defaultContext:{},etag:"prefs-0",updatedAt:null,fallbackReason:null};
  const requests:{path:string;method:string;body:Record<string,unknown>;headers:Headers}[]=[];
  vi.spyOn(globalThis,"fetch").mockImplementation(async(input,init)=>{
    const path=new URL(String(input)).pathname,method=init?.method ?? "GET",body=init?.body?JSON.parse(String(init.body)):{};requests.push({path,method,body,headers:new Headers(init?.headers)});
    let result:unknown=[];
    if(path==="/dashboards"){
      if(method==="POST"){draft={...draft,definition:body.definition};metadata={id:"created",ownerUserId:"owner",accountId:"account",name:draft.definition.name,description:"",status:"ACTIVE",publishedRevision:0,createdAt:"2026-10-09",updatedAt:"2026-10-09"};result=metadata;}else result={items:metadata?[metadata]:[],hasNext:false,nextCursor:null};
    }else if(path==="/dashboards/created")result={dashboard:metadata,definition:metadata?.publishedRevision?draft.definition:null,revision:metadata?.publishedRevision ?? 0,permissions:metadata?.status==="ARCHIVED"?["view","restore","copy"]:["view","edit","publish","archive","copy"],draftEtag:draft.etag,unsupportedWidgetIds:[]};
    else if(path==="/dashboards/created/draft"){if(method==="PUT")draft={...draft,definition:body.definition,etag:"draft-saved"};result=draft;}
    else if(path==="/dashboards/created/publish"){metadata={...metadata!,publishedRevision:1};draft={...draft,baseRevision:1,etag:"published-draft"};result={dashboardId:"created",revision:1,definition:draft.definition};}
    else if(path==="/dashboards/created/archive"){metadata={...metadata!,status:"ARCHIVED"};preferences={...preferences,defaultDashboardId:null,favorites:[],fallbackReason:"DEFAULT_ARCHIVED"};result=metadata;}
    else if(path==="/dashboards/created/revisions")result={items:[]};
    else if(path==="/dashboard-preferences/me"){if(method==="PUT")preferences={...preferences,...body,etag:"prefs-saved"};result=preferences;}
    else if(path==="/dashboard-widget-types")result=[{type:"fleet-count",typeVersion:1,title:"Fleet registration",resultType:"COUNT",supported:true,reducers:[],units:[],maxSeries:0,maxDetailDevices:0,maxRows:50,timeBasis:"SNAPSHOT"}];
    else if(path==="/dashboards/created/query" || path==="/dashboards/created/query-validation")result={dashboardId:"created",revision:metadata?.publishedRevision ?? 0,draftEtag:body.draftEtag ?? null,contextHash:"fixture",asOf:"2026-10-09T00:00:00Z",returnedAt:"2026-10-09T00:00:01Z",cached:false,panels:draft.definition.widgets.map((widget)=>({widgetId:widget.id,status:path.endsWith("query-validation")?"valid":"ready",data:{counts:{registered:3,fresh:0,unknown:3}},contextHash:"fixture",time:null,requestedCount:3,evaluatedCount:3,receivedAt:"2026-10-09T00:00:01Z",measuredAt:null,provenance:{},errorCode:null,explanation:null}))};
    return new Response(JSON.stringify(result),{status:path==="/dashboards" && method==="POST"?201:200,headers:{"Content-Type":"application/json"}});
  });
  function View({entry}:{entry:string}){return <MemoryRouter initialEntries={[entry]}><Routes><Route element={<AppLayout/>}><Route path="/dashboards" element={<PersonalDashboardsPage/>}/><Route path="/dashboards/:dashboardId" element={<PersonalDashboardPage/>}/><Route path="/dashboards/:dashboardId/edit" element={<PersonalDashboardPage/>}/><Route path="/dashboard" element={<DashboardDefaultPage/>}/></Route></Routes></MemoryRouter>;}
  const mounted=render(<View entry="/dashboards"/>);
  await screen.findByRole("heading",{name:"Dashboards"});expect(screen.queryByRole("link",{name:"Admin"})).not.toBeInTheDocument();
  expect(screen.queryByRole("textbox",{name:"Dashboard name"})).not.toBeInTheDocument();await userEvent.click(screen.getByRole("button",{name:"New dashboard"}));
  await userEvent.type(screen.getByRole("textbox",{name:"Dashboard name"}),"USER fleet");await userEvent.click(screen.getByRole("button",{name:"Create draft"}));
  await screen.findByText(/Edit private draft/);expect(screen.queryByRole("dialog",{name:"Unsaved changes"})).not.toBeInTheDocument();
  await userEvent.click(within(screen.getByRole("main")).getByRole("button",{name:"Hide navigation"}));
  expect(screen.queryByRole("navigation",{name:"Main navigation"})).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole("button",{name:"Show navigation"}));
  expect(screen.getByRole("navigation",{name:"Main navigation"})).toBeInTheDocument();
  const settingsButton=screen.getByRole("button",{name:"Dashboard settings"});
  await userEvent.click(settingsButton);const drawer=screen.getByRole("dialog",{name:"Dashboard settings"});
  expect(drawer).toHaveClass("console-dialog-side");expect(within(drawer).getByRole("textbox",{name:"Dashboard name"})).toHaveValue("USER fleet");
  fireEvent(drawer,new Event("cancel",{cancelable:true}));expect(screen.queryByRole("dialog",{name:"Dashboard settings"})).not.toBeInTheDocument();expect(settingsButton).toHaveFocus();
  await waitFor(()=>expect(screen.getByRole("button",{name:"Add widget"})).toBeEnabled());
  await userEvent.click(screen.getByRole("button",{name:"Add widget"}));await userEvent.click(screen.getByRole("button",{name:"Add Equipment overview"}));await userEvent.click(await screen.findByRole("button",{name:"Apply changes"}));
  await userEvent.click(screen.getByRole("button",{name:"Save draft now"}));await waitFor(()=>expect(screen.getByRole("button",{name:"Review publication"})).toBeEnabled());
  await userEvent.click(screen.getByRole("button",{name:"Review publication"}));await userEvent.click(screen.getByRole("button",{name:"Confirm publish"}));
  await screen.findByRole("link",{name:"Edit draft"});await userEvent.click(screen.getByRole("button",{name:"Dashboard settings"}));await userEvent.click(screen.getByRole("button",{name:"Manage"}));await userEvent.click(await screen.findByRole("button",{name:"Set as personal default"}));await screen.findByText("Personal default saved");await userEvent.click(screen.getByRole("button",{name:"Close"}));
  expect(await screen.findByText("Registered equipment")).toBeInTheDocument();expect(screen.getAllByText("3").length).toBeGreaterThan(0);
  expect(requests.find((request)=>request.path.endsWith("/publish"))?.headers.get("If-Match")).toBe('"draft-saved"');expect(requests.filter((request)=>request.path.endsWith("/publish"))).toHaveLength(1);
  mounted.unmount();const reloaded=render(<View entry="/dashboard"/>);await screen.findByRole("heading",{name:"USER fleet"});
  await userEvent.click(screen.getByRole("button",{name:"Dashboard settings"}));await userEvent.click(screen.getByRole("button",{name:"Manage"}));await userEvent.click(screen.getByRole("link",{name:"Open kiosk"}));await screen.findByText("Authenticated read-only kiosk");
  expect(screen.queryByRole("navigation",{name:"Main navigation"})).not.toBeInTheDocument();expect(screen.queryByRole("link",{name:"Edit draft"})).not.toBeInTheDocument();
  reloaded.unmount();const archivedRender=render(<View entry="/dashboard"/>);await screen.findByRole("heading",{name:"USER fleet"});
  await userEvent.click(screen.getByRole("button",{name:"Dashboard settings"}));await userEvent.click(screen.getByRole("button",{name:"Manage"}));await userEvent.click(screen.getByRole("button",{name:"Archive dashboard"}));await userEvent.click(screen.getByRole("button",{name:"Confirm archive"}));await screen.findByText(/Archived dashboard/);
  await userEvent.click(screen.getByRole("link",{name:"Dashboards"}));await screen.findByRole("heading",{name:"Dashboards"});archivedRender.unmount();render(<View entry="/dashboard"/>);await screen.findByText(/DEFAULT_ARCHIVED.*Showing system overview/);
  expect(preferences.defaultDashboardId).toBeNull();expect(requests.some((request)=>request.path.endsWith("/query") && request.body.revision===1)).toBe(true);
},15000);
