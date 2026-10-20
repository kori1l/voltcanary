import {apiRequest} from "../api/client";
import type {LatestPosition,PositionPoint} from "./positionApi";
import type {MonitoringSettings} from "./monitoringModel";
export interface VehicleTarget {deviceId:string;sourceId:string}
export const fleetPositions=(targets:VehicleTarget[],signal:AbortSignal)=>apiRequest<LatestPosition[]>("/telemetry/vehicles/positions",{method:"POST",body:JSON.stringify({targets,asOf:new Date().toISOString()}),signal});
export const readMonitoring=(device:string,signal:AbortSignal)=>apiRequest<MonitoringSettings>(`/telemetry/devices/${device}/vehicle-monitoring`,{signal});
export const saveMonitoring=(device:string,body:MonitoringSettings)=>apiRequest<MonitoringSettings>(`/telemetry/devices/${device}/vehicle-monitoring`,{method:"PUT",body:JSON.stringify(body)});
interface RecordingPage {points:PositionPoint[];nextCursor:string|null;hasMore:boolean}
export async function recordedPositions(target:VehicleTarget,from:string,to:string,signal:AbortSignal,onProgress:(count:number)=>void) {
  const points:PositionPoint[]=[];let cursor:string|undefined;
  for(let page=0;page<10;page++) {
    const params=new URLSearchParams({sourceId:target.sourceId,from,to,limit:"2000",...(cursor?{cursor}:{})});
    const result=await apiRequest<RecordingPage>(`/telemetry/devices/${target.deviceId}/positions/recordings?${params}`,{signal});
    points.push(...result.points);onProgress(points.length);
    if(!result.hasMore)return {points,truncated:false};
    if(!result.nextCursor||result.nextCursor===cursor)throw new Error("Invalid recording cursor; reload the selected range.");
    cursor=result.nextCursor;
  }
  return {points,truncated:true};
}
