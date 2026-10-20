import {EntitySelector} from "../components/EntitySelector";
import {VehicleSourceSelector} from "./VehicleSourceSelector";
import type {DashboardWidget} from "../dashboards/dashboardsApi";

export function VehicleFleetSources({draft,onChange,loadOptions}:{draft:DashboardWidget;onChange:(value:DashboardWidget)=>void;loadOptions:Parameters<typeof EntitySelector>[0]["loadOptions"]}) {
  const ids=draft.binding.deviceIds??[];
  const sources=(draft.query.vehicleSources??{}) as Record<string,string>;
  const update=(nextIds:string[],nextSources:Record<string,string>)=>onChange({...draft,binding:{...draft.binding,mode:"FIXED",deviceIds:nextIds},query:{...draft.query,sourceId:undefined,vehicleSources:nextSources}});
  return <div><p>One map for up to 20 vehicles. Select a GPS source for every device.</p>{ids.map((id,index)=><div key={index} className="action-row">
    <EntitySelector label={`Vehicle ${index+1}`} options={[]} loadOptions={loadOptions} value={id} onChange={next=>{const values={...sources};delete values[id];if(next)values[next]="";update(ids.map((value,i)=>i===index?next:value),values);}}/>
    <VehicleSourceSelector deviceId={id} value={sources[id]??""} onChange={source=>update(ids,{...sources,[id]:source})}/>
    <button type="button" className="ghost-button" disabled={ids.length===1} onClick={()=>{const values={...sources};delete values[id];update(ids.filter((_,i)=>i!==index),values);}}>Remove vehicle {index+1}</button>
  </div>)}<button type="button" className="ghost-button" disabled={ids.length>=20||ids.includes("")} onClick={()=>update([...ids,""],sources)}>Add vehicle to map</button></div>;
}
