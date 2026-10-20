import {useState} from "react";

const intervals=new Set([0,5000,30000,60000,300000]);
/** Local view preference: never writes the published dashboard or starts jobs. */
export function useDashboardRefreshPreference(id:string,hasVehicleMap:boolean) {
  const key=`voltcanary.dashboard.refresh.${id}`;
  const [value,setValue]=useState(()=>{
    try {const saved=localStorage.getItem(key);if(saved!==null && intervals.has(Number(saved)))return Number(saved);}catch{/* Storage may be unavailable. */}
    return hasVehicleMap?5000:0;
  });
  function save(interval:number){
    if(!intervals.has(interval))return;
    setValue(interval);
    try{localStorage.setItem(key,String(interval));}catch{/* Keep the current view usable. */}
  }
  return [value,save] as const;
}
