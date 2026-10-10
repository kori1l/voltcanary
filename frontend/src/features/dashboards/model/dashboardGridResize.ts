import type {Breakpoint,DashboardDefinition,DashboardGrid} from "../../../dashboards/dashboardsApi";
import {normalizeLayouts} from "./dashboardLayout";
import {dashboardScreens} from "./dashboardScreens";
export function resizeScreenGrid(definition:DashboardDefinition,breakpoint:Breakpoint,settings:DashboardGrid,screenId=dashboardScreens(definition)[0].id):DashboardDefinition{
  return normalizeLayouts({...definition,screens:dashboardScreens(definition).map((screen)=>screen.id===screenId?{...screen,grid:{...screen.grid,[breakpoint]:settings}}:screen)});
}
