import {apiRequest} from "../../../api/client";
import {listDevices,listLocations} from "../../../devices/devicesApi";
interface Request {search:string;offset:number;limit:number;signal:AbortSignal}
export async function dashboardDeviceOptions({search,offset,limit,signal}:Request){return (await listDevices({search,offset,limit},signal)).map((device)=>({id:device.id,name:device.name,status:device.status}));}
export async function vehicleDeviceOptions({search,offset,limit,signal}:Request){return (await listDevices({search,offset,limit,type:"VEHICLE"},signal)).map(device=>({id:device.id,name:device.name,status:device.status}));}
export async function refrigerationDeviceOptions({search,offset,limit,signal}:Request){return (await listDevices({search,offset,limit,type:"FRIDGE"},signal)).map((device)=>({id:device.id,name:device.name,status:device.status}));}
export async function dashboardLocationOptions({search,offset,limit,signal}:Request){return (await listLocations({search,offset,limit},signal)).map((location)=>({id:location.id,name:location.name}));}

export function workspaceDeviceOptions(workspaceId:string,accountId:string){return async({search,offset,limit,signal}:Request)=>{const scope=await apiRequest<{devices:{id:string;name:string;status:string}[]}>(`/workspaces/${workspaceId}/devices/query`,{method:"POST",signal,body:JSON.stringify({mode:"ALL",accountId})});return scope.devices.filter(d=>`${d.name} ${d.status}`.toLowerCase().includes(search.toLowerCase())).slice(offset,offset+limit);};}

export function workspaceLocationOptions(workspaceId:string,accountId:string){return async({search,offset,limit,signal}:Request)=>{const scope=await apiRequest<{locations:{id:string;name:string}[]}>(`/workspaces/${workspaceId}/devices/query`,{method:"POST",signal,body:JSON.stringify({mode:"ALL",accountId})});return scope.locations.filter(l=>l.name.toLowerCase().includes(search.toLowerCase())).slice(offset,offset+limit);};}
