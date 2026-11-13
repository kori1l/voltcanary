import {render,screen,waitFor} from "@testing-library/react";
import {afterEach,expect,it,vi} from "vitest";
import {cleanup} from "@testing-library/react";
import {VehicleFieldSelection} from "./VehicleFieldSelection";
import type {DashboardDataChoice} from "../features/dashboards/model/dashboardCapabilitiesApi";
const load=vi.hoisted(()=>vi.fn());
vi.mock("../features/dashboards/model/dashboardCapabilitiesApi",()=>({dashboardCapabilities:load}));
afterEach(()=>{cleanup();vi.clearAllMocks();});
const choice=(key:string,type:string,views:string[]):DashboardDataChoice=>({choiceId:key,deviceId:"bus",sourceId:"source",externalSourceId:"recorded",family:"RECORDED_VEHICLE_FIELD",key,label:key,valueType:type,unit:"",currentStatus:"OBSERVED",sourceFreshness:"STALE",historyStatus:"UNKNOWN",views:[{widgetType:"vehicle-timeline",typeVersion:1,purpose:"HISTORY",visualizations:views,readiness:"COMPATIBLE",requirements:[]}]});
it("uses server view choices and keeps text unavailable for line history",async()=>{
 load.mockResolvedValue({choices:[choice("doors","boolean",["list","line"]),choice("stop","string",["list"])]});
 const valid=vi.fn();
 const view=render(<VehicleFieldSelection deviceId="bus" sourceId="recorded" value="stop" widgetType="vehicle-timeline" visualization="line" onChange={()=>{}} onValidityChange={valid}/>);
 await waitFor(()=>expect(screen.getByRole("option",{name:"stop · string"})).toBeDisabled());
 expect(screen.getByRole("option",{name:"doors · boolean"})).toBeEnabled();
 expect(valid).toHaveBeenLastCalledWith(false);
 view.rerender(<VehicleFieldSelection deviceId="bus" sourceId="recorded" value="stop" widgetType="vehicle-timeline" visualization="list" onChange={()=>{}} onValidityChange={valid}/>);
 await waitFor(()=>expect(valid).toHaveBeenLastCalledWith(true));
 expect(load).toHaveBeenCalledTimes(1);
});
it("does not replace unavailable capabilities with a successful empty field list",async()=>{
 load.mockRejectedValue(new Error("offline"));const valid=vi.fn();
 render(<VehicleFieldSelection deviceId="bus" sourceId="recorded" value="doors" widgetType="vehicle-timeline" onChange={()=>{}} onValidityChange={valid}/>);
 await screen.findByText("Recorded field capabilities are unavailable.");
 expect(valid).toHaveBeenLastCalledWith(false);
});
