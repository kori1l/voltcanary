import {listDevices,listLocations} from "../../../devices/devicesApi";
interface Request {search:string;offset:number;limit:number;signal:AbortSignal}
export async function dashboardDeviceOptions({search,offset,limit,signal}:Request){return (await listDevices({search,offset,limit},signal)).map((device)=>({id:device.id,name:device.name,status:device.status}));}
export async function refrigerationDeviceOptions({search,offset,limit,signal}:Request){return (await listDevices({search,offset,limit,type:"FRIDGE"},signal)).map((device)=>({id:device.id,name:device.name,status:device.status}));}
export async function dashboardLocationOptions({search,offset,limit,signal}:Request){return (await listLocations({search,offset,limit},signal)).map((location)=>({id:location.id,name:location.name}));}
