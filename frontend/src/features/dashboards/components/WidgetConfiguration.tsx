import { useEffect, useState, type ReactNode } from "react";
import { Field } from "../../../components/Field";
import { EntitySelector } from "../../../components/EntitySelector";
import { getTelemetryMetrics, type TelemetryMetricMetadata } from "../../../telemetry/telemetryApi";
import type { DashboardWidget } from "../../../dashboards/dashboardsApi";
import type { WidgetManifest } from "../../../dashboards/dashboardQueriesApi";
import {dashboardDeviceOptions} from "../model/dashboardEntityOptions";
import {EnergyMeterSetup} from "./EnergyMeterSetup";
export function WidgetConfiguration({widget,manifest,onSave,onCancel,layoutControls}:{layoutControls?:ReactNode;widget:DashboardWidget;manifest:WidgetManifest;onSave:(widget:DashboardWidget)=>void;onCancel:()=>void}) {
  const [draft,setDraft]=useState({...widget,binding:widget.binding ?? {mode:"ALL"},query:widget.query ?? {},presentation:widget.presentation ?? {},navigation:widget.navigation ?? {},payload:widget.payload ?? {}});const [metrics,setMetrics]=useState<TelemetryMetricMetadata[]>([]);const [metricError,setMetricError]=useState<string|null>(null);
  const [section,setSection]=useState(!manifest.supported && layoutControls?"Layout":"Data");
  const deviceId=draft.binding.deviceIds?.[0] ?? "";
  const needsMetric=["latest-value","time-series","metric-summary"].includes(widget.type);
  useEffect(()=>{setMetrics([]);setMetricError(null);if(!deviceId || !needsMetric)return;const controller=new AbortController();
    const asOf=Date.now();
    void getTelemetryMetrics(deviceId,new Date(asOf-30*86400000).toISOString(),new Date(asOf).toISOString(),controller.signal).then((items)=>{if(!controller.signal.aborted)setMetrics(items);}).catch((error)=>{if(!controller.signal.aborted)setMetricError(error instanceof Error?error.message:"Metrics unavailable");});
    return()=>controller.abort();},[deviceId,needsMetric]);
  const selected=metrics.find((metric)=>metric.name===draft.query.metric);
  const invalid=needsMetric && (!selected || !deviceId);
  function query(patch:Record<string,unknown>){setDraft((prior)=>({...prior,query:{...prior.query,...patch}}));}
  return <form className="dashboard-config" onSubmit={(event)=>{event.preventDefault();if(invalid)return;onSave({...draft,query:{...draft.query,...(selected?{metric:selected.name,unit:selected.unit}:{}),...(widget.type==="time-series"?{series:[{deviceId,metric:selected!.name}]}:{})}});}}>
    <nav className="dashboard-config-sections" aria-label="Panel settings sections">{[...(manifest.supported?["Data","Calculation","Appearance","Navigation"]:[]),...(layoutControls?["Layout"]:[])].map((name)=><button type="button" key={name} className="ghost-button" aria-pressed={section===name} onClick={()=>setSection(name)}>{name}</button>)}</nav>
    <fieldset hidden={section!=="Data"}><legend>Data</legend>
      <Field label="Widget title" required>{(props)=><input {...props} value={draft.title} maxLength={120} onChange={(event)=>setDraft({...draft,title:event.target.value})} />}</Field>
      <label>Scope <select value={draft.binding.mode ?? "ALL"} onChange={(event)=>setDraft({...draft,binding:{...draft.binding,mode:event.target.value as DashboardWidget["binding"]["mode"]}})}><option value="ALL">All permitted resources</option><option value="DYNAMIC">Follow runtime filters</option><option value="FIXED">Selected equipment</option><option value="UNBOUND">Unbound</option></select></label>
      <EntitySelector label="Binding device" options={[]} loadOptions={dashboardDeviceOptions} value={deviceId} onChange={(id)=>setDraft({...draft,binding:{mode:"FIXED",deviceIds:id?[id]:[]},query:{...draft.query,metric:undefined,unit:undefined,series:[]}})} />
      {needsMetric?<Field label="Registered metric" required error={metricError ?? undefined} hint={!metrics.length?"Select a device with recorded metrics in the last 30 days.":undefined}>{(props)=><select {...props} value={String(draft.query.metric ?? "")} onChange={(event)=>query({metric:event.target.value})}><option value="">Select metric</option>{draft.query.metric && !selected?<option value={String(draft.query.metric)} disabled>Unavailable metric</option>:null}{metrics.map((metric)=><option key={metric.name} value={metric.name}>{metric.displayName} · {metric.unit}</option>)}</select>}</Field>:null}
      <small>Units: {selected?.unit ?? "As recorded by the evidence owner"}. Unavailable metrics never become zero.</small>
      {["energy-period","peak-and-ranking"].includes(widget.type)?<><label><input type="checkbox" checked={draft.query.independentMetersConfirmed===true} onChange={(event)=>query({independentMetersConfirmed:event.target.checked})}/>I reviewed this scope and selected independent physical meters</label><p>Use one upstream meter or its independent children. Duplicate identities and known parent/submeter overlap are rejected. Missing energy evidence stays partial or unavailable.</p>{deviceId?<EnergyMeterSetup key={deviceId} deviceId={deviceId}/>:<p>Select equipment to set up its physical meter. Review every included meter when using all resources or runtime filters.</p>}</>:null}
    </fieldset>
    <fieldset hidden={section!=="Calculation"}><legend>Calculation and time</legend>
      {manifest.reducers.length?<label>Calculation <select value={String(draft.query.reducer ?? manifest.reducers[0])} onChange={(event)=>query({reducer:event.target.value})}>{manifest.reducers.map((reducer)=><option key={reducer}>{{RAW:"Recorded samples",AVG:"Average",MEAN:"Average",MIN:"Minimum",MAX:"Maximum",P95:"95th percentile"}[reducer] ?? reducer}</option>)}</select></label>:null}
      <label>Time basis <select value={String((draft.query.time as {mode?:string}|undefined)?.mode ?? "INHERITED")} onChange={(event)=>query({time:{mode:event.target.value,preset:event.target.value==="CALENDAR"?"TODAY":"LAST_24_HOURS",timezone:Intl.DateTimeFormat().resolvedOptions().timeZone}})}><option value="INHERITED">Dashboard time</option><option value="RELATIVE">Relative interval</option><option value="CALENDAR">Calendar day</option></select></label>
      {widget.type==="incident-list"?<label>Incident basis <select value={String(draft.query.incidentMode ?? "ACTIVE")} onChange={(event)=>query({incidentMode:event.target.value})}><option value="ACTIVE">Current active incidents</option><option value="CREATED">Created within interval</option></select></label>:null}
      <small>Recorded evidence · {manifest.timeBasis}. This configuration does not change alert rules.</small>
      {(draft.query.time as {mode?:string}|undefined)?.mode==="RELATIVE"?<label>Widget relative period<select value={String((draft.query.time as {preset?:string}).preset ?? "LAST_24_HOURS")} onChange={(event)=>query({time:{...(draft.query.time as object),preset:event.target.value}})}>{["LAST_6_HOURS","LAST_24_HOURS","LAST_7_DAYS","LAST_30_DAYS",...(widget.type==="maintenance-agenda"?["NEXT_7_DAYS","NEXT_30_DAYS"]:[])].map((preset)=><option key={preset}>{preset}</option>)}</select></label>:null}
    </fieldset>
    <fieldset hidden={section!=="Appearance"}><legend>Presentation</legend><label>Format <select value={String(draft.presentation.format ?? "auto")} onChange={(event)=>setDraft({...draft,presentation:{...draft.presentation,format:event.target.value}})}><option value="auto">Automatic</option><option value="table">Readable table</option></select></label>
      {([['showTitle','Show panel title'],['showLegend','Show device and metric labels'],['showCalculation','Show calculation explanations'],['showComparison','Show period comparison'],['showDetails','Show measurement details below the panel'],...(widget.type==='time-series'?[['showAxes','Show chart axes']]:[])] as [string,string][]).map(([key,label])=><label className="dashboard-presentation-toggle" key={key}><input type="checkbox" checked={key==='showAxes'?draft.presentation[key]!==false:draft.presentation[key]===true} onChange={(event)=>setDraft({...draft,presentation:{...draft.presentation,[key]:event.target.checked}})}/>{label}</label>)}
      {widget.type==="context-note"?<label>Context note <textarea maxLength={1000} value={String(draft.payload.text ?? "")} onChange={(event)=>setDraft({...draft,payload:{text:event.target.value}})} /></label>:null}
      <label>Visual threshold (presentation only)<input type="number" value={String(draft.presentation.threshold ?? "")} onChange={(event)=>setDraft({...draft,presentation:{...draft.presentation,threshold:event.target.value?Number(event.target.value):null}})} /></label>
    </fieldset>
    <fieldset hidden={section!=="Navigation"}><legend>Drill-down</legend><label>Destination <select value={draft.navigation.href ?? ""} onChange={(event)=>setDraft({...draft,navigation:event.target.value?{href:event.target.value}:{}})}><option value="">None</option><option value="/devices">Devices</option><option value="/alerts">Alerts</option><option value="/maintenance">Maintenance</option>{deviceId?<option value={`/devices/${deviceId}`}>Bound device</option>:null}</select></label></fieldset>
    {layoutControls?<div hidden={section!=="Layout"}>{layoutControls}</div>:null}
    <div className="action-row" hidden={section==="Layout"}><button type="submit" className="primary-button" disabled={invalid}>Apply changes</button><button type="button" className="ghost-button" onClick={onCancel}>Cancel</button></div>
  </form>;
}

