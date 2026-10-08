import { useCallback, useEffect, useMemo } from "react";
import { useSectionResource } from "../components/useSectionResource";
import { queryDashboard, type DashboardQueryBatch, type DashboardQueryRequest } from "./dashboardQueriesApi";

/** Late responses, actor changes and runtime filters use the shared resource generation guard. */
export function useDashboardQuery(dashboardId: string, request: DashboardQueryRequest) {
  const encoded = JSON.stringify(request);
  const stableRequest = useMemo<DashboardQueryRequest>(() => JSON.parse(encoded), [encoded]);
  const resource = useSectionResource<DashboardQueryBatch>(`dashboard-query:${dashboardId}:${encoded}`);
  const { load } = resource;
  useEffect(() => {
    const controller = new AbortController();
    void load(() => queryDashboard(dashboardId, stableRequest, controller.signal));
    return () => controller.abort();
  }, [dashboardId, stableRequest, load]);
  const refresh = useCallback(() => load(() => queryDashboard(dashboardId, { ...stableRequest, forceRefresh: true })),
    [dashboardId, stableRequest, load]);
  return { ...resource, refresh };
}
