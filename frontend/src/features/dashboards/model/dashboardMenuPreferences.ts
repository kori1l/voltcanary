import {useSyncExternalStore} from "react";
type MenuPreferences={collapsed:boolean;hidden:string[]};
const empty:MenuPreferences={collapsed:false,hidden:[]};
const cache=new Map<string,MenuPreferences>();
const listeners=new Set<()=>void>();
function key(actor:string){return `voltcanary.dashboard-menu.${actor}`;}
export function readDashboardMenu(actor?:string):MenuPreferences{
 if(!actor)return empty;
 const cached=cache.get(actor);if(cached)return cached;
 let value=empty;
 try{const parsed=JSON.parse(localStorage.getItem(key(actor)) ?? "null");if(parsed && typeof parsed.collapsed==="boolean" && Array.isArray(parsed.hidden))value={collapsed:parsed.collapsed,hidden:parsed.hidden.filter((id:unknown)=>typeof id==="string")};}catch{/* Storage may be unavailable. */}
 cache.set(actor,value);return value;
}
export function updateDashboardMenu(actor:string,update:(value:MenuPreferences)=>MenuPreferences){
 const value=update(readDashboardMenu(actor));cache.set(actor,value);
 try{localStorage.setItem(key(actor),JSON.stringify(value));}catch{/* Keep usable in memory. */}
 for(const listener of listeners)listener();
}
function subscribe(listener:()=>void){listeners.add(listener);return()=>{listeners.delete(listener);};}
export function useDashboardMenu(actor?:string){return useSyncExternalStore(subscribe,()=>readDashboardMenu(actor));}
