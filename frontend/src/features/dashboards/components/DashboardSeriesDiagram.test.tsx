import {render,screen} from "@testing-library/react";
import {describe,it,expect} from "vitest";
import type {DashboardWidget} from "../../../dashboards/dashboardsApi";
import type {WidgetQueryPanel} from "../../../dashboards/dashboardQueriesApi";
import {DashboardSeriesDiagram} from "./DashboardSeriesDiagram";
const widget={type:"time-series",presentation:{visualization:"area",showLegend:true}} as unknown as DashboardWidget;
const bucket=(at:string,value:number,unit="W")=>({lastAt:at,mean:value,unit,gapCount:0});
const panel={status:"ready",requestedCount:2,evaluatedCount:2,time:{from:"2026-10-10T10:00:00Z",to:"2026-10-10T11:00:00Z"},data:{reducer:"AVG",devices:[{id:"a",name:"Compressor A"},{id:"b",name:"Compressor B"}],series:[{deviceId:"a",metric:"activePowerW",buckets:[bucket("2026-10-10T10:00:00Z",100),bucket("2026-10-10T10:10:00Z",200)]},{deviceId:"b",metric:"activePowerW",buckets:[bucket("2026-10-10T10:00:00Z",80),bucket("2026-10-10T10:10:00Z",120)]}]}} as unknown as WidgetQueryPanel;
describe("combined recorded charts",()=>{
  it("compares equipment with different stable colors and accessible values",()=>{const {container}=render(<DashboardSeriesDiagram widget={widget} panel={panel}/>);expect(screen.getByText("Compressor A")).toBeInTheDocument();expect(screen.getByText("Compressor B")).toBeInTheDocument();expect(new Set([...container.querySelectorAll("line[stroke]")].map(e=>e.getAttribute("stroke"))).size).toBe(2);expect(screen.getAllByRole("img")).toHaveLength(4);});
  it("rejects mixed units rather than showing a misleading shared scale",()=>{render(<DashboardSeriesDiagram widget={widget} panel={{...panel,data:{...panel.data,series:[(panel.data!.series as unknown[])[0],{deviceId:"b",metric:"temperatureC",buckets:[bucket("2026-10-10T10:00:00Z",5,"C")]}]}}}/>);expect(screen.getByText(/compatible recorded units/)).toBeInTheDocument();});
  it("retains a recorded gap between samples",()=>{const {container}=render(<DashboardSeriesDiagram widget={widget} panel={{...panel,data:{...panel.data,series:[{deviceId:"a",metric:"activePowerW",buckets:[bucket("2026-10-10T10:00:00Z",100),bucket("2026-10-10T10:50:00Z",200)]}]}}}/>);expect(container.querySelectorAll("line[stroke]")).toHaveLength(0);expect(container.querySelectorAll("path")).toHaveLength(0);});
});
