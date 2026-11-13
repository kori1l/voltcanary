import {useState,useId} from "react";
import {ChevronDown,ChevronUp} from "lucide-react";
import {FleetMap} from "./FleetMap";
import {PositionFieldDetails} from "./PositionFieldDetails";
import type {LatestPosition,PositionHistory} from "./positionApi";
import "./vehicle.css";

export function DashboardFleetMap({positions,devices,attribution,tracks=[],visibleFields,showDetails=true}:{positions:LatestPosition[];devices:{id:string;name:string}[];attribution?:string;tracks?:PositionHistory[];visibleFields?:string[];showDetails?:boolean}) {
  const [selected,setSelected]=useState("");
  const [tableOpen,setTableOpen]=useState(false);
  const tableId=useId();
  const names=new Map(devices.map(device=>[device.id,device.name]));
  const items=positions.map(latest=>({deviceId:latest.deviceId,name:names.get(latest.deviceId)??"Vehicle",latest}));
  const showSpeed=visibleFields===undefined?positions.some(item=>item.position?.fields?.speedKmh?.display!==false):visibleFields.includes("speedKmh");
  const active=positions.find(point=>point.deviceId===selected);
  return <div className="dashboard-fleet-map"><FleetMap items={items} selected={selected} onSelect={setSelected} initialFollow={false} initialFit tracks={tracks}/>
    {showDetails?<><button type="button" className="fleet-table-toggle" aria-expanded={tableOpen} aria-controls={tableId} onClick={()=>setTableOpen(value=>!value)}><span>Vehicles <span className="fleet-table-count">{items.length}</span></span>{tableOpen?<ChevronUp size={15}/>:<ChevronDown size={15}/>}</button>
    <div id={tableId} className="dashboard-fleet-table" hidden={!tableOpen}><table aria-label="Vehicle measurements"><thead><tr><th>Vehicle</th><th>Status</th>{showSpeed?<th className="fleet-speed">km/h</th>:null}<th>Updated</th></tr></thead><tbody>{items.map(item=><tr key={item.deviceId} className={selected===item.deviceId?"selected":""} onClick={()=>setSelected(item.deviceId)}>
      <td><button type="button" className="fleet-table-name" aria-pressed={selected===item.deviceId} onClick={()=>setSelected(item.deviceId)}>{item.name}</button></td>
      <td><span className={`fleet-table-status ${item.latest.freshness.toLowerCase()}`}><span aria-hidden="true"/>{item.latest.freshness==="FRESH"?"Fresh":item.latest.freshness==="STALE"?"Stale":"No data"}</span></td>
      {showSpeed?<td className="fleet-speed">{item.latest.position?.speedKmh==null||(visibleFields===undefined&&item.latest.position?.fields?.speedKmh?.display===false)?"—":item.latest.position.speedKmh.toFixed(1)}</td>:null}
      <td className="fleet-time">{item.latest.position?new Date(item.latest.position.recordedAt).toLocaleTimeString([], {hour:"2-digit",minute:"2-digit",second:"2-digit",hour12:false}):"—"}</td>
    </tr>)}</tbody></table></div>
    {active?.position?<small>{names.get(active.deviceId)} · {visibleFields===undefined&&!Object.keys(active.position.fields??{}).length?<>{active.position.latitude.toFixed(5)}, {active.position.longitude.toFixed(5)} · </>:null}{new Date(active.position.recordedAt).toLocaleTimeString()}</small>:<small>Select a vehicle marker or name. Recorded positions{attribution?` · Source: ${attribution}`:""}.</small>}
    {active?.position?<PositionFieldDetails point={active.position} visibleFields={visibleFields}/>:null}</>:null}
  </div>;
}
