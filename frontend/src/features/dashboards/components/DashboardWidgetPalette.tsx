import {useState} from "react";
import {ChartLine, Hash, Table2, FileText, Plus, X, GripVertical, Search} from "lucide-react";
import type {WidgetManifest} from "../../../dashboards/dashboardQueriesApi";
import {visualizationLabels,type Visualization} from "../model/dashboardVisualization";
import {widgetManifestVisualizations} from "../model/widgetManifestCapabilities";

export const widgetDragMime="application/x-voltcanary-widget";
const descriptions:Record<string,{name:string;detail:string;kind:"chart"|"number"|"table"|"note"}>={
  "vehicle-value":{name:"Vehicle field",detail:"Current speed, door state, next stop or another recorded field",kind:"number"},
  "vehicle-timeline":{name:"Vehicle state history",detail:"Timestamped door and journey observations",kind:"table"},
  "vehicle-map":{name:"Vehicle map",detail:"GPS position and movement trail of one automobile",kind:"chart"},
  "time-series":{name:"Time series",detail:"Power, temperature and sensor history",kind:"chart"},
  "latest-value":{name:"Current value",detail:"The latest measurement and its unit",kind:"number"},
  "metric-summary":{name:"Statistic",detail:"Average, minimum, maximum or percentile",kind:"number"},
  "fleet-count":{name:"Equipment overview",detail:"Equipment count and measurement freshness",kind:"number"},
  "energy-period":{name:"Energy consumption",detail:"Covered consumption for a selected period",kind:"number"},
  "peak-and-ranking":{name:"Energy ranking",detail:"Compare equipment consumption and peaks",kind:"table"},
  "incident-list":{name:"Incidents",detail:"Active incidents or events in a period",kind:"table"},
  "maintenance-agenda":{name:"Maintenance",detail:"Scheduled work for your equipment",kind:"table"},
  "risk-list":{name:"Risk evidence",detail:"Recorded equipment risk assessments",kind:"table"},
  "quality-and-freshness":{name:"Data quality",detail:"Measurement age and quality indicators",kind:"number"},
  "location-matrix":{name:"Locations",detail:"Equipment evidence grouped by location",kind:"table"},
  "prediction-evidence":{name:"Predictions",detail:"Recorded predictions and model evidence",kind:"table"},
  "context-note":{name:"Note",detail:"Your own text or working instructions",kind:"note"},
  "estimated-cost":{name:"Estimated cost",detail:"Energy costs with versioned tariffs",kind:"number"},
  "uptime-or-cycle":{name:"Cycles and uptime",detail:"Confirmed operating cycles and downtime",kind:"number"}
};
export function DashboardWidgetPalette({manifests,disabled,limitReached,onClose,onAdd,onDragType}:{manifests:WidgetManifest[];disabled:boolean;limitReached:boolean;onClose:()=>void;onAdd:(type:string,visualization?:Visualization)=>void;onDragType:(type:string|null,visualization?:Visualization)=>void}){
  const [search,setSearch]=useState("");
  const visible=manifests.filter((m)=>`${descriptions[m.type]?.name} ${descriptions[m.type]?.detail} ${m.title} ${m.type} ${widgetManifestVisualizations(m).map(v=>visualizationLabels[v]).join(" ")}`.toLowerCase().includes(search.toLowerCase()));
  return <aside className="dashboard-widget-palette" aria-label="Widget palette">
    <header><h2>Widgets</h2><button type="button" className="ghost-button" aria-label="Close widget palette" onClick={onClose}><X size={18}/></button></header>
    <p>Choose a diagram below to add it directly, or drag it onto the grid. Bind recorded measurements in its settings.</p>
    <label className="dashboard-palette-search"><Search size={16}/><input aria-label="Search widgets" type="search" placeholder="Search widgets…" value={search} onChange={(e)=>setSearch(e.target.value)}/></label>
    {limitReached?<p role="status">24 widgets maximum. Remove a panel to add another.</p>:null}
    <div className="dashboard-palette-cards">{visible.map((m)=>{const description=descriptions[m.type] ?? {name:m.title,detail:m.dependency ?? "Recorded equipment evidence",kind:"table"};const locked=disabled || limitReached || !m.supported;const Icon=description.kind==="chart"?ChartLine:description.kind==="number"?Hash:description.kind==="note"?FileText:Table2;
      return <div key={`${m.type}:${m.typeVersion}`} className={`dashboard-palette-card ${locked?"dashboard-palette-card-disabled":""}`} draggable={!locked} aria-label={`${description.name} widget`} onDragStart={(e)=>{if(locked){e.preventDefault();return;}e.dataTransfer.setData(widgetDragMime,m.type);e.dataTransfer.setData("text/plain",m.type);e.dataTransfer.effectAllowed="copy";onDragType(m.type);}} onDragEnd={()=>onDragType(null)}>
        <div className={`dashboard-palette-preview preview-${description.kind}`} aria-hidden="true"><Icon size={30}/>{description.kind==="chart"?<svg viewBox="0 0 120 35"><polyline points="0,28 18,22 30,26 46,10 58,17 76,6 90,14 108,4 120,9" fill="none" stroke="currentColor" strokeWidth="2"/></svg>:null}</div>
        <div className="dashboard-palette-card-title"><strong>{description.name}</strong>{!locked?<GripVertical size={14} aria-hidden="true"/>:null}</div><small>{description.detail}</small>
        <div className="dashboard-palette-diagrams" aria-label="Compatible visualizations">{widgetManifestVisualizations(m).map((kind)=><button type="button" key={kind} title={`Add ${visualizationLabels[kind]}`} aria-label={`Add ${visualizationLabels[kind]} — ${description.name}`} disabled={locked} draggable={!locked} onClick={()=>onAdd(m.type,kind)} onDragStart={e=>{e.stopPropagation();if(locked){e.preventDefault();return;}e.dataTransfer.setData(widgetDragMime,m.type);e.dataTransfer.setData("text/plain",m.type);e.dataTransfer.effectAllowed="copy";onDragType(m.type,kind);}} onDragEnd={()=>onDragType(null)}><svg viewBox="0 0 32 24" aria-label={visualizationLabels[kind]} role="img">{kind==="pie" || kind==="donut"?<><circle cx="16" cy="12" r="10" fill="currentColor"/><path d="M16 12 L16 2 A10 10 0 0 1 26 12 Z" fill="#f59e0b"/>{kind==="donut"?<circle cx="16" cy="12" r="5" fill="var(--panel-bg,#fff)"/>:null}</>:kind.includes("bar")?<><rect x="3" y="12" width="6" height="10" fill="currentColor"/><rect x="13" y="5" width="6" height="17" fill="currentColor"/><rect x="23" y="9" width="6" height="13" fill="currentColor"/></>:kind==="number"?<text x="3" y="19" fill="currentColor">123</text>:<path d={`M2 20 L10 12 L18 16 L30 3 ${kind==="area"?"L30 22 L2 22 Z":""}`} fill={kind==="area"?"currentColor":"none"} stroke="currentColor"/>}</svg><small>{visualizationLabels[kind]}</small></button>)}</div>
        {m.supported?<button type="button" className="ghost-button" disabled={locked} aria-label={`Add ${description.name}`} onClick={()=>onAdd(m.type)}><Plus size={14}/> Add</button>:<small className="dashboard-palette-unavailable">Unavailable: {m.dependency ?? "Capability not installed"}</small>}
      </div>;
    })}</div>{!visible.length?<p>No matching widgets.</p>:null}
  </aside>;
}
