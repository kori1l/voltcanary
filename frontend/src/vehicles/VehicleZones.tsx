import {useEffect,useState} from "react";
import {confirmMonitoring,inspectMonitoring,prepareMonitoring,readMonitoringSnapshot,type MonitoringReview,type MonitoringSnapshot} from "./monitoringApi";
import {MonitoringReviewPreview} from "./MonitoringReviewPreview";
import {VehicleMap} from "./VehicleMap";
import type {MonitoringSettings,VehicleZone} from "./monitoringModel";
import type {PositionEvidence} from "./positionApi";
export function VehicleZones({deviceId,settings,onSave,evidence,reviewId,onReviewChange,onDirtyChange,onAiPoint}:{deviceId:string;settings:MonitoringSettings;onSave:(value:MonitoringSettings)=>void;evidence:PositionEvidence|null;reviewId?:string;onReviewChange?:(id:string|null)=>void;onDirtyChange?:(dirty:boolean)=>void;onAiPoint?:(latitude:number,longitude:number)=>void}) {
  const [draft,setDraft]=useState(settings),[base,setBase]=useState<MonitoringSnapshot|null>(null),[review,setReview]=useState<MonitoringReview|null>(null);
  const [active,setActive]=useState(""),[error,setError]=useState<string|null>(null),[busy,setBusy]=useState(false),[saved,setSaved]=useState(false),[unknown,setUnknown]=useState(false);
  const [pickingAi,setPickingAi]=useState(false);
  const dirty=!!base&&JSON.stringify(draft)!==JSON.stringify(base.settings);
  useEffect(()=>{onDirtyChange?.(dirty||busy);return()=>onDirtyChange?.(false);},[dirty,busy,onDirtyChange]);
  useEffect(()=>{const warn=(event:BeforeUnloadEvent)=>{if(dirty||busy){event.preventDefault();event.returnValue="";}};window.addEventListener("beforeunload",warn);return()=>window.removeEventListener("beforeunload",warn);},[dirty,busy]);
  useEffect(()=>{const controller=new AbortController();setBase(null);setReview(null);setError(null);
    void Promise.all([readMonitoringSnapshot(deviceId,controller.signal),reviewId?inspectMonitoring(deviceId,reviewId,controller.signal):Promise.resolve(null)]).then(([snapshot,restored])=>{
      if(controller.signal.aborted)return;setBase(snapshot);setDraft(snapshot.settings);setReview(restored);
    }).catch(err=>{if(!controller.signal.aborted)setError(err instanceof Error?err.message:"Monitoring unavailable");});return()=>controller.abort();
  },[deviceId,reviewId]);
  const change=(value:MonitoringSettings)=>{setDraft(value);setSaved(false);};
  function zone(id:string,patch:Partial<VehicleZone>){change({...draft,zones:draft.zones.map(zone=>zone.id===id?{...zone,...patch}:zone)});}
  function add(){const point=evidence?.latest.position;const id=crypto.randomUUID();change({...draft,zones:[...draft.zones,{id,name:`Zone ${draft.zones.length+1}`,latitude:point?.latitude??44.8125,longitude:point?.longitude??20.4612,radiusMetres:200}]});setActive(id);}
  async function save(){if(!base)return;setBusy(true);setError(null);try{const result=await prepareMonitoring(deviceId,base.revision,draft,crypto.randomUUID());setReview(result);onReviewChange?.(result.id);}catch(err){setError(err instanceof Error?err.message:"Review failed; reload current settings before reviewing again.");}finally{setBusy(false);}}
  async function finish(inspect:boolean){if(!review)return;setBusy(true);setError(null);if(!inspect)setUnknown(true);
    try{const result=inspect?await inspectMonitoring(deviceId,review.id):await confirmMonitoring(deviceId,review);
      if(result.id!==review.id||result.digest!==review.digest)throw new Error("Monitoring receipt differs from the review.");
      setReview(result);setUnknown(false);if(result.status==="SUCCEEDED"){const current=await readMonitoringSnapshot(deviceId);setBase(current);setDraft(current.settings);onSave(current.settings);setSaved(true);}
    }catch(err){setError(err instanceof Error?err.message:"Save outcome unavailable. Inspect the original review.");}finally{setBusy(false);}}
  async function reload(){if((dirty||unknown)&&!window.confirm("Discard this local draft and reload saved settings? The original review remains available."))return;
    setBusy(true);try{const current=await readMonitoringSnapshot(deviceId);setBase(current);setDraft(current.settings);onSave(current.settings);setReview(null);onReviewChange?.(null);setUnknown(false);setError(null);}catch(err){setError((err as Error).message);}finally{setBusy(false);}}
  if(review)return <section>{error?<p role="alert">{error}</p>:null}<MonitoringReviewPreview review={review}/>{saved?<p role="status">Monitoring settings saved. Current owner settings refreshed.</p>:null}{unknown?<p role="status">Save outcome unknown. Inspect this review before another confirmation.</p>:null}<div className="action-row">
    {review.status==="PREPARED"&&!unknown&&Date.parse(review.expiresAt)>Date.now()?<button type="button" className="primary-button" disabled={busy} onClick={()=>void finish(false)}>Confirm monitoring changes</button>:null}
    <button type="button" className="ghost-button" disabled={busy} onClick={()=>void finish(true)}>Inspect saved result</button>
    <button type="button" className="ghost-button" disabled={busy} onClick={()=>void reload()}>Reload current settings</button></div></section>;
  return <section>{!base&&!error?<p role="status">Loading monitoring settings…</p>:null}<button type="button" className="ghost-button" disabled={busy} onClick={()=>void reload()}>Reload current settings</button><form onSubmit={event=>{event.preventDefault();void save();}}><fieldset disabled={busy||!base}><div className="vehicle-movement-controls"><label>Speed limit (km/h)<input type="number" min="1" max="300" value={draft.speedLimitKmh} onChange={event=>change({...draft,speedLimitKmh:Number(event.target.value)})}/></label><label>Trip idle threshold (min)<input type="number" min="1" max="60" value={draft.idleMinutes} onChange={event=>change({...draft,idleMinutes:Number(event.target.value)})}/></label><button className="primary-button" type="submit">Review monitoring changes</button></div>
    {error?<p role="alert">{error}</p>:null}{saved?<p role="status">Monitoring settings saved.</p>:null}
    <h3>Geozones</h3><p>Select a zone, then click the map to set its centre. Zone events are calculated from accepted history; save before reviewing events.</p><button type="button" className="ghost-button" disabled={draft.zones.length>=50} onClick={add}>Add zone</button>
    <div className="vehicle-zone-list">{draft.zones.map(item=><fieldset key={item.id}><legend><label><input type="radio" name="active-zone" checked={active===item.id} onChange={()=>setActive(item.id)}/> Edit {item.name}</label></legend><label>Name<input required maxLength={100} value={item.name} onChange={event=>zone(item.id,{name:event.target.value})}/></label><label>Latitude<input type="number" step="0.00001" min="-90" max="90" value={item.latitude} onChange={event=>zone(item.id,{latitude:Number(event.target.value)})}/></label><label>Longitude<input type="number" step="0.00001" min="-180" max="180" value={item.longitude} onChange={event=>zone(item.id,{longitude:Number(event.target.value)})}/></label><label>Radius (metres)<input type="number" min="10" max="50000" value={item.radiusMetres} onChange={event=>zone(item.id,{radiusMetres:Number(event.target.value)})}/></label><button type="button" className="ghost-button" onClick={()=>change({...draft,zones:draft.zones.filter(zone=>zone.id!==item.id)})}>Remove zone</button></fieldset>)}</div>
    {onAiPoint?<button type="button" className="ghost-button" disabled={dirty||busy||!base} aria-pressed={pickingAi} onClick={()=>setPickingAi(value=>!value)}>Choose a point for AI</button>:null}
    {pickingAi?<p role="status">Click the existing map to choose a centre for AI. Saved settings stay unchanged.</p>:null}
    <div className="vehicle-zone-map"><VehicleMap evidence={evidence} zones={draft.zones} initialFollow={false} zoneEditing onPickPosition={pickingAi?(latitude,longitude)=>{setPickingAi(false);onAiPoint?.(latitude,((longitude+180)%360+360)%360-180);}:active?(latitude,longitude)=>zone(active,{latitude,longitude}):undefined}/></div>
  </fieldset></form></section>;
}
