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
  const generation=useRef(0);
  async function read(signal?:AbortSignal,forceRefresh=false) {
    const current=++generation.current;
    const known=prior.current?.panels ?? [];
    const merge=(batch:DashboardQueryBatch)=>{
      if(!stableRequest.widgetIds?.length || batch.panels.some((panel)=>panel.status==="no_access"))return batch;
      const returned=new Set(batch.panels.map((panel)=>panel.widgetId));
      return {...batch,panels:[...known.filter((panel)=>!returned.has(panel.widgetId)),...batch.panels]};
    };
    const batch=await queryDashboard(dashboardId,{...stableRequest,...(forceRefresh?{forceRefresh:true}:{})},signal,part=>{
      if(!signal?.aborted && current===generation.current)resource.progress(merge(part));
    });
    // Keep already read offscreen panels stable so changing visibility cannot
    // collapse their cards and trigger a read/layout/visibility feedback loop.
    return merge(batch);
  }
  const { load } = resource;
  const transport = useRef<AbortController|null>(null);
  const start = (force=false) => {
    transport.current?.abort();
    const controller=new AbortController();transport.current=controller;
    return load(()=>read(controller.signal,force));
  };
  useEffect(() => {
    if (!enabled) return;
    void start();
    return () => transport.current?.abort();
  }, [dashboardId, stableRequest, load, enabled]);
  const refresh = useCallback(async () => enabled ? start(true) : null,
    [dashboardId, stableRequest, load, enabled]);
  return { ...resource, refresh };
}
