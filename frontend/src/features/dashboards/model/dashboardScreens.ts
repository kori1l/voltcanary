import {commandKey,type DashboardDefinition,type DashboardScreen} from "../../../dashboards/dashboardsApi";

export function dashboardScreens(definition:DashboardDefinition):DashboardScreen[]{
  return definition.screens?.length?definition.screens:[{id:"main",title:"Overview",widgetIds:definition.widgets.map((widget)=>widget.id)}];
}
export function screenDefinition(definition:DashboardDefinition,screenId:string):DashboardDefinition{
  const screen=dashboardScreens(definition).find((screen)=>screen.id===screenId) ?? dashboardScreens(definition)[0];const ids=new Set(screen.widgetIds);
  return {...definition,screens:[screen],widgets:definition.widgets.filter((widget)=>ids.has(widget.id)),sections:definition.sections.filter((section)=>section.screenId?section.screenId===screen.id:section.widgetIds.some((id)=>ids.has(id)) || (screen.id===dashboardScreens(definition)[0].id && !section.widgetIds.length)).map((section)=>({...section,widgetIds:section.widgetIds.filter((id)=>ids.has(id))})),layouts:Object.fromEntries(Object.entries(definition.layouts).map(([key,placements])=>[key,placements.filter((position)=>ids.has(position.widgetId))])) as DashboardDefinition["layouts"]};
}
export function replaceScreenContent(definition:DashboardDefinition,screenId:string,content:DashboardDefinition):DashboardDefinition{
  const screens=dashboardScreens(definition),screen=screens.find((screen)=>screen.id===screenId) ?? screens[0];const previous=new Set(screen.widgetIds);const ids=content.widgets.map((widget)=>widget.id);
  const previousSections=new Set(screenDefinition(definition,screen.id).sections.map((section)=>section.id));
  return {...definition,widgets:[...definition.widgets.filter((widget)=>!previous.has(widget.id)),...content.widgets],sections:[...definition.sections.filter((section)=>!previousSections.has(section.id)),...content.sections],screens:definition.screens?.length || content.screens?.[0]?.grid?screens.map((item)=>item.id===screen.id?{...item,widgetIds:ids,grid:content.screens?.[0]?.grid ?? item.grid}:item):undefined,layouts:Object.fromEntries((["desktop","tablet","mobile"] as const).map((key)=>[key,[...(definition.layouts[key] ?? []).filter((position)=>!previous.has(position.widgetId)),...(content.layouts[key] ?? [])]])) as DashboardDefinition["layouts"]};
}
export function addScreen(definition:DashboardDefinition,title:string,id:string=commandKey()):DashboardDefinition{
  const screens=dashboardScreens(definition);if(screens.length>=8)return definition;
  return {...definition,screens:definition.widgets.length || definition.screens?.length?[...screens,{id,title,widgetIds:[]}]:[{id,title,widgetIds:[]}]};
}
export function transferWidget(definition:DashboardDefinition,widgetId:string,screenId:string):DashboardDefinition{
  if(!dashboardScreens(definition).some((screen)=>screen.id===screenId))return definition;
  return {...definition,screens:dashboardScreens(definition).map((screen)=>({...screen,widgetIds:[...screen.widgetIds.filter((id)=>id!==widgetId),...(screen.id===screenId?[widgetId]:[])]})),sections:definition.sections.map((section)=>({...section,widgetIds:section.widgetIds.filter((id)=>id!==widgetId)}))};
}
export function removeScreenPreservingWidgets(definition:DashboardDefinition,screenId:string):DashboardDefinition{
  const screens=dashboardScreens(definition);if(screens.length<2)return definition;const removed=screens.find((screen)=>screen.id===screenId);if(!removed)return definition;
  const target=screens.find((screen)=>screen.id!==screenId)!;
  return {...definition,screens:screens.filter((screen)=>screen.id!==screenId).map((screen)=>screen.id===target.id?{...screen,widgetIds:[...screen.widgetIds,...removed.widgetIds]}:screen),sections:definition.sections.map((section)=>section.screenId===screenId?{...section,screenId:target.id}:section)};
}
