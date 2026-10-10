import {workingGrid} from "./dashboardGrid";
import type { Breakpoint, DashboardDefinition, DashboardWidget, Placement } from "../../../dashboards/dashboardsApi";
import type { WidgetManifest } from "../../../dashboards/dashboardQueriesApi";
export const columns: Record<Breakpoint, number> = {desktop:12,tablet:6,mobile:1};
export function blankDashboard(name: string, description = ""): DashboardDefinition {
  return {schemaVersion:1,name:name.trim(),description:description.trim(),defaultContext:{time:{mode:"RELATIVE",preset:"LAST_24_HOURS",timezone:Intl.DateTimeFormat().resolvedOptions().timeZone}},sections:[],widgets:[],layouts:{desktop:[],tablet:[],mobile:[]}};
}
const manifestSizes=new Map<string,NonNullable<WidgetManifest["sizeConstraints"]>>();
export function registerManifestSizes(manifests:WidgetManifest[]){for(const manifest of manifests)if(manifest.sizeConstraints)manifestSizes.set(`${manifest.type}:${manifest.typeVersion}`,manifest.sizeConstraints);}
export function widgetSize(type: string, breakpoint: Breakpoint, version=1,maxColumns=columns[breakpoint]) {
  const size=manifestSizes.get(`${type}:${version}`);
  if(size)return {w:Math.min(maxColumns,size[breakpoint==="desktop"?"desktopMinColumns":breakpoint==="tablet"?"tabletMinColumns":"mobileMinColumns"]),h:size.minRows};
  return {w:1,h:1};
}
export function widgetDefaultSize(type:string,breakpoint:Breakpoint,version=1,maxColumns=columns[breakpoint]) {
  const minimum=widgetSize(type,breakpoint,version,maxColumns);
  const table = ["incident-list","maintenance-agenda","risk-list","location-matrix","time-series","prediction-evidence","peak-and-ranking"].includes(type);
  return {w:Math.max(minimum.w,Math.min(maxColumns,table ? 6 : 3)),h:Math.max(minimum.h,table ? 5 : 3)};
}
const overlaps = (a:Placement,b:Placement) => a.x < b.x+b.w && b.x < a.x+a.w && a.y < b.y+b.h && b.y < a.y+a.h;
/** Deterministic downward collision resolution; incomplete/legacy layouts stay readable. */
export function resolveLayout(widgets: DashboardWidget[], positions: Placement[], breakpoint: Breakpoint,maxColumns=columns[breakpoint]): Placement[] {
  const result: Placement[] = [];
  for (const widget of widgets) {
    const saved = positions.find((item) => item.widgetId === widget.id);
    const minimum = widgetSize(widget.type,breakpoint,widget.typeVersion,maxColumns);
    const defaults=widgetDefaultSize(widget.type,breakpoint,widget.typeVersion,maxColumns);
    const w = Math.min(maxColumns,Math.max(minimum.w,saved?.w ?? defaults.w));
    const next: Placement = {widgetId:widget.id,x:Math.min(Math.max(0,saved?.x ?? 0),maxColumns-w),y:Math.max(0,saved?.y ?? 0),w,h:Math.min(100,Math.max(minimum.h,saved?.h ?? defaults.h))};
    if(!saved){const rows=[0,...result.map((item)=>item.y+item.h)].sort((a,b)=>a-b);let placed=false;for(const y of rows){for(let x=0;x<=maxColumns-w;x++){const candidate={...next,x,y};if(!result.some((item)=>overlaps(item,candidate))){next.x=x;next.y=y;placed=true;break;}}if(placed)break;}}
    while (result.some((item) => overlaps(item,next))) next.y = Math.max(...result.filter((item) => overlaps(item,next)).map((item) => item.y+item.h));
    result.push(next);
  }
  return result;
}
export function normalizeLayouts(definition: DashboardDefinition): DashboardDefinition {
  const layouts = {...definition.layouts};
  for (const breakpoint of Object.keys(columns) as Breakpoint[]) layouts[breakpoint] = layoutGroups(definition).flatMap((widgets)=>resolveLayout(widgets,layouts[breakpoint] ?? [],breakpoint,workingGrid(definition,breakpoint,widgets[0]?.id).columns));
  return {...definition,layouts};
}
/** Screens and sections are separate placement surfaces, never one giant grid. */
export function layoutGroups(definition:DashboardDefinition):DashboardWidget[][] {
  const screens=definition.screens?.length===1?[definition.widgets]:definition.screens?.length?definition.screens.map((screen)=>definition.widgets.filter((widget)=>screen.widgetIds.includes(widget.id))):[definition.widgets];
  return screens.flatMap((widgets)=>{const assigned=new Set<string>();const sections=definition.sections.map((section)=>{const group=widgets.filter((widget)=>section.widgetIds.includes(widget.id) && !assigned.has(widget.id));group.forEach((widget)=>assigned.add(widget.id));return group;});return [...sections,widgets.filter((widget)=>!assigned.has(widget.id))].filter((group)=>group.length);});
}
function surface(definition:DashboardDefinition,id:string){return layoutGroups(definition).find((group)=>group.some((widget)=>widget.id===id)) ?? [];}
function commitSurface(definition:DashboardDefinition,widgets:DashboardWidget[],placements:Placement[],breakpoint:Breakpoint){
  const ids=new Set(widgets.map((widget)=>widget.id));return {...definition,layouts:{...definition.layouts,[breakpoint]:[...placements.sort((a,b)=>a.y-b.y || a.x-b.x),...(definition.layouts[breakpoint] ?? []).filter((position)=>!ids.has(position.widgetId))]}};
}
function pin(widgets:DashboardWidget[],placements:Placement[],id:string,position:Placement,breakpoint:Breakpoint,anchorId?:string,maxColumns=columns[breakpoint]){
  const selected=widgets.find((widget)=>widget.id===id);if(!selected)return placements;
  const result=resolveLayout([selected],[position],breakpoint,maxColumns);
  const neighbours=widgets.filter((widget)=>widget.id!==id).flatMap((widget)=>resolveLayout([widget],placements.filter((item)=>item.widgetId===widget.id),breakpoint,maxColumns)).sort((a,b)=>a.widgetId===anchorId?-1:b.widgetId===anchorId?1:a.y-b.y || a.x-b.x);
  for(const original of neighbours){const next={...original};while(result.some((item)=>overlaps(item,next)))next.y=Math.max(...result.filter((item)=>overlaps(item,next)).map((item)=>item.y+item.h));result.push(next);}
  return result;
}
export type RelativePosition="above"|"below"|"left"|"right";
export function placeAtStart(definition:DashboardDefinition,id:string,breakpoint:Breakpoint):DashboardDefinition{
  const scoped={...definition,sections:definition.sections.map((section)=>({...section,widgetIds:section.widgetIds.filter((value)=>value!==id)}))};
  const widgets=surface(scoped,id),positions=resolveLayout(widgets,scoped.layouts[breakpoint] ?? [],breakpoint,workingGrid(scoped,breakpoint,id).columns);const position=positions.find((item)=>item.widgetId===id);if(!position)return definition;
  return commitSurface(scoped,widgets,pin(widgets,positions,id,{...position,x:0,y:0},breakpoint,undefined,workingGrid(scoped,breakpoint,id).columns),breakpoint);
}
export function relativePlacementReason(definition:DashboardDefinition,id:string,anchorId:string,direction:RelativePosition,breakpoint:Breakpoint):string|null{
  if(id===anchorId)return "Choose another widget as the anchor.";
  const widget=definition.widgets.find((widget)=>widget.id===id),anchor=definition.widgets.find((widget)=>widget.id===anchorId);if(!widget || !anchor)return "Choose an existing anchor.";
  if(definition.screens?.length && definition.screens.find((screen)=>screen.widgetIds.includes(id))?.id!==definition.screens.find((screen)=>screen.widgetIds.includes(anchorId))?.id)return "Move the widget to this screen before choosing its anchor.";
  if(direction==="above" || direction==="below")return null;
  const selected=resolveLayout(surface(definition,id),definition.layouts[breakpoint] ?? [],breakpoint,workingGrid(definition,breakpoint,id).columns).find((item)=>item.widgetId===id)!,target=resolveLayout(surface(definition,anchorId),definition.layouts[breakpoint] ?? [],breakpoint,workingGrid(definition,breakpoint,anchorId).columns).find((item)=>item.widgetId===anchorId)!;
  if(selected.w+target.w>workingGrid(definition,breakpoint,id).columns)return `These widgets need ${selected.w+target.w} columns; ${breakpoint} has ${workingGrid(definition,breakpoint,id).columns}. Choose above/below or resize first.`;
  if(direction==="right" && target.x+target.w+selected.w>workingGrid(definition,breakpoint,id).columns)return "No space to the right at this width. Choose below or move/resize the anchor.";
  return null;
}
export function placeRelative(definition:DashboardDefinition,id:string,anchorId:string,direction:RelativePosition,breakpoint:Breakpoint):DashboardDefinition{
  if(relativePlacementReason(definition,id,anchorId,direction,breakpoint))return definition;
  const targetSection=definition.sections.find((section)=>section.widgetIds.includes(anchorId));
  const scoped={...definition,sections:definition.sections.map((section)=>({...section,widgetIds:[...section.widgetIds.filter((value)=>value!==id),...(section.id===targetSection?.id?[id]:[])]}))};
  const widgets=surface(scoped,anchorId),placements=resolveLayout(widgets,scoped.layouts[breakpoint] ?? [],breakpoint,workingGrid(scoped,breakpoint,id).columns);const selected={...placements.find((item)=>item.widgetId===id)!},anchor=placements.find((item)=>item.widgetId===anchorId)!;
  selected.x=Math.min(anchor.x,workingGrid(scoped,breakpoint,id).columns-selected.w);selected.y=direction==="below"?anchor.y+anchor.h:anchor.y;
  if(direction==="right")selected.x=anchor.x+anchor.w;
  if(direction==="left"){if(anchor.x>=selected.w)selected.x=anchor.x-selected.w;else{selected.x=0;anchor.x=selected.w;}}
  return commitSurface(scoped,widgets,pin(widgets,placements,id,selected,breakpoint,anchorId,workingGrid(scoped,breakpoint,id).columns),breakpoint);
}
export function moveWidget(definition: DashboardDefinition,id:string,direction:-1|1,breakpoint:Breakpoint) {
  const ordered = resolveLayout(surface(definition,id),definition.layouts[breakpoint] ?? [],breakpoint,workingGrid(definition,breakpoint,id).columns).sort((a,b)=>a.y-b.y || a.x-b.x);
  const index = ordered.findIndex((item)=>item.widgetId===id), target = index+direction;
  if(index<0 || target<0 || target>=ordered.length) return definition;
  return placeRelative(definition,id,ordered[target].widgetId,direction===-1?"above":"below",breakpoint);
}
export function resizeWidget(definition: DashboardDefinition,id:string,w:number,h:number,breakpoint:Breakpoint) {
  const widgets=surface(definition,id),placements=resolveLayout(widgets,definition.layouts[breakpoint] ?? [],breakpoint,workingGrid(definition,breakpoint,id).columns);const position=placements.find((item)=>item.widgetId===id);if(!position)return definition;
  return commitSurface(definition,widgets,pin(widgets,placements,id,{...position,w,h},breakpoint,undefined,workingGrid(definition,breakpoint,id).columns),breakpoint);
}
export function shiftWidget(definition:DashboardDefinition,id:string,direction:-1|1,breakpoint:Breakpoint){
  const widgets=surface(definition,id),placements=resolveLayout(widgets,definition.layouts[breakpoint] ?? [],breakpoint,workingGrid(definition,breakpoint,id).columns);const position=placements.find((item)=>item.widgetId===id);if(!position)return definition;
  return commitSurface(definition,widgets,pin(widgets,placements,id,{...position,x:Math.max(0,Math.min(workingGrid(definition,breakpoint,id).columns-position.w,position.x+direction))},breakpoint,undefined,workingGrid(definition,breakpoint,id).columns),breakpoint);
}
export function removeWidget(definition:DashboardDefinition,id:string) {
  return normalizeLayouts({...definition,screens:definition.screens?.map((screen)=>({...screen,widgetIds:screen.widgetIds.filter((value)=>value!==id)})),widgets:definition.widgets.filter((widget)=>widget.id!==id),sections:definition.sections.map((section)=>({...section,widgetIds:section.widgetIds.filter((value)=>value!==id)}))});
}
