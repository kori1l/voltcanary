import {useState,useRef} from "react";
import type {Breakpoint,DashboardDefinition,DashboardScreen} from "../../../dashboards/dashboardsApi";
import {workingGrid} from "../model/dashboardGrid";
import {relativePlacementReason,resolveLayout,widgetSize,widgetDefaultSize,type RelativePosition} from "../model/dashboardLayout";

type Props={definition:DashboardDefinition;id:string;breakpoint:Breakpoint;screens:DashboardScreen[];screenId:string;disabled:boolean;limitReached:boolean;onMove:(direction:"up"|"down"|"left"|"right")=>void;onResize:(w:number,h:number)=>void;onPlace:(anchor:string,direction:RelativePosition)=>void;onScreen:(id:string)=>void;onSection:(id:string)=>void;onDuplicate:()=>void;onDelete:()=>void};
export function DashboardPanelLayoutSettings({definition,id,breakpoint,screens,screenId,disabled,limitReached,onMove,onResize,onPlace,onScreen,onSection,onDuplicate,onDelete}:Props){
  const widthInput=useRef<HTMLInputElement>(null),heightInput=useRef<HTMLInputElement>(null);
  const [anchor,setAnchor]=useState(""),[direction,setDirection]=useState<RelativePosition>("below");
  const widget=definition.widgets.find((w)=>w.id===id);
  if(!widget)return <p>Updating panel location…</p>;
  const totalColumns=workingGrid(definition,breakpoint,id).columns;
  const peers=definition.sections.find((s)=>s.widgetIds.includes(id))?.widgetIds ?? definition.widgets.filter((w)=>!definition.sections.some((s)=>s.widgetIds.includes(w.id))).map((w)=>w.id);
  const positions=resolveLayout(definition.widgets.filter((w)=>peers.includes(w.id)),definition.layouts[breakpoint] ?? [],breakpoint,totalColumns).sort((a,b)=>a.y-b.y || a.x-b.x);
  const index=positions.findIndex((p)=>p.widgetId===id),position=positions[index];
  const reason=anchor?relativePlacementReason(definition,id,anchor,direction,breakpoint):null;
  return <fieldset className="dashboard-panel-layout-settings" disabled={disabled}><legend>Layout and panel actions</legend>
    <small>{breakpoint} layout · Changes save to the draft. Use Undo to restore.</small>
    <label>Size<select defaultValue="" onChange={(e)=>{const size=widgetDefaultSize(widget.type,breakpoint,widget.typeVersion,totalColumns);onResize(e.target.value==="wide"?totalColumns:e.target.value==="half"?Math.ceil(totalColumns/2):size.w,e.target.value==="tall"?size.h+3:size.h);e.target.value="";}}><option value="">Choose size…</option><option value="normal">Default</option><option value="half">Half width</option><option value="wide">Full width</option><option value="tall">Tall</option></select></label>
    <div className="dashboard-grid-numeric"><label>Width (columns)<input key={`w:${breakpoint}:${position?.w}`} ref={widthInput} type="number" min={widgetSize(widget.type,breakpoint,widget.typeVersion,totalColumns).w} max={totalColumns} step={1} defaultValue={position?.w}/></label><label>Height (rows)<input key={`h:${breakpoint}:${position?.h}`} ref={heightInput} type="number" min={widgetSize(widget.type,breakpoint,widget.typeVersion,totalColumns).h} max={100} step={1} defaultValue={position?.h}/></label></div>
    <button type="button" className="ghost-button" onClick={()=>{if(!widthInput.current?.reportValidity() || !heightInput.current?.reportValidity())return;const w=Number(widthInput.current.value),h=Number(heightInput.current.value);if(Number.isInteger(w) && Number.isInteger(h) && w>0 && h>0)onResize(w,h);}}>Apply panel size</button>
    <div className="dashboard-settings-movement" role="group" aria-label="Keyboard movement">{(["up","down","left","right"] as const).map((side)=><button type="button" className="ghost-button" key={side} disabled={side==="up"?index<=0:side==="down"?index<0 || index===positions.length-1:side==="left"?!position || position.x===0:!position || position.x+position.w>=totalColumns} onClick={()=>onMove(side)}>Move {side}</button>)}</div>
    <label>Place beside<select value={anchor} onChange={(e)=>setAnchor(e.target.value)}><option value="">Choose another panel</option>{definition.widgets.filter((w)=>w.id!==id).map((w)=><option key={w.id} value={w.id}>{w.title}</option>)}</select></label>
    {anchor?<><label>Position<select value={direction} onChange={(e)=>setDirection(e.target.value as RelativePosition)}>{(["above","below","left","right"] as const).map((side)=><option value={side} key={side}>{side}</option>)}</select></label>{reason?<small>{reason}</small>:null}<button type="button" className="ghost-button" disabled={!!reason} onClick={()=>onPlace(anchor,direction)}>Move to selected position</button></>:null}
    <label>Screen<select value={screenId} onChange={(e)=>{setAnchor("");onScreen(e.target.value);}}>{screens.map((s)=><option key={s.id} value={s.id}>{s.title}</option>)}</select></label>
    <label>Section<select value={definition.sections.find((s)=>s.widgetIds.includes(id))?.id ?? ""} onChange={(e)=>{setAnchor("");onSection(e.target.value);}}><option value="">No section</option>{definition.sections.map((s)=><option key={s.id} value={s.id}>{s.title}</option>)}</select></label>
    <div className="action-row"><button type="button" className="ghost-button" disabled={limitReached} onClick={onDuplicate}>Duplicate panel</button><button type="button" className="ghost-button dashboard-delete-panel" onClick={onDelete}>Delete panel</button></div>
  </fieldset>;
}
