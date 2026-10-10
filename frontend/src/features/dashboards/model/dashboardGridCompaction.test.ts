import {expect,it} from "vitest";
import {blankDashboard,normalizeLayouts} from "./dashboardLayout";
import {screenDefinition,replaceScreenContent} from "./dashboardScreens";
import {compactGridLayout} from "./dashboardGridCompaction";
it("compacts only the selected screen and breakpoint without modifying the input",()=>{
 const original=normalizeLayouts({...blankDashboard("Grid"),widgets:["a","b","other"].map((id)=>({id,title:id,type:"fleet-count",typeVersion:1,binding:{mode:"ALL" as const},query:{},presentation:{},navigation:{},payload:{}})),screens:[{id:"main",title:"Main",widgetIds:["a","b"]},{id:"other-screen",title:"Other",widgetIds:["other"]}],layouts:{desktop:[{widgetId:"a",x:0,y:6,w:3,h:3},{widgetId:"b",x:3,y:9,w:3,h:3},{widgetId:"other",x:0,y:20,w:3,h:3}],tablet:[],mobile:[]}});
 const result=replaceScreenContent(original,"main",compactGridLayout(screenDefinition(original,"main"),"desktop"));
 expect(result.layouts.desktop.filter((p)=>p.widgetId!=="other").map((p)=>p.y)).toEqual([0,0]);expect(original.layouts.desktop.find((p)=>p.widgetId==="a")?.y).toBe(6);
 expect(result.layouts.desktop.find((p)=>p.widgetId==="other")).toEqual(original.layouts.desktop.find((p)=>p.widgetId==="other"));expect([...result.layouts.mobile].sort((a,b)=>a.widgetId.localeCompare(b.widgetId))).toEqual([...original.layouts.mobile].sort((a,b)=>a.widgetId.localeCompare(b.widgetId)));expect([...result.layouts.tablet].sort((a,b)=>a.widgetId.localeCompare(b.widgetId))).toEqual([...original.layouts.tablet].sort((a,b)=>a.widgetId.localeCompare(b.widgetId)));
});
