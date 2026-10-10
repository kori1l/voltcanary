import type {Breakpoint,DashboardDefinition,DashboardWidget,Placement} from "../../../dashboards/dashboardsApi";
import {normalizeLayouts} from "./dashboardLayout";

/** Temporary drop identifiers never enter the saved dashboard definition. */
export function insertWidget(definition:DashboardDefinition,widget:DashboardWidget,breakpoint:Breakpoint,drop?:{positions:Placement[];temporaryId:string;sectionId?:string}):DashboardDefinition{
  let next={...definition,widgets:[...definition.widgets,widget],sections:definition.sections.map((section)=>section.id===drop?.sectionId?{...section,widgetIds:[...section.widgetIds,widget.id]}:section)};
  if(drop){
    const allowed=new Set([...definition.widgets.map((w)=>w.id),widget.id]);
    const placements=drop.positions.map((p)=>({...p,widgetId:p.widgetId===drop.temporaryId?widget.id:p.widgetId})).filter((p)=>allowed.has(p.widgetId));
    const changed=new Set(placements.map((p)=>p.widgetId));
    next={...next,layouts:{...next.layouts,[breakpoint]:[...(next.layouts[breakpoint] ?? []).filter((p)=>!changed.has(p.widgetId)),...placements]}};
  }
  return normalizeLayouts(next);
}
