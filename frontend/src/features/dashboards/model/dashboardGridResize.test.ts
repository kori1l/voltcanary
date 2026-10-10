import {expect,it} from "vitest";
import {blankDashboard,normalizeLayouts,resizeWidget} from "./dashboardLayout";
import {workingGrid} from "./dashboardGrid";
import {resizeScreenGrid} from "./dashboardGridResize";
import {screenDefinition,replaceScreenContent} from "./dashboardScreens";
const make=(id:string)=>({id,title:id,type:"fleet-count",typeVersion:1,binding:{mode:"ALL" as const},query:{},presentation:{},navigation:{},payload:{}});
it("persists custom dimensions and preserves other screens and profiles while reducing columns",()=>{
 const original=normalizeLayouts({...blankDashboard("Grid"),widgets:[make("a"),make("b"),make("other")],screens:[{id:"main",title:"Main",widgetIds:["a","b"]},{id:"other-screen",title:"Other",widgetIds:["other"]}],layouts:{desktop:[{widgetId:"a",x:0,y:0,w:6,h:3},{widgetId:"b",x:6,y:0,w:6,h:3},{widgetId:"other",x:9,y:0,w:3,h:3}],tablet:[],mobile:[]}});
 const result=replaceScreenContent(original,"main",resizeScreenGrid(screenDefinition(original,"main"),"desktop",{columns:4,rowHeight:36,gap:8}));
 expect(workingGrid(screenDefinition(result,"main"),"desktop")).toEqual({columns:4,rowHeight:36,gap:8});expect(workingGrid(screenDefinition(result,"other-screen"),"desktop").columns).toBe(12);
 const a=result.layouts.desktop.find((p)=>p.widgetId==="a")!,b=result.layouts.desktop.find((p)=>p.widgetId==="b")!;expect(a.w).toBe(4);expect(b.w).toBe(4);expect(b.y).toBeGreaterThanOrEqual(a.y+a.h);
 expect(result.layouts.desktop.find((p)=>p.widgetId==="other")).toEqual(original.layouts.desktop.find((p)=>p.widgetId==="other"));for(const bp of ["mobile","tablet"] as const)expect([...result.layouts[bp]].sort((a,b)=>a.widgetId.localeCompare(b.widgetId))).toEqual([...original.layouts[bp]].sort((a,b)=>a.widgetId.localeCompare(b.widgetId)));
});
it("promotes legacy definitions and allows panel widths greater than the default grid",()=>{
 const legacy=normalizeLayouts({...blankDashboard("Legacy"),widgets:[make("a")]});const result=resizeScreenGrid(legacy,"desktop",{columns:24,rowHeight:32,gap:6});expect(result.screens?.[0].widgetIds).toEqual(["a"]);
 expect(resizeWidget(result,"a",20,3,"desktop").layouts.desktop[0].w).toBe(20);expect(legacy.screens).toBeUndefined();
});
