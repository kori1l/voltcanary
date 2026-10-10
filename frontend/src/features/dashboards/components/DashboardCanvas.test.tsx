import {cleanup,fireEvent,render,screen} from "@testing-library/react";
import {afterEach,beforeEach,expect,it,vi} from "vitest";
import {DashboardCanvas} from "./DashboardCanvas";
import {blankDashboard,normalizeLayouts} from "../model/dashboardLayout";
vi.mock("./DashboardWidgetPanel",()=>({DashboardWidgetPanel:()=> <p>Evidence</p>}));
const definition=normalizeLayouts({...blankDashboard("Grid"),widgets:["first","second"].map((id)=>({id,title:id,type:"fleet-count",typeVersion:1,binding:{mode:"ALL" as const},query:{},presentation:{},navigation:{},payload:{}}))});
beforeEach(()=>{vi.stubGlobal("DragEvent",MouseEvent);vi.spyOn(HTMLElement.prototype,"offsetParent","get").mockImplementation(function(this:HTMLElement){return this.parentElement;});vi.spyOn(HTMLElement.prototype,"getBoundingClientRect").mockReturnValue({left:0,top:0,width:1200,height:600,right:1200,bottom:600,x:0,y:0,toJSON(){}});});
afterEach(()=>{cleanup();vi.restoreAllMocks();vi.unstubAllGlobals();});
it("moves a card into free grid space using real mouse events and commits only on release",()=>{
 const commit=vi.fn();render(<DashboardCanvas definition={definition} panels={[]} stale={false} onRetry={()=>{}} onLayoutCommit={commit} previewBreakpoint="desktop"/>);
 fireEvent.mouseDown(screen.getByLabelText("Drag first"),{button:0,clientX:10,clientY:10});
 fireEvent.mouseMove(document,{buttons:1,clientX:20,clientY:20});fireEvent.mouseMove(document,{buttons:1,clientX:620,clientY:190});expect(commit).not.toHaveBeenCalled();
 fireEvent.mouseUp(document,{clientX:620,clientY:190});
 expect(commit).toHaveBeenCalledTimes(1);expect(commit.mock.calls[0][1]).toBe("desktop");
 expect(commit.mock.calls[0][0].find((p:{widgetId:string})=>p.widgetId==="first")).toMatchObject({x:6,y:3,w:3,h:3});
 expect(definition.layouts.mobile[0]).toMatchObject({x:0,y:0,w:1});
});
it("resizes through the real corner handle, respects column bounds and commits on release",()=>{
 const commit=vi.fn();render(<DashboardCanvas definition={definition} panels={[]} stale={false} onRetry={()=>{}} onLayoutCommit={commit} previewBreakpoint="desktop"/>);
 const handle=screen.getByRole("article",{name:"first"}).querySelector(".react-resizable-handle-se")!;
 fireEvent.mouseDown(handle,{button:0,clientX:290,clientY:170});fireEvent.mouseMove(document,{buttons:1,clientX:595,clientY:290});expect(commit).not.toHaveBeenCalled();fireEvent.mouseUp(document,{clientX:595,clientY:290});
 expect(commit).toHaveBeenCalledTimes(1);expect(commit.mock.calls[0][0].find((p:{widgetId:string})=>p.widgetId==="first")).toMatchObject({w:6,h:5});
});
it("disables editing during save/conflict and keeps viewer chrome absent",()=>{
 const commit=vi.fn();render(<DashboardCanvas definition={definition} panels={[]} stale={false} onRetry={()=>{}} onLayoutCommit={commit} movementDisabled previewBreakpoint="desktop"/>);
 fireEvent.mouseDown(screen.getByLabelText("Drag first"),{button:0,clientX:10,clientY:10});fireEvent.mouseMove(document,{clientX:620,clientY:190});fireEvent.mouseUp(document);expect(commit).not.toHaveBeenCalled();cleanup();
 render(<DashboardCanvas definition={definition} panels={[]} stale={false} onRetry={()=>{}}/>);
 expect(screen.queryByLabelText("Drag first")).not.toBeInTheDocument();expect(document.querySelector(".react-resizable-handle")).toBeNull();expect(document.querySelector(".dashboard-edit-grid")).toBeNull();
});

it("cancels a gesture on Escape or release outside the canvas without saving",()=>{
 const commit=vi.fn();render(<DashboardCanvas definition={definition} panels={[]} stale={false} onRetry={()=>{}} onLayoutCommit={commit} previewBreakpoint="desktop"/>);
 fireEvent.mouseDown(screen.getByLabelText("Drag first"),{button:0,clientX:10,clientY:10});fireEvent.mouseMove(document,{buttons:1,clientX:620,clientY:190});fireEvent.keyDown(document,{key:"Escape"});fireEvent.mouseUp(document,{clientX:620,clientY:190});expect(commit).not.toHaveBeenCalled();
 fireEvent.mouseDown(screen.getByLabelText("Drag first"),{button:0,clientX:10,clientY:10});fireEvent.mouseMove(document,{buttons:1,clientX:620,clientY:190});fireEvent.mouseUp(document,{clientX:1300,clientY:190});expect(commit).not.toHaveBeenCalled();
});

