import userEvent from "@testing-library/user-event";
import {cleanup,render,screen} from "@testing-library/react";
import {afterEach,expect,it} from "vitest";
import type {DashboardWidget} from "../../../dashboards/dashboardsApi";
import type {WidgetQueryPanel} from "../../../dashboards/dashboardQueriesApi";
import {DashboardWidgetPanel} from "./DashboardWidgetPanel";
const widget:DashboardWidget={id:"value",type:"latest-value",typeVersion:1,title:"Power",binding:{mode:"FIXED",deviceIds:["owned"]},query:{metric:"activePowerW"},presentation:{},navigation:{},payload:{}};
const panel:WidgetQueryPanel={widgetId:"value",status:"ready",data:{resultType:"VALUE",devices:[{id:"owned",name:"Fridge"}],value:{deviceId:"owned",metric:"activePowerW",unit:"W",value:0,measuredAt:"2026-10-09T12:00:00Z",quality:"OK"}},contextHash:"scope",time:null,requestedCount:1,evaluatedCount:1,receivedAt:"2026-10-09T12:00:01Z",measuredAt:"2026-10-09T12:00:00Z",provenance:{},errorCode:null,explanation:null};
afterEach(cleanup);
it("defaults to a value without retrieval prose and exposes metadata only on demand",async()=>{
  render(<DashboardWidgetPanel widget={widget} panel={panel} stale={false} onRetry={()=>{}}/>);
  expect(screen.queryByText("Recorded evidence")).not.toBeInTheDocument();expect(screen.queryByText(/^1 of 1$/)).not.toBeInTheDocument();expect(screen.queryByText(/Fridge · activePowerW/)).not.toBeInTheDocument();
  const opener=screen.getByRole("button",{name:"Information for Power"});await userEvent.click(opener);
  expect(screen.getByRole("dialog",{name:"Information for Power"})).toHaveClass("console-dialog-side");expect(screen.getByText(/^1 of 1$/)).toBeVisible();await userEvent.click(screen.getByRole("button",{name:"Close"}));expect(opener).toHaveFocus();
});
it("shows only enabled annotations while retaining stale and incomplete estimate badges",()=>{
  const view=render(<DashboardWidgetPanel widget={{...widget,presentation:{showLegend:true,showDetails:true}}} panel={panel} stale={true} onRetry={()=>{}}/>);
  expect(screen.getByText("Fridge · activePowerW")).toBeVisible();expect(screen.getByText(/^1 of 1$/)).toBeVisible();expect(screen.getByText("Stale")).toBeVisible();
  view.rerender(<DashboardWidgetPanel widget={{...widget,type:"energy-period"}} panel={{...panel,data:{energy:{energyKWh:0.5,coverage:"PARTIAL",coverageFraction:0.5,estimate:true}}}} stale={false} onRetry={()=>{}}/>);
  expect(screen.getByText(/PARTIAL coverage · 50%/)).toBeVisible();expect(screen.getByText("Estimate")).toBeVisible();expect(screen.queryByText(/Raw consumption includes/)).not.toBeInTheDocument();
});
it("shows partial energy and an undefined zero-baseline percentage without calling it a complete period",()=>{
  const energy={...panel,data:{energy:{energyKWh:0.5,coverage:"PARTIAL",coverageFraction:0.5,devicesWithEvidence:1,evaluatedDevices:2,estimate:false,devices:[{epochs:[{method:"COUNTER_DELTA"}]}],locations:[]},baselineEnergy:{energyKWh:0,coverage:"COMPLETE",coverageFraction:1},comparison:{difference:0.5,percent:null}}};
  render(<DashboardWidgetPanel widget={{...widget,type:"energy-period",presentation:{showCalculation:true,showComparison:true}}} panel={energy} stale={false} onRetry={()=>{}}/>);
  expect(screen.getByText("0.5",{selector:".dashboard-primary-value"})).toBeVisible();expect(screen.getByText("kWh",{selector:".dashboard-primary-value span"})).toBeVisible();
  expect(screen.getByText(/PARTIAL coverage/,{selector:".dashboard-state-badge"})).toBeVisible();expect(screen.getByText(/Percentage unavailable/)).toBeVisible();expect(screen.getByText(/Partial totals are not complete-period consumption/)).toBeVisible();
});
it("keeps unknown energy unavailable and purges it on loss of access",async()=>{
  const energyWidget={...widget,type:"energy-period"};const view=render(<DashboardWidgetPanel widget={energyWidget} panel={{...panel,status:"no_data",data:null}} stale={false} onRetry={()=>{}}/>);
  expect(screen.getByText("No data")).toBeVisible();await userEvent.click(screen.getByRole("button",{name:"Information for Power"}));expect(screen.getByText(/unknown consumption is not zero/)).toBeVisible();await userEvent.click(screen.getByRole("button",{name:"Close"}));expect(screen.queryByText("0",{selector:".dashboard-primary-value"})).not.toBeInTheDocument();
  view.rerender(<DashboardWidgetPanel widget={energyWidget} panel={{...panel,status:"no_access",data:null}} stale={true} onRetry={()=>{}}/>);expect(screen.queryByText("kWh")).not.toBeInTheDocument();
});
it("emphasizes a recorded zero and unit while technical fields remain in closed details",()=>{
  render(<DashboardWidgetPanel widget={widget} panel={panel} stale={false} onRetry={()=>{}}/>);expect(screen.getByText("W",{selector:".dashboard-primary-value span"})).toBeVisible();expect(screen.getByText("0",{selector:".dashboard-primary-value"})).toBeVisible();expect(screen.queryByText("result Type")).not.toBeInTheDocument();
});
it("never exposes stale values when a panel is denied or has no data",()=>{
  const view=render(<DashboardWidgetPanel widget={widget} panel={{...panel,status:"no_access",data:null}} stale={false} onRetry={()=>{}}/>);expect(screen.queryByText("W")).not.toBeInTheDocument();view.rerender(<DashboardWidgetPanel widget={widget} panel={{...panel,status:"no_data",data:null}} stale={false} onRetry={()=>{}}/>);expect(screen.queryByText("0",{selector:".dashboard-primary-value"})).not.toBeInTheDocument();
});
it("plots observed envelope ranges without bridging recorded gaps",()=>{
  const evidence={...panel,widgetId:"series",time:{from:"2026-10-09T10:00:00Z",to:"2026-10-09T12:00:00Z",asOf:"2026-10-09T12:00:00Z",timezone:"UTC",override:false,compareFrom:null,compareTo:null,comparisonBasis:"NONE"},data:{devices:[{id:"owned",name:"Fridge"}],series:[{deviceId:"owned",metric:"activePowerW",buckets:[{minimum:0,maximum:20,first_value:0,last_value:20,sampleCount:3,gapCount:1,firstAt:"2026-10-09T10:00:00Z",lastAt:"2026-10-09T12:00:00Z",minimumAt:"2026-10-09T10:00:00Z",maximumAt:"2026-10-09T12:00:00Z",unit:"W"}]}]}};
  const {container}=render(<DashboardWidgetPanel widget={{...widget,id:"series",type:"time-series"}} panel={evidence} stale={false} onRetry={()=>{}}/>);expect(screen.getByRole("img",{name:"Fridge · activePowerW chart"})).toBeVisible();expect(container.querySelectorAll("polyline")).toHaveLength(0);expect(screen.queryByText(/Blank intervals and gaps are not interpolated/)).not.toBeInTheDocument();expect(screen.getByText(/source points/,{selector:"p"})).not.toBeVisible();
});
