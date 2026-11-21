import {VehicleCurrentSpeed} from "./VehicleCurrentSpeed";
import {TransportDetails} from "./TransportDetails";
import {useEffect,useMemo,useState} from "react";
import {Link,useSearchParams} from "react-router-dom";
import {openAssistant} from "../aiAgent/assistantWindowState";
import {SidebarToggle} from "../components/NavigationVisibility";
import {listDataSources,type Device,type DataSource} from "../devices/devicesApi";
import {discoverFleet} from "./fleetDiscovery";
import {fleetPositions,readMonitoring,type VehicleTarget} from "./monitoringApi";
import {defaultMonitoring,movementState,type MonitoringSettings,type MovementState} from "./monitoringModel";
import type {LatestPosition,PositionEvidence} from "./positionApi";
import {FleetMap} from "./FleetMap";
import {VehicleHistory} from "./VehicleHistory";
import {VehicleZones} from "./VehicleZones";
import "./vehicle.css";
interface FleetDevice {device:Device;sources:DataSource[];sourceId:string}
export function VehicleConsole(){
  const [params,setParams]=useSearchParams();
  const [dirty,setDirty]=useState(false);
  const [acceptedReview,setAcceptedReview]=useState<string|undefined>(params.get("reviewId")??undefined);
  useEffect(()=>{if(!dirty)setAcceptedReview(params.get("reviewId")??undefined);},[dirty,params.get("reviewId")]);
  const requestedDevice=params.get("deviceId"),requestedSource=params.get("dataSourceId");
  const canLeave=()=>!dirty||window.confirm("Discard unsaved monitoring changes before changing vehicle, source or section?");
  function selectVehicle(id:string){if(!canLeave())return;setDirty(false);setSelected(id);setParams(previous=>{const next=new URLSearchParams(previous);next.set("deviceId",id);next.delete("dataSourceId");next.delete("reviewId");next.delete("latitude");next.delete("longitude");return next;},{replace:true});}
  function sectionChange(value:string){if(!canLeave())return;setDirty(false);setSection(value);setParams(previous=>{const next=new URLSearchParams(previous);next.set("section",value);next.delete("reviewId");return next;},{replace:true});}

  const [devices,setDevices]=useState<FleetDevice[]>([]),[positions,setPositions]=useState<LatestPosition[]>([]),[selected,setSelected]=useState(requestedDevice??""),[search,setSearch]=useState(""),[filter,setFilter]=useState<MovementState|"ALL">("ALL"),[section,setSection]=useState(["LIVE","HISTORY","ZONES"].includes(params.get("section")??"")?params.get("section")!:"LIVE");
  const [error,setError]=useState<string|null>(null),[discoveryError,setDiscoveryError]=useState<string|null>(null),[loading,setLoading]=useState(true),[reload,setReload]=useState(0),[capped,setCapped]=useState(false);
  useEffect(()=>{let active=true;const controller=new AbortController();setLoading(true);setDiscoveryError(null);setDevices([]);setPositions([]);
    void discoverFleet(controller.signal).then(async ({items,capped})=>{
      const found=await Promise.all(items.map(async device=>{const sources=(await listDataSources(device.id)).filter(source=>source.protocol==="REST");return {device,sources,sourceId:device.id===requestedDevice&&requestedSource?sources.find(source=>source.id===requestedSource)?.externalId??"":sources.length===1?sources[0].externalId:""};}));
      if(active){setCapped(capped);setDevices(found);setSelected(id=>requestedDevice?(found.some(item=>item.device.id===requestedDevice)?requestedDevice:""):found.some(item=>item.device.id===id)?id:found[0]?.device.id??"");}
    }).catch(err=>{if(active)setDiscoveryError(err instanceof Error?err.message:"Vehicle discovery failed");}).finally(()=>{if(active)setLoading(false);});
    return()=>{active=false;controller.abort();};
  },[reload]);
  const targets=useMemo(()=>devices.filter(item=>item.sourceId).map(item=>({deviceId:item.device.id,sourceId:item.sourceId})),[devices]);
  useEffect(()=>{setPositions([]);setError(null);},[targets]);
  useEffect(()=>{if(!targets.length)return;let active=true,inFlight=false;let controller:AbortController|undefined;
    async function refresh(){if(inFlight||!active||document.hidden||section!=="LIVE")return;inFlight=true;controller=new AbortController();try{const result=await fleetPositions(targets,controller.signal);if(active){setPositions(result);setError(null);}}catch(err){if(active&&!controller.signal.aborted)setError(err instanceof Error?err.message:"Fleet refresh failed");}finally{inFlight=false;}}
    void refresh();const timer=setInterval(()=>void refresh(),5000),visibility=()=>{if(document.hidden)controller?.abort();else void refresh();};document.addEventListener("visibilitychange",visibility);
    return()=>{active=false;controller?.abort();clearInterval(timer);document.removeEventListener("visibilitychange",visibility);};
  },[targets,section]);
  useEffect(()=>{
    if(!requestedDevice||!devices.length)return;
    const item=devices.find(item=>item.device.id===requestedDevice);
    if(!item){setError("Selected vehicle is unavailable. Choose an authorized vehicle from the list.");return;}
    const nextSection=params.get("section"),nextSource=requestedSource?item.sources.find(source=>source.id===requestedSource)?.externalId??"":item.sourceId;
    if(dirty&&(requestedDevice!==selected||nextSource!==item.sourceId||(nextSection&&nextSection!==section))){setError("Unsaved monitoring changes retained. Save or reload before switching context.");return;}
    if(requestedDevice!==selected)setSelected(requestedDevice);
    if(nextSection&&["LIVE","HISTORY","ZONES"].includes(nextSection)&&nextSection!==section)setSection(nextSection);
    if(nextSource!==item.sourceId)setDevices(values=>values.map(value=>value.device.id===requestedDevice?{...value,sourceId:nextSource}:value));
  },[requestedDevice,requestedSource,params.get("section"),selected,devices,dirty,section]);
  const current=devices.find(item=>item.device.id===selected),latest=positions.find(item=>item.deviceId===selected&&item.sourceId===current?.sourceId);
  const [settings,setSettings]=useState<MonitoringSettings>(defaultMonitoring),[settingsError,setSettingsError]=useState<string|null>(null),[settingsLoading,setSettingsLoading]=useState(false);
  useEffect(()=>{setSettings(defaultMonitoring);setSettingsError(null);if(!selected)return;const controller=new AbortController();setSettingsLoading(true);void readMonitoring(selected,controller.signal).then(setSettings).catch(err=>{if(!controller.signal.aborted)setSettingsError(err instanceof Error?err.message:"Settings unavailable");}).finally(()=>{if(!controller.signal.aborted)setSettingsLoading(false);});return()=>controller.abort();},[selected]);
  const visible=devices.filter(item=>item.device.name.toLowerCase().includes(search.toLowerCase())&&(filter==="ALL"||movementState(positions.find(point=>point.deviceId===item.device.id&&point.sourceId===item.sourceId))===filter));
  const markers=visible.flatMap(item=>{const latest=positions.find(point=>point.deviceId===item.device.id&&point.sourceId===item.sourceId);return latest?[{deviceId:item.device.id,name:item.device.name,latest}]:[];});
  const target:VehicleTarget|undefined=current?.sourceId?{deviceId:current.device.id,sourceId:current.sourceId}:undefined;
  const evidence:PositionEvidence|null=latest?{latest,history:{...latest,from:latest.asOf,to:latest.asOf,points:latest.position?[latest.position]:[],returnedCount:latest.position?1:0,truncated:false}}:null;
  return <main className="vehicle-console"><header className="vehicle-console-header"><div className="vehicle-console-heading"><SidebarToggle/><h1>Vehicles</h1></div><div className="action-row"><Link className="primary-button" to="/devices">Add vehicle</Link><button type="button" className="ghost-button" onClick={()=>setReload(value=>value+1)}>Reload fleet</button></div></header>
    {loading?<p role="status">Loading vehicles and sources…</p>:null}{discoveryError?<p role="alert">{discoveryError}</p>:null}{error?<p role="alert">{error}. Last fleet snapshot retained; live refresh unavailable.</p>:null}{capped?<p>First 100 vehicles shown.</p>:null}
    {section!=="LIVE"?<p role="status">Live fleet refresh paused while viewing history or editing settings. The map retains the last live snapshot.</p>:null}
    <div className="vehicle-fleet-layout">
      <aside className="vehicle-fleet-sidebar" aria-label="Vehicle filters and list">
        <div className="vehicle-fleet-filters">
          <label>Search vehicles<input type="search" placeholder="Name or vehicle ID" value={search} onChange={event=>setSearch(event.target.value)}/></label>
          <label>Movement status<select value={filter} onChange={event=>setFilter(event.target.value as MovementState|"ALL")}>{["ALL","MOVING","PARKED","STALE","NO_DATA","UNKNOWN"].map(state=><option key={state}>{state}</option>)}</select></label>
          <span className="vehicle-fleet-count">{devices.length} vehicles · 5s visible live refresh</span>
        </div>
        <div className="vehicle-fleet-list">{visible.map(item=>{
          const point=positions.find(point=>point.deviceId===item.device.id&&point.sourceId===item.sourceId);
          return <article key={item.device.id} className={selected===item.device.id?"selected":""}>
            <button type="button" className="vehicle-fleet-select" aria-label={item.device.name} aria-pressed={selected===item.device.id} onClick={()=>selectVehicle(item.device.id)}>
              <span className="vehicle-fleet-item-heading"><strong>{item.device.name}</strong><span className="vehicle-fleet-status">{movementState(point)}</span></span>
              {point?.position?<small><VehicleCurrentSpeed latest={point} unavailable={Boolean(error)||section!=="LIVE"}/><time dateTime={point.position.recordedAt}>{new Date(point.position.recordedAt).toLocaleString()}</time></small>:null}
            </button>
            {item.sources.length!==1?<label>GPS source<select value={item.sourceId} onChange={event=>{if(!canLeave())return;setDirty(false);const source=item.sources.find(source=>source.externalId===event.target.value);setDevices(values=>values.map(value=>value.device.id===item.device.id?{...value,sourceId:event.target.value}:value));setParams(previous=>{const next=new URLSearchParams(previous);next.set("deviceId",item.device.id);next.delete("reviewId");if(source)next.set("dataSourceId",source.id);else next.delete("dataSourceId");return next;},{replace:true});}}><option value="">Select source</option>{item.sources.map(source=><option key={source.id} value={source.externalId}>{source.externalId}</option>)}</select></label>:null}
          </article>;
        })}{!loading&&!discoveryError&&!devices.length?<p>No vehicles. Create an Automobile in Devices and configure its GPS source.</p>:null}</div>
      </aside>
      <FleetMap items={markers} selected={selected} onSelect={selectVehicle}/>
    </div>
    {current?<section className="panel vehicle-console-detail"><header className="section-header"><h2>{current.device.name}</h2><button type="button" className="ghost-button" onClick={()=>{if(dirty){window.alert("Review or discard unsaved monitoring changes before asking AI.");return;}setParams(previous=>{const next=new URLSearchParams(previous);next.set("deviceId",selected);const source=current.sources.find(source=>source.externalId===current.sourceId);if(source)next.set("dataSourceId",source.id);else next.delete("dataSourceId");return next;},{replace:true});openAssistant();}}>AI monitoring</button>{current.device.metadata?.feedProvider === "HSL" ? <Link to={`/devices/${selected}?tab=movement`}>Device & source</Link> : <Link to={`/devices/${selected}?tab=simulation`}>Configure emulator</Link>}</header><div className="action-row vehicle-console-tabs">{["LIVE","HISTORY","ZONES"].map(value=><button type="button" key={value} className={section===value?"primary-button":"ghost-button"} onClick={()=>sectionChange(value)}>{value==="HISTORY"?"Trips & playback":value==="ZONES"?"Zones & settings":"Live measurements"}</button>)}</div>
      {settingsError?<p role="alert">{settingsError}. Monitoring defaults shown; saved settings not loaded.</p>:null}
      {section==="LIVE"?<div className="vehicle-live-summary"><p>{latest?.position?`${latest.position.latitude.toFixed(5)}, ${latest.position.longitude.toFixed(5)}`:"Waiting for GPS observations"}</p><span>Ignition: {latest?.position?.ignition==null?"No data":latest.position.ignition===1?"On":"Off"}</span><span>Fuel: {latest?.position?.fuelPercent==null?"No data":`${latest.position.fuelPercent.toFixed(1)}%`}</span><span>Odometer: {latest?.position?.odometerKm==null?"No data":`${latest.position.odometerKm.toFixed(2)} km`}</span>{current.device.metadata?.feedProvider === "HSL" ? <p>Real GPS observations · Helsinki · Source: HSL HFP open data. Fuel, ignition and odometer are not supplied by this feed.</p> : <p>Vehicle values are simulated when enabled in Demo Tools. Odometer starts from the configured value on each run.</p>}</div>:null}
      {section==="LIVE"?<TransportDetails context={latest?.position?.transport}/>:null}
      {section==="HISTORY"&&target?<VehicleHistory key={`${selected}:${target.sourceId}`} target={target} settings={settings}/>:null}
      {section==="ZONES"&&!settingsLoading&&!settingsError?<VehicleZones key={`${selected}:${current.sourceId}`} reviewId={acceptedReview} onDirtyChange={setDirty} onAiPoint={(latitude,longitude)=>{setParams(previous=>{const next=new URLSearchParams(previous);next.set("deviceId",selected);next.set("latitude",String(latitude));next.set("longitude",String(longitude));const source=current.sources.find(source=>source.externalId===current.sourceId);if(source)next.set("dataSourceId",source.id);else next.delete("dataSourceId");return next;},{replace:true});openAssistant();}} onReviewChange={id=>setParams(previous=>{const next=new URLSearchParams(previous);if(id)next.set("reviewId",id);else next.delete("reviewId");return next;},{replace:true})} deviceId={selected} settings={settings} onSave={setSettings} evidence={evidence}/>:null}
      {!target?<p>Select a GPS source for this vehicle.</p>:null}
    </section>:null}
  </main>;
}
