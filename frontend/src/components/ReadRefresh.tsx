import {useState} from "react";
import {useReadRefresh} from "./useReadRefresh";
export function ReadRefresh({scope,refresh}:{scope:string;refresh:()=>Promise<unknown>}){
 const [interval,setIntervalMs]=useState(0);useReadRefresh({scope,refresh,interval});return <ReadRefreshControl interval={interval} onChange={setIntervalMs}/>;
}
export function ReadRefreshControl({interval,onChange}:{interval:number;onChange:(value:number)=>void}){
 return <label>Read refresh <select aria-label="Read refresh" value={interval} onChange={(event)=>onChange(Number(event.target.value))}><option value={0}>Off</option><option value={30_000}>30 seconds</option><option value={60_000}>1 minute</option><option value={300_000}>5 minutes</option></select></label>;
}
