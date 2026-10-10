import {cleanup,render,screen,waitFor} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {MemoryRouter,Route,Routes} from "react-router-dom";
import {afterEach,expect,it,vi} from "vitest";
import {createDashboard} from "../../../dashboards/dashboardsApi";
import {listDevices} from "../../../devices/devicesApi";
import {getTelemetryMetrics} from "../../../telemetry/telemetryApi";
import {listDashboardTemplates,type DashboardTemplate} from "../model/dashboardTemplates";
import {DashboardTemplateWizard} from "./DashboardTemplateWizard";
vi.mock("../model/dashboardTemplates",async(importOriginal)=>({...await importOriginal<typeof import("../model/dashboardTemplates")>(),listDashboardTemplates:vi.fn()}));
vi.mock("../../../dashboards/dashboardsApi",async(importOriginal)=>({...await importOriginal<typeof import("../../../dashboards/dashboardsApi")>(),createDashboard:vi.fn()}));
vi.mock("../../../devices/devicesApi",()=>({listDevices:vi.fn(),listLocations:async()=>[]}));
vi.mock("../../../telemetry/telemetryApi",()=>({getTelemetryMetrics:vi.fn()}));
const template:DashboardTemplate={id:"refrigeration",version:1,title:"Refrigeration",task:"Review measured facts",persona:"Manager",segments:["Café"],available:true,explanation:"",period:"LAST_24_HOURS",seriesHours:24,slots:["primary"],panels:[
  {key:"temperature",type:"latest-value",title:"Temperature",slot:"primary",metric:"temperatureC",unit:"C",query:{reducer:"RAW"},drillDown:"/devices"},
  {key:"power",type:"time-series",title:"Power",slot:"primary",metric:"activePowerW",unit:"W",query:{reducer:"RAW"},drillDown:"/devices"}]};
afterEach(()=>{cleanup();vi.resetAllMocks();});
it("requires rebinding when a formerly selectable device is no longer in current authorized scope",async()=>{
  vi.mocked(listDashboardTemplates).mockResolvedValue([template]);
  vi.mocked(listDevices).mockResolvedValue([{id:"old-binding",name:"Equipment",status:"ACTIVE",type:"FRIDGE",locationId:null,manufacturer:null,model:null}]);
  render(<MemoryRouter><DashboardTemplateWizard/></MemoryRouter>);
  await userEvent.click(await screen.findByRole("button",{name:"Bind Refrigeration"}));
  await waitFor(()=>expect(screen.getByRole("combobox",{name:"Equipment slot 1 (primary measurement)"})).toBeEnabled());
  await userEvent.selectOptions(screen.getByRole("combobox",{name:"Equipment slot 1 (primary measurement)"}),"old-binding");
  vi.mocked(listDevices).mockResolvedValue([]);
  await userEvent.click(screen.getByRole("button",{name:"Check current bindings and capabilities"}));
  await screen.findByText(/A binding is no longer authorized/);expect(screen.getByRole("button",{name:"Create template draft and preview"})).toBeDisabled();expect(createDashboard).not.toHaveBeenCalled();
});
it("checks real capability gaps, keeps compatible power and retries exactly the same private snapshot",async()=>{
  vi.mocked(listDashboardTemplates).mockResolvedValue([template]);
  vi.mocked(listDevices).mockResolvedValue([{id:"owned",name:"Demo fridge",status:"ACTIVE",type:"FRIDGE",locationId:null,manufacturer:null,model:null}]);
  vi.mocked(getTelemetryMetrics).mockResolvedValue([{name:"activePowerW",unit:"W",displayName:"Power",type:"GAUGE",min:0,max:20}]);
  vi.mocked(createDashboard).mockRejectedValueOnce(new Error("Transport failed")).mockResolvedValueOnce({id:"copy",ownerUserId:"owner",accountId:"account",name:"Refrigeration",description:"",status:"ACTIVE",publishedRevision:0,createdAt:"",updatedAt:""});
  render(<MemoryRouter><Routes><Route path="/" element={<DashboardTemplateWizard/>}/><Route path="/dashboards/:id/edit" element={<h1>Independent template preview</h1>}/></Routes></MemoryRouter>);
  await userEvent.click(await screen.findByRole("button",{name:"Bind Refrigeration"}));
  await waitFor(()=>expect(screen.getByRole("combobox",{name:"Equipment slot 1 (primary measurement)"})).toBeEnabled());
  await userEvent.selectOptions(screen.getByRole("combobox",{name:"Equipment slot 1 (primary measurement)"}),"owned");
  await userEvent.click(screen.getByRole("button",{name:"Check current bindings and capabilities"}));
  await screen.findByText(/Missing registered metric.*Temperature.*temperatureC/);
  await userEvent.selectOptions(screen.getByRole("combobox",{name:"Unavailable panels"}),"remove");
  await userEvent.click(screen.getByRole("button",{name:"Create template draft and preview"}));await screen.findByText("Transport failed");
  await userEvent.click(screen.getByRole("button",{name:"Create template draft and preview"}));await screen.findByRole("heading",{name:"Independent template preview"});
  expect(createDashboard).toHaveBeenCalledTimes(2);expect(vi.mocked(createDashboard).mock.calls[1]).toEqual(vi.mocked(createDashboard).mock.calls[0]);
  const definition=vi.mocked(createDashboard).mock.calls[0][0];expect(definition.widgets.some((widget)=>widget.query.metric==="temperatureC")).toBe(false);expect(definition.widgets.find((widget)=>widget.type==="time-series")?.query.unit).toBe("W");
  expect(definition.widgets[0].title).toContain("DEMO");
},15000);
