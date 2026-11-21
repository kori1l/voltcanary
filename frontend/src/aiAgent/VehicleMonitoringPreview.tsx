import type {AgentAction} from "./aiAgentApi";
import type {MonitoringReview} from "../vehicles/monitoringApi";
import {MonitoringReviewPreview} from "../vehicles/MonitoringReviewPreview";
import type {MonitoringSettings} from "../vehicles/monitoringModel";
function settings(value:unknown):value is MonitoringSettings {
  if(!value||typeof value!=="object")return false;
  const item=value as MonitoringSettings;
  return Number.isFinite(item.speedLimitKmh)&&Number.isInteger(item.idleMinutes)&&Array.isArray(item.zones)&&item.zones.length<=50
    &&item.zones.every(zone=>typeof zone.id==="string"&&typeof zone.name==="string"&&[zone.latitude,zone.longitude,zone.radiusMetres].every(Number.isFinite));
}
export function monitoringReview(action:AgentAction):MonitoringReview|null {
  const value=action.parameters.review as MonitoringReview|undefined;
  return value&&typeof value.id==="string"&&value.deviceId===action.parameters.deviceId&&typeof value.digest==="string"
    &&Number.isInteger(value.baseRevision)&&typeof value.expiresAt==="string"&&settings(value.before)&&settings(value.after)?value:null;
}
export function VehicleMonitoringPreview({action}:{action:AgentAction}) {
  const review=monitoringReview(action);
  return review?<MonitoringReviewPreview review={review}/>:<p role="alert">Monitoring preview unavailable. Reload the saved review.</p>;
}
