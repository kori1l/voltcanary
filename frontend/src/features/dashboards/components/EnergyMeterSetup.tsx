import {useEffect,useRef,useState} from "react";
import {Link} from "react-router-dom";
import {ApiError,sessionIdentityRevision} from "../../../api/client";
import {EntitySelector} from "../../../components/EntitySelector";
import {listDataSources,type DataSource} from "../../../devices/devicesApi";
import {commandKey} from "../../../dashboards/dashboardsApi";
import {dashboardDeviceOptions} from "../model/dashboardEntityOptions";
import {getEnergyMeter,saveEnergyMeter,type EnergyMeterDefinition,type EnergyMeterRequest} from "../model/energyMetersApi";

export function EnergyMeterSetup({deviceId}:{deviceId:string}){
  const [history,setHistory]=useState<EnergyMeterDefinition[]>([]),[sources,setSources]=useState<DataSource[]>([]);
  const [source,setSource]=useState(""),[method,setMethod]=useState<EnergyMeterRequest["authoritativeMethod"]>("COUNTER_DELTA"),[gap,setGap]=useState(900);
  const [effective,setEffective]=useState(new Date(Date.now()-86400000).toISOString().slice(0,16));
  const [fromNow,setFromNow]=useState(false);
  const [replace,setReplace]=useState(false),[sharedDevice,setSharedDevice]=useState(""),[parentDevice,setParentDevice]=useState("");
  const [error,setError]=useState<string|null>(null),[message,setMessage]=useState(""),[loading,setLoading]=useState(true),[busy,setBusy]=useState(false);
  const [reload,setReload]=useState(0);
  const [resetAt,setResetAt]=useState(""),[resetKind,setResetKind]=useState<"RESET"|"ROLLOVER">("RESET"),[rollover,setRollover]=useState("");
  const physical=useRef(commandKey()),lock=useRef(false),activeDevice=useRef(deviceId);activeDevice.current=deviceId;
  const latest=history[history.length-1];
  useEffect(()=>{const controller=new AbortController();setLoading(true);setHistory([]);setSources([]);setSource("");setMethod("COUNTER_DELTA");setGap(900);setFromNow(false);setEffective(new Date(Date.now()-86400000).toISOString().slice(0,16));setError(null);setMessage("");setReplace(false);setSharedDevice("");setParentDevice("");setResetAt("");physical.current=commandKey();
    void Promise.all([getEnergyMeter(deviceId,controller.signal),listDataSources(deviceId)]).then(([epochs,options])=>{
      if(controller.signal.aborted)return;setHistory(epochs);setSources(options);const current=epochs[epochs.length-1];
      setSource(current?.sourceId ?? options[0]?.id ?? "");setMethod(current?.authoritativeMethod ?? "COUNTER_DELTA");setGap(current?.maxGapSeconds ?? 900);
      if(current)setFromNow(true);
    }).catch((error)=>{if(!controller.signal.aborted)setError(error instanceof Error?error.message:"Meter metadata unavailable");}).finally(()=>{if(!controller.signal.aborted)setLoading(false);});return()=>controller.abort();},[deviceId,reload]);
  async function save(){if(lock.current || !source || !effective)return;lock.current=true;setBusy(true);setError(null);setMessage("");const identity=sessionIdentityRevision();const expected=deviceId;
    try{
      const shared=sharedDevice?(await getEnergyMeter(sharedDevice)).slice(-1)[0]:null;
      const parent=parentDevice?(await getEnergyMeter(parentDevice)).slice(-1)[0]:null;
      if(identity!==sessionIdentityRevision() || activeDevice.current!==expected)return;
      if(sharedDevice && !shared)throw new Error("Configure the shared physical meter first.");if(parentDevice && !parent)throw new Error("Configure the upstream meter first.");
      const events=[...(replace || (shared && shared.physicalMeterId!==latest?.physicalMeterId)?[]:latest?.counterEvents ?? [])];if(resetAt)events.push({at:new Date(`${resetAt}Z`).toISOString(),kind:resetKind,rolloverWh:resetKind==="ROLLOVER"?Number(rollover):null});
      const body:EnergyMeterRequest={expectedRevision:latest?.revision ?? 0,physicalMeterId:shared?.physicalMeterId ?? (latest && !replace?latest.physicalMeterId:physical.current),parentPhysicalMeterId:parent?.physicalMeterId ?? shared?.parentPhysicalMeterId ?? (!replace?latest?.parentPhysicalMeterId ?? null:null),sourceId:source,authoritativeMethod:method,maxGapSeconds:gap,effectiveFrom:fromNow?new Date().toISOString():new Date(`${effective}Z`).toISOString(),counterEvents:events};
      const saved=await saveEnergyMeter(deviceId,body);
      if(identity!==sessionIdentityRevision() || activeDevice.current!==expected)return;
      const renewed=await getEnergyMeter(deviceId);if(identity!==sessionIdentityRevision() || activeDevice.current!==expected)return;
      setHistory(renewed);physical.current=commandKey();setResetAt("");setReplace(false);setFromNow(true);setMessage(`Meter revision ${saved.revision} saved. Apply the widget configuration and refresh its evidence.`);
    }catch(error){if(identity===sessionIdentityRevision() && activeDevice.current===expected)setError(error instanceof ApiError && error.status===0?"The save may have reached the server. Reload meter metadata before retrying.":error instanceof Error?error.message:"Meter save failed");}finally{lock.current=false;if(activeDevice.current===expected)setBusy(false);}
  }
  return <details className="dashboard-editor-settings"><summary>Physical meter and calculation</summary>
    <p>Choose the physical meter behind this equipment. Its saved calculation applies to energy widgets; measurements and maintenance records are preserved.</p>
    {loading?<p role="status">Loading owned meter and source choices…</p>:<fieldset disabled={busy}>
      {latest?<p>Saved revision {latest.revision} · {latest.authoritativeMethod==="COUNTER_DELTA"?"Cumulative counter":"Power integration estimate"} · maximum gap {latest.maxGapSeconds}s</p>:<p>No physical meter is registered. Consumption is unavailable until this equipment is configured.</p>}
      <label>Recorded source<select value={source} onChange={(event)=>setSource(event.target.value)}><option value="">Choose a registered source</option>{sources.map((option)=><option key={option.id} value={option.id}>{option.externalId} · {option.protocol}</option>)}{source && !sources.some((option)=>option.id===source)?<option value={source}>Previously recorded source</option>:null}</select></label>
      {!sources.length?<p>Add an equipment source in <Link to={`/devices/${deviceId}`}>device settings</Link> before configuring metering.</p>:null}
      <label>Energy calculation<select value={method} onChange={(event)=>setMethod(event.target.value as EnergyMeterRequest["authoritativeMethod"])}><option value="COUNTER_DELTA">Cumulative counter difference (Wh / kWh)</option><option value="POWER_INTEGRAL">Power over time (W / kW), estimate</option></select></label>
      <label>Maximum accepted gap (seconds)<input type="number" min={1} max={86400} required value={gap} onChange={(event)=>setGap(Number(event.target.value))}/></label>
      <label><input type="checkbox" checked={fromNow} onChange={(event)=>setFromNow(event.target.checked)}/>Apply this revision from now (UTC)</label>
      {!fromNow?<label>Effective from (UTC)<input type="datetime-local" required value={effective} onChange={(event)=>setEffective(event.target.value)}/></label>:<p>The new epoch starts at this save. Earlier measurement epochs are preserved.</p>}
      <p>Longer gaps are excluded. Boundary interpolation is declared; partial coverage never becomes a complete period.</p>
      {latest?<label><input type="checkbox" checked={replace} onChange={(event)=>setReplace(event.target.checked)}/>The physical meter has been replaced</label>:null}
      <EntitySelector label="Same physical meter as equipment (optional)" options={[]} loadOptions={dashboardDeviceOptions} value={sharedDevice} onChange={setSharedDevice}/>
      <EntitySelector label="Upstream meter equipment (optional)" options={[]} loadOptions={dashboardDeviceOptions} value={parentDevice} onChange={setParentDevice}/>
      <p>Reuse identity for the same physical meter. Declare upstream relationships so selecting parent and submeter together is rejected.</p>
      {method==="COUNTER_DELTA"?<details><summary>Record a counter reset or rollover</summary><label>Event time (UTC)<input type="datetime-local" value={resetAt} onChange={(event)=>setResetAt(event.target.value)}/></label><label>Counter event<select value={resetKind} onChange={(event)=>setResetKind(event.target.value as typeof resetKind)}><option value="RESET">Reset / unknown discontinuity</option><option value="ROLLOVER">Known rollover</option></select></label>{resetKind==="ROLLOVER"?<label>Rollover modulus (Wh)<input type="number" min={0.000001} value={rollover} onChange={(event)=>setRollover(event.target.value)}/></label>:null}<p>A reset interval is excluded; only an explicit rollover modulus can recover its delta.</p></details>:null}
      <button type="button" className="primary-button" disabled={!source || !effective || !Number.isInteger(gap) || gap<1 || gap>86400 || (!!resetAt && resetKind==="ROLLOVER" && !(Number(rollover)>0))} onClick={()=>void save()}>{busy?"Saving meter…":"Save meter revision"}</button>
    </fieldset>}{error?<p role="alert">{error}</p>:null}{message?<p role="status">{message}</p>:null}<button type="button" className="ghost-button" disabled={busy} onClick={()=>setReload(value=>value+1)}>Reload meter metadata</button>
  </details>;
}
