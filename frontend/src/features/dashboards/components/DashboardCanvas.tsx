import {useEffect, useLayoutEffect, useRef, useState} from "react";
import GridLayout, {noCompactor, type Layout, type Compactor} from "react-grid-layout";
import {GripVertical} from "lucide-react";
import "react-grid-layout/css/styles.css";
import "react-resizable/css/styles.css";
import type {Breakpoint, DashboardDefinition, Placement} from "../../../dashboards/dashboardsApi";
import type {WidgetQueryPanel} from "../../../dashboards/dashboardQueriesApi";
import {DashboardWidgetPanel} from "./DashboardWidgetPanel";
import {restoreGestureLayout} from "../model/dashboardGestureLayout";
import {widgetDragMime} from "./DashboardWidgetPalette";
import {workingGrid} from "../model/dashboardGrid";
import { resolveLayout, widgetSize} from "../model/dashboardLayout";

type Props = {
  definition:DashboardDefinition; panels:WidgetQueryPanel[]; stale:boolean; onRetry:(id:string)=>void;
  tools?:(id:string,breakpoint:Breakpoint)=>React.ReactNode; previewBreakpoint?:Breakpoint;
  showGuides?:boolean;
  paletteType?:string|null; paletteSize?:{w:number;h:number}; onWidgetDrop?:(type:string,positions:Placement[],temporaryId:string,sectionId?:string)=>void;
  onVisible?:(ids:string[])=>void; onLayoutCommit?:(positions:Placement[],breakpoint:Breakpoint)=>void; movementDisabled?:boolean;
};
export function DashboardCanvas({definition,panels,stale,onRetry,tools,previewBreakpoint,onVisible,onLayoutCommit,movementDisabled=false,paletteType,paletteSize,onWidgetDrop,showGuides=true}:Props) {
  const ref=useRef<HTMLDivElement>(null),[width,setWidth]=useState(0),[message,setMessage]=useState("");
  const [viewportWidth,setViewportWidth]=useState(window.innerWidth);
  useEffect(()=>{const resize=()=>setViewportWidth(window.innerWidth);resize();window.addEventListener("resize",resize);return()=>window.removeEventListener("resize",resize);},[]);
  useLayoutEffect(()=>{if(!ref.current)return;const measure=()=>setWidth(ref.current!.getBoundingClientRect().width);measure();if(typeof ResizeObserver==="undefined")return;const observer=new ResizeObserver(([entry])=>setWidth(entry.contentRect.width));observer.observe(ref.current);return()=>observer.disconnect();},[]);
  const gestureLayout=useRef<{id:string;initial:Layout;breakpoint:Breakpoint}|null>(null);
  const gesture=useRef(false),[gridRevision,setGridRevision]=useState(0);
  useEffect(()=>{const cancel=(event:KeyboardEvent)=>{if(event.key==="Escape" && gesture.current){gesture.current=false;gestureLayout.current=null;setGridRevision((value)=>value+1);setMessage("Layout change cancelled.");}};document.addEventListener("keydown",cancel);return()=>document.removeEventListener("keydown",cancel);},[]);
  const widgetIds=definition.widgets.map((widget)=>widget.id).join(",");
  useEffect(()=>{if(!onVisible || !ref.current)return;if(typeof IntersectionObserver==="undefined"){onVisible(definition.widgets.map((widget)=>widget.id));return;}const visible=new Set<string>();const observer=new IntersectionObserver((entries)=>{for(const entry of entries){const id=(entry.target as HTMLElement).dataset.dashboardWidget!;if(entry.isIntersecting)visible.add(id);else visible.delete(id);}onVisible([...visible].sort());});ref.current.querySelectorAll("[data-dashboard-widget]").forEach((element)=>observer.observe(element));return()=>observer.disconnect();},[widgetIds,onVisible]);
  const breakpoint=previewBreakpoint ?? (viewportWidth>=1200?"desktop":viewportWidth>=768?"tablet":"mobile");
  const settings=workingGrid(definition,breakpoint),totalColumns=settings.columns;
  const editable=!!onLayoutCommit;
  useEffect(()=>{gesture.current=false;gestureLayout.current=null;},[breakpoint,movementDisabled]);
  function grid(ids:string[],sectionId?:string) {
    const widgets=definition.widgets.filter((widget)=>ids.includes(widget.id));
    const layout=resolveLayout(widgets,definition.layouts[breakpoint] ?? [],breakpoint,totalColumns);
    const cards=[...widgets].sort((a,b)=>{const first=layout.find((item)=>item.widgetId===a.id)!;const second=layout.find((item)=>item.widgetId===b.id)!;return first.y-second.y || first.x-second.x;}).map((widget)=>{
      const position=layout.find((item)=>item.widgetId===widget.id)!;
      return <article key={widget.id} tabIndex={-1} data-dashboard-widget={widget.id} className={`dashboard-widget panel ${editable?"dashboard-widget-editable":""}`} aria-label={widget.title} style={editable?undefined:{gridColumn:`${position.x+1} / span ${position.w}`,gridRow:`${position.y+1} / span ${position.h}`}}>
        <div className={editable?"dashboard-edit-card-heading":"dashboard-card-heading"}>{editable?<div className="dashboard-grid-drag-header" title="Drag to move this panel" aria-label={`Drag ${widget.title}`}><GripVertical size={16} aria-hidden="true"/><h3>{widget.title || widget.type}</h3></div>:<h3 className={widget.presentation?.showTitle===true?undefined:"sr-only"}>{widget.title || widget.type}</h3>}{tools?.(widget.id,breakpoint)}</div>
        <DashboardWidgetPanel widget={widget} panel={panels.find((panel)=>panel.widgetId===widget.id)} stale={stale} onRetry={()=>onRetry(widget.id)}/>
      </article>;
    });
    if(!editable)return <div className="dashboard-grid" style={{gridTemplateColumns:`repeat(${totalColumns}, minmax(0, 1fr))`,gridAutoRows:`${settings.rowHeight}px`,gap:settings.gap}}>{cards}</div>;
    const libraryLayout=layout.map(({widgetId,...position})=>{const minimum=widgetSize(widgets.find((w)=>w.id===widgetId)!.type,breakpoint,widgets.find((w)=>w.id===widgetId)!.typeVersion,totalColumns);return {...position,i:widgetId,minW:minimum.w,minH:minimum.h,maxW:totalColumns,maxH:100};});
    const compactor:Compactor={...noCompactor,compact(next){const baseline=gestureLayout.current;return baseline && baseline.breakpoint===breakpoint && next.some((p)=>p.i===baseline.id)?restoreGestureLayout(next,baseline.initial,baseline.id):next;}};
    function begin(id:string|undefined){gesture.current=true;gestureLayout.current=id?{id,breakpoint,initial:libraryLayout.map((p)=>({...p}))}:null;}
    function finish(next:Layout){const baseline=gestureLayout.current;const final=baseline?restoreGestureLayout(next,baseline.initial,baseline.id):next;gestureLayout.current=null;gesture.current=false;commit(final);}
    function commit(next:Layout){
      if(movementDisabled)return;
      const positions=next.map(({i,x,y,w,h})=>({widgetId:i,x,y,w,h}));
      if(positions.every((p)=>{const prior=layout.find((item)=>item.widgetId===p.widgetId);return prior && ["x","y","w","h"].every((key)=>prior[key as keyof Placement]===p[key as keyof Placement]);}))return;
      onLayoutCommit?.(positions,breakpoint);setMessage(`Layout updated on ${breakpoint}. Use Undo to restore it.`);
    }
    return <GridLayout key={`${breakpoint}:${gridRevision}:${paletteType ?? ""}`} width={Math.max(1,width)} className={`dashboard-edit-grid ${showGuides?"":"dashboard-edit-grid-no-guides"}`} layout={libraryLayout} compactor={compactor}
      gridConfig={{cols:totalColumns,rowHeight:settings.rowHeight,margin:[settings.gap,settings.gap],containerPadding:[0,0]}}
      dragConfig={{enabled:!movementDisabled,handle:".dashboard-grid-drag-header",cancel:"button, input, select, textarea, a, summary",bounded:false}}
      dropConfig={{enabled:!movementDisabled && !!paletteType && !!onWidgetDrop,defaultItem:paletteSize ?? {w:3,h:3}}}
      droppingItem={{i:"palette-drop-preview",x:0,y:0,w:Math.min(totalColumns,paletteSize?.w ?? 3),h:paletteSize?.h ?? 3}}
      onDropDragOver={()=>paletteType && !movementDisabled?{w:Math.min(totalColumns,paletteSize?.w ?? 3),h:paletteSize?.h ?? 3}:false}
      onDrop={(next,item,event)=>{const submitted=(event as DragEvent).dataTransfer?.getData(widgetDragMime);if(!item || movementDisabled || !paletteType || submitted!==paletteType)return;onWidgetDrop?.(paletteType,next.map(({i,x,y,w,h})=>({widgetId:i,x,y,w,h})),item.i,sectionId);}}
      resizeConfig={{enabled:!movementDisabled,handles:["e","s","se"]}}
      style={{minHeight:(Math.max(0,...layout.map((p)=>p.y+p.h))+3 )*(settings.rowHeight+settings.gap),backgroundSize:`${(width+settings.gap)/totalColumns}px ${settings.rowHeight+settings.gap}px`}}
      onDragStart={(_layout,_old,item)=>begin(item?.i)} onResizeStart={(_layout,_old,item)=>begin(item?.i)}
      onDragStop={(next,_old,_new,_placeholder,event)=>{const point="changedTouches" in event?(event as TouchEvent).changedTouches[0]:event as MouseEvent;const bounds=ref.current?.getBoundingClientRect();gesture.current=false;if(point && bounds && (point.clientX<bounds.left || point.clientX>bounds.right || point.clientY<bounds.top || point.clientY>bounds.bottom)){gestureLayout.current=null;setGridRevision((value)=>value+1);setMessage("Move cancelled outside the canvas.");return;}finish(next);}}
      onResizeStop={finish}>{cards}</GridLayout>;
  }
  const sectioned=new Set(definition.sections.flatMap((section)=>section.widgetIds));
  return <div ref={ref} className="dashboard-canvas">
    {editable?<div className="dashboard-grid-guidance"><span>Drag a panel header to move · Pull the corner or edge to resize</span><span>{breakpoint} · {totalColumns} columns</span></div>:null}
    {editable?<p role="status" className="sr-only">{message}</p>:null}
    {definition.sections.map((section)=><section key={section.id}><h2>{section.title}</h2>{grid(section.widgetIds,section.id)}</section>)}
    {grid(definition.widgets.filter((widget)=>!sectioned.has(widget.id)).map((widget)=>widget.id))}
    {!definition.widgets.length?<p>{tools?"Add a widget and choose its data to preview recorded evidence.":"No published widgets on this screen."}</p>:null}
  </div>;
}
