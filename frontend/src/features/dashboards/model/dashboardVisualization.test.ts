import {describe,expect,it} from "vitest";
import type {DashboardWidget} from "../../../dashboards/dashboardsApi";
import type {WidgetQueryPanel} from "../../../dashboards/dashboardQueriesApi";
import {compatibleVisualizations,diagramEvidence} from "./dashboardVisualization";
const widget={type:"fleet-count"} as DashboardWidget;
const panel=(counts:Record<string,unknown>)=>({widgetId:"fleet",contextHash:"h",time:null,receivedAt:"2026-10-10T00:00:00Z",measuredAt:null,provenance:{},errorCode:null,explanation:null,status:"ready",requestedCount:6,evaluatedCount:6,data:{counts}} as WidgetQueryPanel);
describe("typed visualization evidence",()=>{
  it("uses disjoint categories and excludes overlapping active counts",()=>{const result=diagramEvidence(widget,panel({registered:6,active:6,fresh:2,stale:1,unknown:1,manualImport:1,sparse:1}));expect(result.total).toBe(6);expect(result.values.map(v=>v.label)).not.toContain("Active");});
  it("does not turn missing, negative or nonpartitioned counts into slices",()=>{for(const counts of [{registered:0,fresh:0,stale:0,unknown:0,manualImport:0},{registered:1,fresh:-1,stale:1,unknown:1,manualImport:0,sparse:0},{registered:10,fresh:1,stale:1,unknown:1,manualImport:0,sparse:0}])expect(diagramEvidence(widget,panel(counts)).values).toEqual([]);});
  it("labels zero total and excludes stale authorized evidence",()=>{expect(diagramEvidence(widget,panel({registered:0,fresh:0,stale:0,unknown:0,manualImport:0,sparse:0})).reason).toContain("Zero total");expect(diagramEvidence(widget,{...panel({}),status:"no_access"} as WidgetQueryPanel).values).toEqual([]);});
  it("does not offer pie for scalar or time series",()=>{expect(compatibleVisualizations("latest-value")).toEqual(["number"]);expect(compatibleVisualizations("time-series")).not.toContain("pie");});
  it("only partitions complete energy that matches device totals",()=>{const energyWidget={type:"energy-period"} as DashboardWidget;const energyPanel={...panel({}),data:{energy:{coverage:"COMPLETE",energyKWh:4,devices:[{deviceName:"A",energyKWh:1},{deviceName:"B",energyKWh:3}]}}} as WidgetQueryPanel;expect(diagramEvidence(energyWidget,energyPanel).total).toBe(4);expect(diagramEvidence(energyWidget,{...energyPanel,data:{energy:{...energyPanel.data!.energy as object,coverage:"PARTIAL"}}}).values).toEqual([]);expect(diagramEvidence(energyWidget,{...energyPanel,data:{energy:{...energyPanel.data!.energy as object,energyKWh:5}}}).values).toEqual([]);});
});
