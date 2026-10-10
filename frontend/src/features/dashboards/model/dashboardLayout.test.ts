import { describe,expect,it } from "vitest";
import { blankDashboard,moveWidget,normalizeLayouts,resizeWidget } from "./dashboardLayout";
import type {DashboardWidget} from "../../../dashboards/dashboardsApi";
import {safeDefinition,readRecovery,writeRecovery} from "./dashboardRecovery";
import {clearTokens,setTokens} from "../../../api/client";
const widgets:DashboardWidget[]=["first","second"].map((id)=>({id,type:"fleet-count",typeVersion:1,title:id,binding:{mode:"ALL"},query:{},presentation:{},navigation:{},payload:{}}));
describe("dashboard layout and recovery invariants",()=>{
  it("resolves collisions and clamps legacy tablet placement while retaining desktop",()=>{
    const definition=normalizeLayouts({...blankDashboard("Fleet"),widgets,layouts:{desktop:[{widgetId:"first",x:0,y:0,w:3,h:3},{widgetId:"second",x:2,y:0,w:3,h:3}],tablet:[{widgetId:"first",x:6,y:0,w:2,h:3}],mobile:[]}});
    expect(definition.layouts.desktop[1].y).toBe(3);
    expect(definition.layouts.tablet.every((item)=>item.x+item.w<=6)).toBe(true);
    const moved=moveWidget(definition,"second",-1,"mobile");
    expect(moved.layouts.desktop).toEqual(definition.layouts.desktop);
    expect(moved.layouts.mobile[0].widgetId).toBe("second");
    const resized=resizeWidget(definition,"first",12,6,"desktop");
    expect(resized.layouts.desktop[1].y).toBe(6);
  });
  it("retains compact saved panels and useful default insertion sizes",()=>{
    const chart={...widgets[0],type:"time-series"};
    const definition=normalizeLayouts({...blankDashboard("Dense"),widgets:[chart],layouts:{desktop:[{widgetId:chart.id,x:0,y:0,w:2,h:1}],tablet:[],mobile:[]}});
    expect(definition.layouts.desktop[0]).toMatchObject({w:2,h:1});
    expect(definition.layouts.tablet[0]).toMatchObject({w:6,h:5});
    expect(resizeWidget(definition,chart.id,1,1,"desktop").layouts.desktop[0]).toMatchObject({w:1,h:1});
  });
  it("rejects credential/value payloads and isolates recovery by actor/account/base",()=>{
    localStorage.clear();const token=(sub:string)=>`${btoa("{}")} .${btoa(JSON.stringify({sub}))}.fixture`.replace(" ","");
    setTokens(token("owner"),"fixture");
    const metadata={id:"dashboard",accountId:"account",ownerUserId:"owner",name:"Fleet",description:"",status:"ACTIVE" as const,publishedRevision:1,createdAt:"",updatedAt:""};
    writeRecovery(metadata,1,blankDashboard("Recovered"));
    expect(readRecovery(metadata,1)?.name).toBe("Recovered");expect(readRecovery(metadata,2)).toBeNull();expect(readRecovery({...metadata,accountId:"other"},1)).toBeNull();
    expect(safeDefinition({payload:{readings:[120]}})).toBe(false);expect(safeDefinition({payload:{sourceToken:"secret"}})).toBe(false);
    setTokens(token("other"),"fixture");expect(readRecovery(metadata,1)).toBeNull();clearTokens();localStorage.clear();
  });
});
