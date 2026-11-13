import type {PositionPoint} from "./positionApi";
import {TransportDetails} from "./TransportDetails";
export function PositionFieldDetails({point,visibleFields,stale=false}:{point:PositionPoint;visibleFields?:string[];stale?:boolean}){
  if(visibleFields===undefined&&!Object.keys(point.fields??{}).length)return <TransportDetails context={point.transport}/>;
  const fields:Record<string,{value:unknown;label:string;unit:string;display:boolean}>={};
  for(const key of ['latitude','longitude','speedKmh','headingDeg','ignition','fuelPercent','odometerKm'] as const)if(point[key]!=null)fields[key]={value:point[key],label:key,unit:key==='speedKmh'?'km/h':'',display:true};
  Object.entries(point.transport??{}).forEach(([key,value])=>{if(value!=null)fields[`transport.${key}`]={value,label:key,unit:'',display:true};});
  Object.assign(fields,point.fields??{});
  const defaults=visibleFields===undefined&&Object.keys(point.fields??{}).length?point.fields!:fields;
  const entries=Object.entries(defaults).filter(([key,field])=>(!stale||key!=="speedKmh")&&(visibleFields===undefined?field.display:visibleFields.includes(key)));
  if(!entries.length)return null;
  return <dl className="transport-details" aria-label="Observed journey information">{entries.map(([key,field])=><div key={key}><dt>{field.label}</dt><dd>{typeof field.value==='boolean'?(field.value?'True':'False'):String(field.value)} {field.unit}</dd></div>)}</dl>;
}
