import {describe,expect,it} from "vitest";
import type {DashboardWidget} from "../../../dashboards/dashboardsApi";
import {blankDashboard,normalizeLayouts,placeRelative,relativePlacementReason,resizeWidget} from "./dashboardLayout";
import {addScreen,dashboardScreens,screenDefinition,replaceScreenContent,removeScreenPreservingWidgets,transferWidget} from "./dashboardScreens";
const widget=(id:string):DashboardWidget=>({id,type:"fleet-count",typeVersion:1,title:id,binding:{mode:"ALL"},query:{},presentation:{},navigation:{},payload:{}});
describe("working screens and pinned relative placement",()=>{
  it("retains new placements when an older definition has no stored layouts",()=>{
    const legacy={...blankDashboard("Legacy"),layouts:{} as ReturnType<typeof blankDashboard>["layouts"]};
    const content=normalizeLayouts({...screenDefinition(legacy,"main"),widgets:[widget("added")]});
    const updated=replaceScreenContent(legacy,"main",content);
    expect(updated.layouts.desktop).toEqual([{widgetId:"added",x:0,y:0,w:3,h:3}]);
    expect(updated.layouts.mobile[0]).toMatchObject({widgetId:"added",w:1});
    expect(placeRelative({...legacy,widgets:[widget("a"),widget("b")]},"b","a","right","desktop").layouts.desktop.find((position)=>position.widgetId==="b")).toMatchObject({x:3,y:0});
  });
  it("preserves legacy content, separates screens, transfers and removes without dropping widgets",()=>{
    const legacy=normalizeLayouts({...blankDashboard("Work"),widgets:[widget("first")]});expect(dashboardScreens(legacy)[0].widgetIds).toEqual(["first"]);
    let definition=addScreen(legacy,"Analytics","analytics");const content=normalizeLayouts({...screenDefinition(definition,"analytics"),widgets:[widget("chart")]});definition=replaceScreenContent(definition,"analytics",content);
    expect(screenDefinition(definition,"main").widgets.map((widget)=>widget.id)).toEqual(["first"]);expect(screenDefinition(definition,"analytics").widgets.map((widget)=>widget.id)).toEqual(["chart"]);
    const transferred=normalizeLayouts(transferWidget(definition,"first","analytics"));expect(screenDefinition(transferred,"main").widgets).toHaveLength(0);
    const preserved=removeScreenPreservingWidgets(transferred,"analytics");expect(preserved.widgets).toHaveLength(2);expect(dashboardScreens(preserved)[0].widgetIds).toEqual(["chart","first"]);
  });
  it("pins an insertion right/left of the anchor and only displaces colliding neighbours",()=>{
    const definition=normalizeLayouts({...blankDashboard("Work"),widgets:[widget("neighbour"),widget("anchor"),widget("new")],layouts:{desktop:[{widgetId:"anchor",x:0,y:0,w:3,h:3},{widgetId:"neighbour",x:3,y:0,w:3,h:3},{widgetId:"new",x:0,y:3,w:3,h:3}],tablet:[],mobile:[]}});
    const right=placeRelative(definition,"new","anchor","right","desktop");const at=(id:string)=>right.layouts.desktop.find((position)=>position.widgetId===id)!;
    expect(at("new")).toMatchObject({x:3,y:0});expect(at("anchor")).toMatchObject({x:0,y:0});expect(at("neighbour").y).toBe(3);
    const left=placeRelative(definition,"new","anchor","left","desktop");expect(left.layouts.desktop.find((position)=>position.widgetId==="new")).toMatchObject({x:0,y:0});expect(left.layouts.desktop.find((position)=>position.widgetId==="anchor")).toMatchObject({x:3,y:0});
    expect(relativePlacementReason(definition,"new","anchor","right","mobile")).toContain("columns");
  });
  it("preserves another screen and mobile placements during resize or above/below movement",()=>{
    const definition=normalizeLayouts({...blankDashboard("Work"),widgets:[widget("one"),widget("two"),widget("other")],screens:[{id:"monitoring",title:"Monitoring",widgetIds:["one","two"]},{id:"analytics",title:"Analytics",widgetIds:["other"]}]});
    const resized=resizeWidget(definition,"one",12,6,"desktop");expect(resized.layouts.mobile).toEqual(definition.layouts.mobile);expect(resized.layouts.desktop.find((position)=>position.widgetId==="other")).toEqual(definition.layouts.desktop.find((position)=>position.widgetId==="other"));
    const above=placeRelative(definition,"two","one","above","mobile");expect(above.layouts.mobile.find((position)=>position.widgetId==="two")?.y).toBe(0);expect(above.layouts.desktop).toEqual(definition.layouts.desktop);
    expect(relativePlacementReason(definition,"one","other","right","desktop")).toContain("this screen");
  });
});
