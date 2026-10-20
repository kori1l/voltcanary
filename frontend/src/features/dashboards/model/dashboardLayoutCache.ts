import {subscribeSession} from "../../../api/client";
import type {Breakpoint,DashboardDefinition,DashboardGrid,Placement} from "../../../dashboards/dashboardsApi";
import {workingGrid} from "./dashboardGrid";
import {dashboardScreens,screenDefinition} from "./dashboardScreens";
import {resolveLayout} from "./dashboardLayout";

export type LayoutHint=Record<Breakpoint,{positions:Placement[];grid:DashboardGrid}>;
const layouts=new Map<string,LayoutHint>();
subscribeSession(()=>layouts.clear());
export function rememberDashboardLayout(id:string,definition:DashboardDefinition,screenId?:string|null){
  const screen=screenDefinition(definition,screenId ?? dashboardScreens(definition)[0].id);
  const hint=Object.fromEntries((["desktop","tablet","mobile"] as const).map(key=>{
    const grid=workingGrid(screen,key);
    return [key,{positions:resolveLayout(screen.widgets,screen.layouts[key] ?? [],key,grid.columns),grid}];
  })) as LayoutHint;
  layouts.delete(id);while(layouts.size>=8)layouts.delete(layouts.keys().next().value!);layouts.set(id,hint);
}
export const dashboardLayoutHint=(id:string)=>layouts.get(id);
export const forgetDashboardLayout=(id:string)=>layouts.delete(id);
