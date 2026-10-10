import {expect,it} from "vitest";
import {blankDashboard,normalizeLayouts} from "./dashboardLayout";
import {insertWidget} from "./dashboardWidgetInsertion";
const make=(id:string)=>({id,title:id,type:"fleet-count",typeVersion:1,binding:{mode:"ALL" as const},query:{},presentation:{},navigation:{},payload:{}});
it("maps the grid placeholder and collision-adjusted neighbours while preserving other layouts and sections",()=>{
 const original=normalizeLayouts({...blankDashboard("Palette"),widgets:[make("existing"),make("other")],sections:[{id:"other-section",title:"Other",widgetIds:["other"]}]});
 const result=insertWidget(original,make("added"),"desktop",{temporaryId:"temporary",positions:[{widgetId:"temporary",x:3,y:2,w:3,h:3},{widgetId:"existing",x:0,y:5,w:3,h:3}]});
 expect(result.layouts.desktop.find((p)=>p.widgetId==="added")).toMatchObject({x:3,y:2,w:3,h:3});expect(result.layouts.desktop.find((p)=>p.widgetId==="existing")?.y).toBe(5);
 expect(result.layouts.desktop.find((p)=>p.widgetId==="other")).toEqual(original.layouts.desktop.find((p)=>p.widgetId==="other"));
 expect(result.layouts.mobile.find((p)=>p.widgetId==="existing")).toEqual(original.layouts.mobile.find((p)=>p.widgetId==="existing"));expect(result.layouts.tablet.find((p)=>p.widgetId==="existing")).toEqual(original.layouts.tablet.find((p)=>p.widgetId==="existing"));
 expect(result.widgets.map((w)=>w.id)).toEqual(["existing","other","added"]);expect(result.layouts.desktop.some((p)=>p.widgetId==="temporary")).toBe(false);
});
