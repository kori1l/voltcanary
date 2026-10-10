import type {Breakpoint,DashboardDefinition,DashboardGrid} from "../../../dashboards/dashboardsApi";
import {dashboardScreens} from "./dashboardScreens";
export const gridDefaults:Record<Breakpoint,DashboardGrid>={desktop:{columns:12,rowHeight:48,gap:12},tablet:{columns:6,rowHeight:48,gap:12},mobile:{columns:1,rowHeight:48,gap:12}};
export function workingGrid(definition:DashboardDefinition,breakpoint:Breakpoint,widgetId?:string):DashboardGrid{
  const screens=dashboardScreens(definition);const screen=widgetId?screens.find((s)=>s.widgetIds.includes(widgetId)) ?? screens[0]:screens[0];
  return screen.grid?.[breakpoint] ?? gridDefaults[breakpoint];
}
