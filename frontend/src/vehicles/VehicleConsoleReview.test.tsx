import {render,screen,fireEvent,waitFor} from "@testing-library/react";
import {MemoryRouter,useNavigate} from "react-router-dom";
import {expect,it,vi} from "vitest";
import {VehicleConsole} from "./VehicleConsole";
import {buildAssistantContext} from "../aiAgent/assistantContext";
vi.mock("../devices/devicesApi",()=>({listDevices:vi.fn().mockResolvedValue([{id:"car",name:"Car",type:"VEHICLE"}]),listDataSources:vi.fn().mockResolvedValue([{id:"registered",externalId:"gps",protocol:"REST"}])}));
vi.mock("./monitoringApi",()=>({readMonitoring:vi.fn().mockResolvedValue({speedLimitKmh:80,idleMinutes:2,zones:[]}),fleetPositions:vi.fn().mockResolvedValue([])}));
vi.mock("./FleetMap",()=>({FleetMap:()=>null}));
vi.mock("./VehicleHistory",()=>({VehicleHistory:()=>null}));
vi.mock("./VehicleZones",()=>({VehicleZones:({reviewId,onDirtyChange}:{reviewId?:string;onDirtyChange:(value:boolean)=>void})=><div><span>Review: {reviewId??"none"}</span><button onClick={()=>onDirtyChange(true)}>Edit local draft</button><button onClick={()=>onDirtyChange(false)}>Discard local draft</button></div>}));
function ExternalResult(){const navigate=useNavigate();return <button onClick={()=>navigate("/vehicles?deviceId=car&section=ZONES&reviewId=ai-review")}>Open AI result</button>;}
it("provides only a bounded explicit map point and the registered source to AI",()=>{
  expect(buildAssistantContext("/vehicles","?deviceId=car&dataSourceId=registered&latitude=60&longitude=24")).toMatchObject({deviceId:"car",sourceId:"registered",vehiclePoint:{latitude:60,longitude:24}});
  expect(buildAssistantContext("/vehicles","?deviceId=car&latitude=91&longitude=24")).not.toHaveProperty("vehiclePoint");
});
it("retains an unsaved draft when an AI result changes the URL review",async()=>{
  render(<MemoryRouter initialEntries={["/vehicles?deviceId=car&section=ZONES"]}><ExternalResult/><VehicleConsole/></MemoryRouter>);
  fireEvent.click(await screen.findByRole("button",{name:"Edit local draft"}));
  fireEvent.click(screen.getByRole("button",{name:"Open AI result"}));
  expect(screen.getByText("Review: none")).toBeVisible();
  fireEvent.click(screen.getByRole("button",{name:"Discard local draft"}));
  await waitFor(()=>expect(screen.getByText("Review: ai-review")).toBeVisible());
});
