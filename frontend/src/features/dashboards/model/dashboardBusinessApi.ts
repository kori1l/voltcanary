import {apiRequest} from "../../../api/client";
export interface EnergyTariff {tariffId:string|null;version:number;accountId?:string;workspaceId?:string;currency:string;timezone:string;ratePerKWh:string;effectiveFrom:string;effectiveTo:string;taxesIncluded:boolean;reason:string;archived:boolean}
export const listEnergyTariffs=(accountId?:string,workspaceId?:string,signal?:AbortSignal)=>apiRequest<EnergyTariff[]>(`/energy-tariffs?${new URLSearchParams({...accountId?{accountId}:{},...workspaceId?{workspaceId}:{}})}`,{signal});
export const saveEnergyTariff=(tariff:EnergyTariff)=>apiRequest<EnergyTariff>("/energy-tariffs",{method:"POST",body:JSON.stringify({tariff,reviewed:true})});
export interface OperationalFact {eventId:string|null;version:number;deviceId:string;sourceId:string;sourceEventId:string;kind:string;completeness:string;startAt:string;endAt:string|null;reason:string;evidence:string|null}
export const saveOperationalFact=(fact:OperationalFact,accountId?:string,workspaceId?:string)=>apiRequest<OperationalFact>("/operational-events",{method:"POST",body:JSON.stringify({fact,accountId,workspaceId,reviewed:true})});
export interface OperatingSchedule {scheduleId:string|null;version:number;deviceId:string;timezone:string;effectiveFrom:string;effectiveTo:string;weekSlots:{dayOfWeek:number;from:string;to:string}[];holidays:string[];reason:string}
export const saveOperatingSchedule=(schedule:OperatingSchedule,accountId?:string,workspaceId?:string)=>apiRequest<OperatingSchedule>("/operating-schedules",{method:"POST",body:JSON.stringify({schedule,accountId,workspaceId,reviewed:true})});
export const listOperatingSchedules=(deviceId:string,signal?:AbortSignal,accountId?:string,workspaceId?:string)=>apiRequest<OperatingSchedule[]>(`/operating-schedules?${new URLSearchParams({deviceId,...accountId?{accountId}:{},...workspaceId?{workspaceId}:{}})}`,{signal});

export const archiveEnergyTariff=(id:string,accountId?:string,workspaceId?:string)=>apiRequest<void>(`/energy-tariffs/${id}/archive?${new URLSearchParams({...accountId?{accountId}:{},...workspaceId?{workspaceId}:{}})}`,{method:"POST"});
export const listOperationalFacts=(deviceId:string,from:string,to:string,accountId?:string,workspaceId?:string)=>apiRequest<OperationalFact[]>(`/operational-events?${new URLSearchParams({deviceId,from,to,...accountId?{accountId}:{},...workspaceId?{workspaceId}:{}})}`);

export const listWorkspaceSources=(workspaceId:string,accountId:string,deviceId:string)=>apiRequest<import("../../../devices/devicesApi").DataSource[]>(`/workspaces/${workspaceId}/devices/${deviceId}/data-sources?${new URLSearchParams({accountId})}`);

export const workspaceTelemetryMetrics=(workspaceId:string,accountId:string,deviceId:string,from:string,to:string,signal?:AbortSignal)=>apiRequest<import("../../../telemetry/telemetryApi").TelemetryMetricMetadata[]>(`/telemetry/workspaces/${workspaceId}/devices/${deviceId}/metrics?${new URLSearchParams({accountId,from,to})}`,{signal});
