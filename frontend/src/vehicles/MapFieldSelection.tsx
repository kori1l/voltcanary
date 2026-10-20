import {useEffect,useState} from "react";
import {getTelemetryFields} from "../telemetry/telemetryFieldsApi";
const defaults=['transport.routeShortName','transport.direction','transport.destination','transport.nextStopId','transport.scheduleDeviationSeconds','transport.doorsOpen','transport.accelerationMs2','transport.scheduledStart','transport.locationSource'];
export function MapFieldSelection({deviceIds,selected,onChange}:{deviceIds:string[];selected?:string[];onChange:(fields:string[]|undefined)=>void}){
  const [fields,setFields]=useState<{key:string;label:string;display:boolean}[]>([]),[error,setError]=useState('');
  const ids=deviceIds.join(',');
  useEffect(()=>{let active=true;const controller=new AbortController();setError('');void Promise.all(deviceIds.slice(0,20).map(id=>getTelemetryFields(id,undefined,controller.signal))).then(values=>{if(active)setFields([...new Map(values.flat().map(field=>[field.key,field])).values()]);}).catch(failure=>{if(active)setError(String(failure));});return()=>{active=false;controller.abort();};},[ids]);
  const options=[...new Map([...fields,...(selected??[]).filter(key=>!fields.some(field=>field.key===key)).map(key=>({key,label:`${key} (no current value)`,display:false}))].map(field=>[field.key,field])).values()];
  const defaultSelection=fields.filter(field=>field.display&&(!field.key.startsWith('transport.')||defaults.includes(field.key))).map(field=>field.key);
  return <fieldset><legend>Vehicle fields displayed on this map</legend><small>Display only; storage is configured in the source template.</small>{error?<p role="status">{error}</p>:null}
    <label><input type="checkbox" checked={selected===undefined} onChange={event=>onChange(event.target.checked?undefined:defaultSelection)}/>Use source display defaults</label>
    {options.map(field=><label key={field.key}><input type="checkbox" checked={(selected??defaultSelection).includes(field.key)} onChange={event=>{const prior=selected??defaultSelection;onChange(event.target.checked?[...prior,field.key]:prior.filter(key=>key!==field.key));}}/>{field.label}</label>)}
    {!options.length&&!error?<p>Fields appear after a device receives telemetry.</p>:null}
    <button type="button" className="ghost-button" onClick={()=>onChange([])}>Hide all fields</button>
  </fieldset>;
}
