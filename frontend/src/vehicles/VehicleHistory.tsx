import {useEffect,useMemo,useRef,useState} from "react";
import {recordedPositions,type VehicleTarget} from "./monitoringApi";
import {monitoringEvents,observedTrips,positionsCsv,type MonitoringSettings} from "./monitoringModel";
import {VehicleMap} from "./VehicleMap";
import type {PositionPoint,PositionEvidence} from "./positionApi";
const localTime=(date:Date)=>new Date(date.getTime()-date.getTimezoneOffset()*60000).toISOString().slice(0,19);
export function VehicleHistory({target,settings}:{target:VehicleTarget;settings:MonitoringSettings}) {
  const [from,setFrom]=useState(()=>localTime(new Date(Date.now()-6*3600000))),[to,setTo]=useState(()=>localTime(new Date()));
  const [points,setPoints]=useState<PositionPoint[]>([]),[truncated,setTruncated]=useState(false),[loading,setLoading]=useState(false),[progress,setProgress]=useState(0),[error,setError]=useState<string|null>(null);
  const [index,setIndex]=useState(0),[playing,setPlaying]=useState(false),[speed,setSpeed]=useState(5);
  const flight=useRef<AbortController|null>(null),playhead=useRef(0);
  useEffect(()=>()=>flight.current?.abort(),[]);
  useEffect(()=>{
    if(!playing||!points.length)return;
    let last=performance.now();playhead.current=Date.parse(points[index].recordedAt);
    const timer=setInterval(()=>{const now=performance.now();if(document.hidden){setPlaying(false);return;}playhead.current+=(now-last)*speed;last=now;
      let low=0,high=points.length-1;while(low<high){const mid=Math.ceil((low+high)/2);if(Date.parse(points[mid].recordedAt)<=playhead.current)low=mid;else high=mid-1;}setIndex(low);if(low===points.length-1)setPlaying(false);
    },200);
    return()=>clearInterval(timer);
  },[playing,speed,points]);
  async function load(){
    flight.current?.abort();const controller=new AbortController();flight.current=controller;setLoading(true);setError(null);setPlaying(false);setProgress(0);
    try{const start=new Date(from),end=new Date(to);if(!Number.isFinite(start.getTime())||!Number.isFinite(end.getTime())||start>=end||end.getTime()-start.getTime()>31*86400000)throw new Error("Select a positive period of at most 31 days.");
      const result=await recordedPositions(target,start.toISOString(),end.toISOString(),controller.signal,setProgress);if(!controller.signal.aborted){setPoints(result.points);setTruncated(result.truncated);setIndex(0);}
    }catch(err){if(!controller.signal.aborted)setError(err instanceof Error?err.message:"History unavailable");}finally{if(!controller.signal.aborted)setLoading(false);}
  }
  const trips=useMemo(()=>observedTrips(points,settings.idleMinutes),[points,settings.idleMinutes]);
  const events=useMemo(()=>monitoringEvents(points,settings),[points,settings]);
  const point=points[index];const evidence:PositionEvidence|null=point?{latest:{...target,asOf:point.recordedAt,freshness:"FRESH",position:point},history:{...target,from:points[0].recordedAt,to:point.recordedAt,points:points.slice(Math.max(0,index-999),index+1),returnedCount:Math.min(index+1,1000),truncated:index>=1000}}:null;
  const seek=(value:number)=>{setPlaying(false);setIndex(value);};
  function exportCsv(){const url=URL.createObjectURL(new Blob([positionsCsv(points)],{type:"text/csv;charset=utf-8"}));const link=document.createElement("a");link.href=url;link.download="vehicle-recordings.csv";link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
  return <section className="vehicle-history"><div className="action-row">{[1,6,24].map(hours=><button type="button" className="ghost-button" key={hours} onClick={()=>{setFrom(localTime(new Date(Date.now()-hours*3600000)));setTo(localTime(new Date()));}}>Last {hours}h</button>)}</div><div className="vehicle-movement-controls"><label>From<input type="datetime-local" step="1" value={from} onChange={event=>setFrom(event.target.value)}/></label><label>To<input type="datetime-local" step="1" value={to} onChange={event=>setTo(event.target.value)}/></label><button type="button" className="primary-button" onClick={()=>void load()} disabled={loading}>Load recordings</button><button type="button" className="ghost-button" disabled={!points.length} onClick={exportCsv}>Export CSV</button></div>
    {loading?<p role="status">Loading recorded points: {progress}</p>:null}{error?<p role="alert">{error} {points.length?"Previous loaded history retained.":""}</p>:null}{truncated?<p role="status">First 20000 samples loaded. Narrow the range for complete coverage.</p>:null}
    {!points.length&&!loading?<p>Select a period and load accepted GPS recordings.</p>:null}
    {point?<><div className="action-row"><button type="button" className="primary-button" onClick={()=>{if(index===points.length-1)setIndex(0);setPlaying(value=>!value);}}>{playing?"Pause":"Play"}</button><label>Playback speed<select value={speed} onChange={event=>setSpeed(Number(event.target.value))}><option value={1}>1×</option><option value={5}>5×</option><option value={20}>20×</option></select></label><span>{index+1} / {points.length} recorded samples</span></div><label className="vehicle-playback">Recorded time<input aria-label="Recorded time" type="range" min={0} max={points.length-1} value={index} onChange={event=>seek(Number(event.target.value))}/></label><VehicleMap evidence={evidence}/></>:null}
    <h3>Observed trips</h3><p>Distance from recorded GPS; incomplete trips may start/end outside the selected period. Gaps split coverage.</p><div className="table-scroll"><table><thead><tr><th>Start / end</th><th>Distance</th><th>Duration</th><th>Average / max</th><th>Coverage</th><th/></tr></thead><tbody>{trips.map((trip,i)=><tr key={i}><td>{new Date(points[trip.startIndex].recordedAt).toLocaleString()}<br/>{new Date(points[trip.endIndex].recordedAt).toLocaleTimeString()}</td><td>{trip.distanceKm.toFixed(2)} km</td><td>{Math.round(trip.elapsedSeconds/60)} min</td><td>{trip.averageSpeedKmh.toFixed(1)} / {trip.maxSpeedKmh?.toFixed(1)??"—"} km/h</td><td>{trip.incomplete?"Incomplete":"Observed"}</td><td><button onClick={()=>seek(trip.startIndex)}>View trip</button></td></tr>)}</tbody></table></div>
    <h3>Observed events</h3><p>Computed from loaded samples and current zones/limits; gaps have unknown coverage. These are historical evidence, not delivered notifications.</p><ul className="vehicle-events">{events.slice(-500).reverse().map((event,i)=><li key={i}><button type="button" className="ghost-button" onClick={()=>seek(event.pointIndex)}>{new Date(event.recordedAt).toLocaleString()} · {event.type} · {event.label}</button></li>)}</ul>{events.length>500?<p>Latest 500 events shown.</p>:null}
  </section>;
}
