import {useEffect,useState} from "react";
import type {Breakpoint,DashboardGrid} from "../../../dashboards/dashboardsApi";
import {gridDefaults} from "../model/dashboardGrid";
export function DashboardGridSettings({breakpoint,guides,disabled,onBreakpoint,onGuides,onCompact,settings=gridDefaults[breakpoint],onSettings}:{breakpoint:Breakpoint;guides:boolean;disabled:boolean;onBreakpoint:(value:Breakpoint)=>void;onGuides:(value:boolean)=>void;onCompact:()=>void;settings?:DashboardGrid;onSettings?:(value:DashboardGrid)=>void}){
  const [values,setValues]=useState({columns:String(settings.columns),rowHeight:String(settings.rowHeight),gap:String(settings.gap)});
  useEffect(()=>setValues({columns:String(settings.columns),rowHeight:String(settings.rowHeight),gap:String(settings.gap)}),[settings.columns,settings.rowHeight,settings.gap]);
  const bounds={columns:[1,48],rowHeight:[16,160],gap:[0,32]} as const;
  const valid=(Object.keys(bounds) as (keyof typeof bounds)[]).every((key)=>/^\d+$/.test(values[key]) && Number(values[key])>=bounds[key][0] && Number(values[key])<=bounds[key][1]);
  return <form onSubmit={(e)=>{e.preventDefault();if(valid)onSettings?.({columns:Number(values.columns),rowHeight:Number(values.rowHeight),gap:Number(values.gap)});}}><fieldset className="dashboard-grid-settings" disabled={disabled}><legend>Working grid</legend>
    <label>Layout<select value={breakpoint} onChange={(e)=>onBreakpoint(e.target.value as Breakpoint)}><option value="desktop">Computer</option><option value="tablet">Tablet</option><option value="mobile">Phone</option></select></label>
    <div className="dashboard-grid-numeric">{([['columns','Columns'],['rowHeight','Row height (px)'],['gap','Spacing (px)']] as const).map(([key,label])=><label key={key}>{label}<input aria-label={label} type="number" required min={bounds[key][0]} max={bounds[key][1]} step={1} value={values[key]} onChange={(e)=>setValues({...values,[key]:e.target.value})}/><small>{bounds[key][0]}–{bounds[key][1]}</small></label>)}</div>
    <button className="primary-button" type="submit" disabled={!valid || !onSettings}>Apply grid size</button>
    <label className="dashboard-presentation-toggle"><input type="checkbox" checked={guides} onChange={(e)=>onGuides(e.target.checked)}/>Show grid guides</label>
    <div className="dashboard-grid-diagram" style={{gridTemplateColumns:`repeat(${settings.columns},1fr)`}} aria-label={`${settings.columns} columns`}>{Array.from({length:settings.columns},(_,i)=><span key={i} title={`Column ${i+1}`}>{settings.columns<=24?i+1:null}</span>)}</div>
    <p>Drag a panel header to choose its position. Pull an edge or corner to resize it, or enter its exact width and height in panel settings → Layout.</p>
    <p>Grid size is saved for this screen and layout. Reducing columns moves panels safely into the available width; other screens and layouts stay unchanged.</p>
    <button type="button" className="ghost-button" onClick={onCompact}>Compact this layout</button><small>Removes empty rows in this layout only. You can Undo grid changes. Guide visibility affects editing only.</small>
  </fieldset></form>;
}
