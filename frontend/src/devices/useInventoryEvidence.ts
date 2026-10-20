import { getDeviceRisk } from "../analytics/analyticsApi";
import { getLatestTelemetry } from "../telemetry/telemetryApi";
import { useEntityResources } from "../components/useEntityResources";
import type { Device } from "./devicesApi";
import { useEffect, useRef } from "react";
import { ReadLimiter } from "../components/ReadLimiter";
import { ApiError } from "../api/client";

export function useInventoryEvidence(scope: string) {
  const limiter = useRef(new ReadLimiter(6));
  const request = useRef<{ scope: string; controller: AbortController } | null>(null);
  useEffect(() => () => request.current?.controller.abort(), [scope]);
  function signal() { if (!request.current || request.current.scope !== scope || request.current.controller.signal.aborted) { request.current?.controller.abort(); request.current = { scope, controller: new AbortController() }; } return request.current.controller.signal; }
  const telemetry = useEntityResources<Awaited<ReturnType<typeof getLatestTelemetry>>>(scope);
  const risk = useEntityResources<Awaited<ReturnType<typeof getDeviceRisk>> | null>(scope);
  const reloadTelemetry = (id: string) => { const current = signal(); return telemetry.load(id, () => limiter.current.run(() => getLatestTelemetry(id, current), current), (data) => !Object.keys(data.readings).length); };
  const reloadRisk = async (id: string) => {
    const current = signal();let absent=false;
    const value=await risk.load(id, () => limiter.current.run(async()=>{
      try{return await getDeviceRisk(id,current);}catch(error){
        if(error instanceof ApiError && error.status===404){absent=true;return null;}
        throw error;
      }
    },current),data=>data===null);
    return !current.aborted && (value!==null || absent);
  };
  async function load(devices: Device[]) {
    request.current?.controller.abort();
    const results = await Promise.all(devices.flatMap(({ id }) => [reloadTelemetry(id), reloadRisk(id)]));
    return results.every((result) => result !== null && result !== false);
  }
  return { telemetry: telemetry.items, risk: risk.items, reloadTelemetry, reloadRisk, load,
    latestByDevice: Object.fromEntries(Object.entries(telemetry.items).map(([id, resource]) => [id, resource.data])),
    riskByDevice: Object.fromEntries(Object.entries(risk.items).map(([id, resource]) => [id, resource.data])),
  };
}
