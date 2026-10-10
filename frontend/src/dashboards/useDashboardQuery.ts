import { useCallback, useEffect, useMemo, useRef } from "react";
import { useSectionResource } from "../components/useSectionResource";
import { queryDashboard, type DashboardQueryBatch, type DashboardQueryRequest } from "./dashboardQueriesApi";

/** Late responses, actor changes and runtime filters use the shared resource generation guard. */
export function useDashboardQuery(dashboardId: string, request: DashboardQueryRequest, enabled = true, screenId = "") {
  const encoded = JSON.stringify(request);
  const stableRequest = useMemo<DashboardQueryRequest>(() => JSON.parse(encoded), [encoded]);
  const scope = `dashboard-query:${dashboardId}:${screenId}:${JSON.stringify({...request,widgetIds:undefined})}`;
  const resource = useSectionResource<DashboardQueryBatch>(scope);
  const prior = useRef(resource.data);prior.current=resource.data;
  async function read(signal?:AbortSignal,forceRefresh=false) {
    const batch=await queryDashboard(dashboardId,{...stableRequest,...(forceRefresh?{forceRefresh:true}:{})},signal);
    // Keep already read offscreen panels stable so changing visibility cannot
    // collapse their cards and trigger a read/layout/visibility feedback loop.
    const known=prior.current?.panels ?? [];
    if(!stableRequest.widgetIds?.length || batch.panels.some((panel)=>panel.status==="no_access"))return batch;
    const returned=new Set(batch.panels.map((panel)=>panel.widgetId));
    return {...batch,panels:[...known.filter((panel)=>!returned.has(panel.widgetId)),...batch.panels]};
  }
  const { load } = resource;
  useEffect(() => {
    if (!enabled) return;
    const controller = new AbortController();
    void load(() => read(controller.signal));
    return () => controller.abort();
  }, [dashboardId, stableRequest, load, enabled]);
  const refresh = useCallback(async () => enabled ? load(() => read(undefined,true)) : null,
    [dashboardId, stableRequest, load, enabled]);
  return { ...resource, refresh };
}
