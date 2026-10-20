import {useState} from "react";
import {saveMonitoring} from "./monitoringApi";
import {VehicleMap} from "./VehicleMap";
import type {MonitoringSettings,VehicleZone} from "./monitoringModel";
import type {PositionEvidence} from "./positionApi";
export function VehicleZones({deviceId,settings,onSave,evidence}:{deviceId:string;settings:MonitoringSettings;onSave:(value:MonitoringSettings)=>void;evidence:PositionEvidence|null}) {
  const [draft,setDraft]=useState(settings),[active,setActive]=useState(""),[error,setError]=useState<string|null>(null),[busy,setBusy]=useState(false),[saved,setSaved]=useState(false);
  const change=(value:MonitoringSettings)=>{setDraft(value);setSaved(false);};
  function zone(id:string,patch:Partial<VehicleZone>){change({...draft,zones:draft.zones.map(zone=>zone.id===id?{...zone,...patch}:zone)});}
  function add(){const point=evidence?.latest.position;const id=crypto.randomUUID();change({...draft,zones:[...draft.zones,{id,name:`Zone ${draft.zones.length+1}`,latitude:point?.latitude??44.8125,longitude:point?.longitude??20.4612,radiusMetres:200}]});setActive(id);}
  async function save(){setBusy(true);setError(null);try{const result=await saveMonitoring(deviceId,draft);onSave(result);setSaved(true);}catch(err){setError(err instanceof Error?err.message:"Settings save failed");}finally{setBusy(false);}}
  return <section><div className="vehicle-movement-controls"><label>Speed limit (km/h)<input type="number" min="1" max="300" value={draft.speedLimitKmh} onChange={event=>change({...draft,speedLimitKmh:Number(event.target.value)})}/></label><label>Trip idle threshold (min)<input type="number" min="1" max="60" value={draft.idleMinutes} onChange={event=>change({...draft,idleMinutes:Number(event.target.value)})}/></label><button className="primary-button" disabled={busy} onClick={()=>void save()}>Save monitoring settings</button></div>
    {error?<p role="alert">{error}</p>:null}{saved?<p role="status">Monitoring settings saved.</p>:null}
    <h3>Geozones</h3><p>Select a zone, then click the map to set its centre. Zone events are calculated from accepted history; save before reviewing events.</p><button type="button" className="ghost-button" disabled={draft.zones.length>=50} onClick={add}>Add zone</button>
    <div className="vehicle-zone-list">{draft.zones.map(item=><fieldset key={item.id}><legend><label><input type="radio" name="active-zone" checked={active===item.id} onChange={()=>setActive(item.id)}/> Edit {item.name}</label></legend><label>Name<input maxLength={100} value={item.name} onChange={event=>zone(item.id,{name:event.target.value})}/></label><label>Latitude<input type="number" step="0.00001" min="-90" max="90" value={item.latitude} onChange={event=>zone(item.id,{latitude:Number(event.target.value)})}/></label><label>Longitude<input type="number" step="0.00001" min="-180" max="180" value={item.longitude} onChange={event=>zone(item.id,{longitude:Number(event.target.value)})}/></label><label>Radius (metres)<input type="number" min="10" max="50000" value={item.radiusMetres} onChange={event=>zone(item.id,{radiusMetres:Number(event.target.value)})}/></label><button type="button" className="ghost-button" onClick={()=>change({...draft,zones:draft.zones.filter(zone=>zone.id!==item.id)})}>Remove zone</button></fieldset>)}</div>
    <div className="vehicle-zone-map"><VehicleMap evidence={evidence} zones={draft.zones} initialFollow={false} onPickPosition={active?(latitude,longitude)=>zone(active,{latitude,longitude}):undefined}/></div>
  </section>;
}