it("previews and drops an external palette card through the installed grid",()=>{
 const drop=vi.fn();render(<DashboardCanvas definition={definition} panels={[]} stale={false} onRetry={()=>{}} onLayoutCommit={()=>{}} previewBreakpoint="desktop" paletteType="time-series" paletteSize={{w:6,h:5}} onWidgetDrop={drop}/>);
 const grid=document.querySelector(".dashboard-edit-grid")!;
 const dataTransfer={getData:(mime:string)=>mime==="application/x-voltcanary-widget"?"time-series":"",types:["application/x-voltcanary-widget"]};
 fireEvent.dragEnter(grid,{clientX:330,clientY:180,dataTransfer});fireEvent.dragOver(grid,{clientX:330,clientY:180,dataTransfer});expect(drop).not.toHaveBeenCalled();
 fireEvent.drop(grid,{clientX:330,clientY:180,dataTransfer});expect(drop).toHaveBeenCalledTimes(1);
 const [type,positions,id]=drop.mock.calls[0];expect(type).toBe("time-series");expect(positions.find((p:{widgetId:string})=>p.widgetId===id)).toMatchObject({w:6,h:5});
});
it("rejects external drop data that does not match the active palette type",()=>{
 const drop=vi.fn();render(<DashboardCanvas definition={definition} panels={[]} stale={false} onRetry={()=>{}} onLayoutCommit={()=>{}} previewBreakpoint="desktop" paletteType="time-series" paletteSize={{w:6,h:5}} onWidgetDrop={drop}/>);
 const grid=document.querySelector(".dashboard-edit-grid")!;const dataTransfer={getData:()=>"unknown",types:["application/x-voltcanary-widget"]};
 fireEvent.dragEnter(grid,{clientX:330,clientY:180,dataTransfer});fireEvent.dragOver(grid,{clientX:330,clientY:180,dataTransfer});fireEvent.drop(grid,{clientX:330,clientY:180,dataTransfer});expect(drop).not.toHaveBeenCalled();
});

it("restores a crossed neighbour while still dragging and saves only the final collision layout",()=>{
 const commit=vi.fn();render(<DashboardCanvas definition={definition} panels={[]} stale={false} onRetry={()=>{}} onLayoutCommit={commit} previewBreakpoint="desktop"/>);
 const neighbour=screen.getByRole("article",{name:"second"});const original=neighbour.style.transform;
 fireEvent.mouseDown(screen.getByLabelText("Drag first"),{button:0,clientX:10,clientY:10});fireEvent.mouseMove(document,{buttons:1,clientX:320,clientY:10});expect(neighbour.style.transform).not.toBe(original);
 fireEvent.mouseMove(document,{buttons:1,clientX:620,clientY:10});expect(neighbour.style.transform).toBe(original);expect(commit).not.toHaveBeenCalled();
 fireEvent.mouseUp(document,{clientX:620,clientY:10});expect(commit).toHaveBeenCalledTimes(1);expect(commit.mock.calls[0][0].find((p:{widgetId:string})=>p.widgetId==="second")).toMatchObject({x:3,y:0});
});
it("returning to the start restores all neighbours and does not create a layout change",()=>{
 const commit=vi.fn();render(<DashboardCanvas definition={definition} panels={[]} stale={false} onRetry={()=>{}} onLayoutCommit={commit} previewBreakpoint="desktop"/>);
 const neighbour=screen.getByRole("article",{name:"second"});const original=neighbour.style.transform;
 fireEvent.mouseDown(screen.getByLabelText("Drag first"),{button:0,clientX:10,clientY:10});fireEvent.mouseMove(document,{buttons:1,clientX:320,clientY:10});expect(neighbour.style.transform).not.toBe(original);
 fireEvent.mouseMove(document,{buttons:1,clientX:10,clientY:10});expect(neighbour.style.transform).toBe(original);fireEvent.mouseUp(document,{clientX:10,clientY:10});expect(commit).not.toHaveBeenCalled();
});
it("shrinking back after a resize restores displaced neighbours without saving",()=>{
 const commit=vi.fn();render(<DashboardCanvas definition={definition} panels={[]} stale={false} onRetry={()=>{}} onLayoutCommit={commit} previewBreakpoint="desktop"/>);
 const neighbour=screen.getByRole("article",{name:"second"}),original=neighbour.style.transform;const handle=screen.getByRole("article",{name:"first"}).querySelector(".react-resizable-handle-se")!;
 fireEvent.mouseDown(handle,{button:0,clientX:290,clientY:170});fireEvent.mouseMove(document,{buttons:1,clientX:595,clientY:290});expect(neighbour.style.transform).not.toBe(original);
 fireEvent.mouseMove(document,{buttons:1,clientX:290,clientY:170});expect(neighbour.style.transform).toBe(original);fireEvent.mouseUp(document,{clientX:290,clientY:170});expect(commit).not.toHaveBeenCalled();
});

it("renders saved grid dimensions in View using viewport profiles even with a narrow canvas",()=>{
 const prior=window.innerWidth;Object.defineProperty(window,"innerWidth",{configurable:true,value:1280});
 vi.mocked(HTMLElement.prototype.getBoundingClientRect).mockReturnValue({left:0,top:0,width:900,height:600,right:900,bottom:600,x:0,y:0,toJSON(){}});
 const saved={...definition,screens:[{id:"main",title:"Main",widgetIds:["first","second"],grid:{desktop:{columns:24,rowHeight:32,gap:6}}}]};
 render(<DashboardCanvas definition={saved} panels={[]} stale={false} onRetry={()=>{}}/>);
 const grid=document.querySelector<HTMLElement>(".dashboard-grid")!;expect(grid.style.gridTemplateColumns).toContain("repeat(24");expect(grid.style.gridAutoRows).toContain("32px");expect(grid.style.gap).toBe("6px");
 Object.defineProperty(window,"innerWidth",{configurable:true,value:390});fireEvent(window,new Event("resize"));expect(grid.style.gridTemplateColumns).toContain("repeat(1");cleanup();Object.defineProperty(window,"innerWidth",{configurable:true,value:prior});
});
