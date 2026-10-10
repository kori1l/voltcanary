import {useState} from "react";
import {Dialog} from "../../../components/Dialog";
import type {Breakpoint,DashboardDefinition,DashboardWidget} from "../../../dashboards/dashboardsApi";
import type {WidgetManifest} from "../../../dashboards/dashboardQueriesApi";
import {relativePlacementReason,type RelativePosition} from "../model/dashboardLayout";

export interface PlacementIntent {mode:"add"|"move";widgetId?:string;anchorId?:string;direction?:RelativePosition}
export function DashboardPlacementDialog({definition,manifests,breakpoint,intent,onClose,onPlace}:{definition:DashboardDefinition;manifests:WidgetManifest[];breakpoint:Breakpoint;intent:PlacementIntent;onClose:()=>void;onPlace:(type:string,anchorId:string,direction:RelativePosition)=>void}){
  const current=definition.widgets.find((widget)=>widget.id===intent.widgetId);
  const [type,setType]=useState(current?.type ?? "");const [anchor,setAnchor]=useState(intent.anchorId ?? "");const [direction,setDirection]=useState<RelativePosition>(intent.direction ?? "below");
  const candidate:DashboardWidget=current ?? {id:"placement-preview",type,typeVersion:1,title:"New widget",binding:{mode:"UNBOUND"},query:{},presentation:{},navigation:{},payload:{}};
  const preview=current?definition:{...definition,widgets:[...definition.widgets,candidate]};
  const supported=intent.mode==="move" || manifests.some((manifest)=>manifest.type===type && manifest.supported);
  const reason=anchor?relativePlacementReason(preview,candidate.id,anchor,direction,breakpoint):null;
  const anchorTitle=definition.widgets.find((widget)=>widget.id===anchor)?.title;
  return <Dialog title={intent.mode==="add"?"Place a new widget":`Move ${current?.title}`} onClose={onClose}><form onSubmit={(event)=>{event.preventDefault();if(supported && !reason)onPlace(type,anchor,direction);}}>
    {intent.mode==="add"?<label>Widget type<select value={type} onChange={(event)=>setType(event.target.value)} required><option value="">Choose a chart, number, table or note</option>{manifests.map((manifest)=><option key={`${manifest.type}-${manifest.typeVersion}`} value={manifest.type} disabled={!manifest.supported}>{manifest.title}{manifest.supported?"":` · Unavailable: ${manifest.dependency}`}</option>)}</select></label>:<p>{current?.title}</p>}
    <label>Place relative to<select value={anchor} onChange={(event)=>setAnchor(event.target.value)}><option value="">At the start of this screen</option>{definition.widgets.filter((widget)=>widget.id!==candidate.id).map((widget)=><option key={widget.id} value={widget.id}>{widget.title}</option>)}</select></label>
    {anchor?<fieldset><legend>Position</legend><div className="dashboard-position-options">{(["above","below","left","right"] as const).map((side)=>{const why=type?relativePlacementReason(preview,candidate.id,anchor,side,breakpoint):null;return <label key={side} title={why ?? undefined}><input type="radio" name="widget-position" checked={direction===side} disabled={!!why} onChange={()=>setDirection(side)}/>{side[0].toUpperCase()+side.slice(1)}</label>;})}</div></fieldset>:null}
    {anchor && type?[...new Set((["left","right"] as const).map((side)=>relativePlacementReason(preview,candidate.id,anchor,side,breakpoint)).filter((why):why is string=>!!why))].map((why)=><p className="muted-text" key={why}>{why}</p>):null}
    <div className={`dashboard-placement-preview placement-${anchor?direction:"start"}`} aria-label="Placement preview"><span>{candidate.title}</span>{anchor?<span>{anchorTitle}</span>:null}</div>
    <p>{anchor?`${intent.mode==="add"?"Add":"Move"} ${direction} of ${anchorTitle}. Only colliding neighbours move down.`:"Start at the top-left of this screen. Existing neighbours move safely."}</p>{reason?<p role="alert">{reason}</p>:null}
    <button className="primary-button" type="submit" disabled={!supported || !!reason}>{intent.mode==="add"?"Add widget here":"Move widget here"}</button>
  </form></Dialog>;
}
