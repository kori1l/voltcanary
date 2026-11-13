import {useEffect,useState} from "react";
import {Link} from "react-router-dom";
import {listDataSources,type DataSource} from "../devices/devicesApi";
import {getPositionEvidence,type PositionEvidence} from "./positionApi";
import {VehicleMap} from "./VehicleMap";

export function VehicleMovementPanel({deviceId}:{deviceId:string}) {
  const [sources,setSources]=useState<DataSource[]>([]),[source,setSource]=useState(""),[minutes,setMinutes]=useState(15);
  const [evidence,setEvidence]=useState<PositionEvidence|null>(null),[error,setError]=useState<string|null>(null),[loaded,setLoaded]=useState(false);
  useEffect(()=>{
    let active=true;
    void listDataSources(deviceId).then(items=>{if(active){const gps=items.filter(item=>item.protocol==="REST");setSources(gps);setSource(gps.length===1?gps[0].externalId:"");setLoaded(true);}}).catch(err=>{if(active){setError(err instanceof Error?err.message:"Sources unavailable");setLoaded(true);}});
    return()=>{active=false;};
  },[deviceId]);
  useEffect(()=>{
    setEvidence(null);setError(null);
    if(!source)return;
    let active=true,inFlight=false;
    let controller:AbortController|undefined;
    async function refresh(){
      if(!active || inFlight || document.hidden)return;
      inFlight=true;controller=new AbortController();
      const now=Date.now();
      try {
        const result=await getPositionEvidence(deviceId,source,new Date(now-minutes*60000).toISOString(),new Date(now).toISOString(),controller.signal);
        if(active){setEvidence(result);setError(null);}
      }catch(err){if(active && !controller.signal.aborted)setError(err instanceof Error?err.message:"GPS refresh unavailable");}
      finally{inFlight=false;}
    }
    void refresh();const interval=setInterval(()=>void refresh(),5000);
    const visibility=()=>{if(document.hidden)controller?.abort();else void refresh();};
    document.addEventListener("visibilitychange",visibility);
    return()=>{active=false;controller?.abort();clearInterval(interval);document.removeEventListener("visibilitychange",visibility);};
  },[deviceId,source,minutes]);
  return <section className="panel"><div className="vehicle-movement-controls"><label>GPS source<select value={source} onChange={event=>setSource(event.target.value)}><option value="">Select source</option>{sources.map(item=><option key={item.id} value={item.externalId}>{item.externalId}</option>)}</select></label><label>Trail<select value={minutes} onChange={event=>setMinutes(Number(event.target.value))}><option value={15}>Last 15 minutes</option><option value={60}>Last hour</option><option value={1440}>Last 24 hours</option></select></label><Link to={`/devices/${deviceId}?tab=configuration`}>Configure sources</Link>{sources.find(item=>item.externalId===source)?.sourceType==="SIMULATOR"?<Link to={`/devices/${deviceId}?tab=simulation`}>Open Demo Tools</Link>:null}</div>
    {error?<p role="alert">{error}{evidence?" · Previous GPS snapshot retained; refresh failed.":""}</p>:null}
    {loaded && !sources.length?<p>No REST GPS source. Add a source in Configuration.</p>:null}
    {source?<div className="vehicle-movement-panel"><VehicleMap key={`${deviceId}:${source}:${minutes}`} evidence={evidence}/></div>:null}
  </section>;
}
