import {verticalCompactor} from "react-grid-layout";
import type {Breakpoint,DashboardDefinition} from "../../../dashboards/dashboardsApi";
import {workingGrid} from "./dashboardGrid";
import {layoutGroups,resolveLayout} from "./dashboardLayout";
/** Operates on the projected active screen; other responsive placements are untouched. */
export function compactGridLayout(screen:DashboardDefinition,breakpoint:Breakpoint):DashboardDefinition{
  const compacted=layoutGroups(screen).flatMap((widgets)=>verticalCompactor.compact(resolveLayout(widgets,screen.layouts[breakpoint] ?? [],breakpoint,workingGrid(screen,breakpoint).columns).map(({widgetId,...p})=>({...p,i:widgetId})),workingGrid(screen,breakpoint).columns).map(({i,x,y,w,h})=>({widgetId:i,x,y,w,h})));
  return {...screen,layouts:{...screen.layouts,[breakpoint]:compacted}};
}
