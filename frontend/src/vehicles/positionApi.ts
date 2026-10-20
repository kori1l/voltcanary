import {apiRequest} from "../api/client";

export interface TransportContext {
  routeId:string|null;routeShortName:string|null;direction:number|null;destination:string|null;nextStopId:string|null;
  scheduleDeviationSeconds:number|null;doorsOpen:boolean|null;accelerationMs2:number|null;scheduledStart:string|null;locationSource:string|null;
}
export interface PositionPoint {fields?:Record<string,{value:number|string|boolean;type:string;label:string;unit:string;display:boolean}>;
  recordedAt:string; receivedAt:string; latitude:number; longitude:number;
  speedKmh:number|null; headingDeg:number|null; quality:string[];
  ignition?:number|null; fuelPercent?:number|null; odometerKm?:number|null;
  transport?:TransportContext|null;
}
export interface LatestPosition {
  deviceId:string; sourceId:string; asOf:string; freshness:"FRESH"|"STALE"|"NO_DATA";
  position:PositionPoint|null;
}
export interface PositionHistory {
  deviceId:string; sourceId:string; from:string; to:string; points:PositionPoint[];
  returnedCount:number; truncated:boolean;
}
export interface PositionEvidence {latest:LatestPosition; history:PositionHistory}

export async function getPositionEvidence(device:string,source:string,from:string,asOf:string,signal:AbortSignal):Promise<PositionEvidence> {
  const params=new URLSearchParams({sourceId:source});
  const [latest,history]=await Promise.all([
    apiRequest<LatestPosition>(`/telemetry/devices/${device}/positions/latest?${params}&asOf=${encodeURIComponent(asOf)}`,{signal}),
    apiRequest<PositionHistory>(`/telemetry/devices/${device}/positions?${params}&from=${encodeURIComponent(from)}&to=${encodeURIComponent(asOf)}`,{signal})
  ]);
  return {latest,history};
}
