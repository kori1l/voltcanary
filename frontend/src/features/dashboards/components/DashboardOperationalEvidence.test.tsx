import {cleanup,render,screen} from "@testing-library/react";
import {afterEach,expect,it} from "vitest";
import type {DashboardWidget} from "../../../dashboards/dashboardsApi";
import {DashboardOperationalEvidence} from "./DashboardOperationalEvidence";
afterEach(cleanup);
it("keeps incomplete evidence visible in the compact summary",()=>{
  const widget:DashboardWidget={id:"operations",type:"uptime-or-cycle",typeVersion:1,title:"Operations",binding:{mode:"DYNAMIC"},query:{},presentation:{compactOperations:true},navigation:{},payload:{}};
  render(<DashboardOperationalEvidence widget={widget} data={{operations:[{completedCycles:2,cycleCoverage:"PARTIAL",uptime:{coverage:"UNKNOWN",observedSeconds:0,downtimeSeconds:0}}]}}/>);
  expect(screen.getByText("Partial or unknown observation")).toBeVisible();
  expect(screen.getByText("Partial or unknown cycle energy")).toBeVisible();
  expect(screen.getByText("Unknown")).toBeVisible();
});
