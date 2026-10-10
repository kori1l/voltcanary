import {apiRequest} from "../../../api/client";

export interface MeterCounterEvent {at:string;kind:"RESET"|"ROLLOVER"|"REPLACEMENT";rolloverWh:number|null}
export interface EnergyMeterDefinition {
  revision:number;physicalMeterId:string;parentPhysicalMeterId:string|null;sourceId:string;
  authoritativeMethod:"COUNTER_DELTA"|"POWER_INTEGRAL";maxGapSeconds:number;effectiveFrom:string;effectiveTo:string|null;
  counterEvents:MeterCounterEvent[];ancestorPhysicalMeterIds:string[];topologyComplete:boolean;
}
export interface EnergyMeterRequest extends Omit<EnergyMeterDefinition,"revision"|"effectiveTo"|"ancestorPhysicalMeterIds"|"topologyComplete"> {expectedRevision:number}
export function getEnergyMeter(deviceId:string,signal?:AbortSignal){return apiRequest<EnergyMeterDefinition[]>(`/devices/${encodeURIComponent(deviceId)}/energy-meter`,{signal});}
export function saveEnergyMeter(deviceId:string,body:EnergyMeterRequest){return apiRequest<EnergyMeterDefinition>(`/devices/${encodeURIComponent(deviceId)}/energy-meter`,{method:"PUT",body:JSON.stringify(body)});}
