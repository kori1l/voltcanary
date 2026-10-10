import { useState } from "react";
import { useConsoleQuery } from "../../../app/useConsoleQuery";
import type { RuntimeContext } from "../../../dashboards/dashboardsApi";
import type { DashboardTime } from "../../../dashboards/dashboardQueriesApi";
import {EntitySelector} from "../../../components/EntitySelector";
import {dashboardDeviceOptions,dashboardLocationOptions} from "../model/dashboardEntityOptions";
export function runtimeFromUrl(params:URLSearchParams,defaults:RuntimeContext):RuntimeContext {
  const preset=params.get("period") as DashboardTime["preset"];
  return {scope:params.get("scopeMode")==="ALL"?{mode:"ALL"}:params.get("deviceId")?{mode:"DYNAMIC",deviceIds:[params.get("deviceId")!]}:params.get("locationId")?{mode:"DYNAMIC",locationIds:[params.get("locationId")!],includeSubtree:true}:defaults.scope,
    time:params.has("from") && params.has("to")?{mode:"FIXED",from:params.get("from")!,to:params.get("to")!,timezone:params.get("timezone") ?? defaults.time?.timezone ?? "UTC"}:preset?{mode:["TODAY","WEEK_TO_DATE","MONTH_TO_DATE"].includes(preset)?"CALENDAR":"RELATIVE",preset,timezone:params.get("timezone") ?? defaults.time?.timezone ?? "UTC"}:defaults.time};
}
export function DashboardRuntimeControls({context,onDefaults,disabled=false}:{context:RuntimeContext;onDefaults?:(context:RuntimeContext)=>void;disabled?:boolean}) {
  const query=useConsoleQuery();const [preset,setPreset]=useState(context.time?.preset ?? "LAST_24_HOURS");const [timezone,setTimezone]=useState(context.time?.timezone ?? "UTC");
  const [from,setFrom]=useState(context.time?.from?.slice(0,16) ?? "");const [to,setTo]=useState(context.time?.to?.slice(0,16) ?? "");const [fixed,setFixed]=useState(context.time?.mode==="FIXED");const [error,setError]=useState<string|null>(null);
  const [deviceId,setDeviceId]=useState(context.scope?.deviceIds?.[0] ?? "");const [locationId,setLocationId]=useState(context.scope?.locationIds?.[0] ?? "");
  function apply(save=false){
    try{new Intl.DateTimeFormat(undefined,{timeZone:timezone});if(fixed && (!from || !to || Date.parse(from)>=Date.parse(to) || Date.parse(to)-Date.parse(from)>31*86400000))throw new Error("Choose a positive interval of at most 31 days.");
      const time:DashboardTime=fixed?{mode:"FIXED",from:new Date(`${from}Z`).toISOString(),to:new Date(`${to}Z`).toISOString(),timezone}:{mode:["TODAY","WEEK_TO_DATE","MONTH_TO_DATE"].includes(preset)?"CALENDAR":"RELATIVE",preset,timezone};
      const scope=deviceId?{mode:"DYNAMIC" as const,deviceIds:[deviceId]}:locationId?{mode:"DYNAMIC" as const,locationIds:[locationId],includeSubtree:true}:{mode:"ALL" as const};
      setError(null);if(save)onDefaults?.({scope,time});else query.update({scopeMode:scope.mode,deviceId,locationId:deviceId?null:locationId,period:fixed?null:preset,timezone,from:fixed?time.from:null,to:fixed?time.to:null});
    }catch(error){setError(error instanceof Error?error.message:"Invalid time context");}
  }
  return <form className="dashboard-toolbar" onSubmit={(event)=>{event.preventDefault();apply();}}>
    <EntitySelector label="Runtime device" options={[]} value={deviceId} loadOptions={dashboardDeviceOptions} disabled={disabled} onChange={(value)=>{setDeviceId(value);setLocationId("");}}/>
    <EntitySelector label="Runtime location" options={[]} value={locationId} loadOptions={dashboardLocationOptions} disabled={disabled} onChange={(value)=>{setLocationId(value);setDeviceId("");}}/>
    <label>Time mode <select disabled={disabled} value={fixed?"fixed":"preset"} onChange={(event)=>setFixed(event.target.value==="fixed")}><option value="preset">Preset</option><option value="fixed">Fixed UTC interval</option></select></label>
    {fixed?<><label>From (UTC)<input type="datetime-local" value={from} onChange={(event)=>setFrom(event.target.value)} /></label><label>To (UTC)<input type="datetime-local" value={to} onChange={(event)=>setTo(event.target.value)}/></label></>:<label>Period <select value={preset} onChange={(event)=>setPreset(event.target.value as typeof preset)}>{["LAST_24_HOURS","LAST_7_DAYS","LAST_30_DAYS","TODAY","WEEK_TO_DATE","MONTH_TO_DATE"].map((value)=><option key={value}>{value}</option>)}</select></label>}
    <label>Timezone <input value={timezone} maxLength={80} onChange={(event)=>setTimezone(event.target.value)}/></label>
    <button type="submit" className="ghost-button" disabled={disabled}>Apply runtime context</button>{onDefaults?<button type="button" className="ghost-button" disabled={disabled} onClick={()=>apply(true)}>Save as draft defaults</button>:null}<small>Runtime changes do not publish defaults.</small>{error?<p role="alert">{error}</p>:null}
  </form>;
}
