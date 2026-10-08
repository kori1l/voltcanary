import { useEffect, useRef } from "react";
import { type AlertFilters, type AlertResponse, type AlertPageResponse, type AlertSummaryResponse, type AlertRule, type NotificationChannelResponse, listAlertPage, getAlertSummary, listAlertRules, listNotificationChannels } from "../../alerts/alertsApi";
import { type Device, type Location, listDevices, listLocations } from "../../devices/devicesApi";
import { useSectionResource } from "../../components/useSectionResource";

export function useAlertConsoleData(filters: AlertFilters, page = 0) {
  const scope = JSON.stringify(filters);
  const pageResource = useSectionResource<AlertPageResponse>(`${scope}:${page}`);
  const alerts = { ...pageResource, data: pageResource.data?.content ?? null, update: (mutate: (data: AlertResponse[]) => AlertResponse[]) => pageResource.update((current) => ({ ...current, content: mutate(current.content) })) };
  const summary = useSectionResource<AlertSummaryResponse>(scope);
  const summaryScope = useRef<string | null>(null);
  const request = useRef<AbortController | null>(null);
  const rules = useSectionResource<AlertRule[]>();
  const channels = useSectionResource<NotificationChannelResponse[]>();
  const devices = useSectionResource<Device[]>();
  const locations = useSectionResource<Location[]>();
  useEffect(() => {
    void Promise.allSettled([rules.load(listAlertRules, (data) => !data.length), channels.load(listNotificationChannels, (data) => !data.length), devices.load(listDevices, (data) => !data.length), locations.load(listLocations, (data) => !data.length)]);
    return () => request.current?.abort();
  }, []);
  async function load(refreshSummary = true) {
    request.current?.abort();
    const controller = new AbortController(); request.current = controller;
    const jobs: Array<Promise<unknown>> = [pageResource.load(async () => {
      const result = await listAlertPage(filters, page, 100, controller.signal);
      if (!Array.isArray(result.content) || typeof result.hasNext !== "boolean" || !Number.isFinite(result.totalElements)) throw new Error("Incident page unavailable: invalid response");
      return result;
    }, (data) => !data.content.length)];
    if (refreshSummary || summaryScope.current !== scope) { summaryScope.current = scope; jobs.push(summary.load(() => getAlertSummary(filters))); }
    const results = await Promise.allSettled(jobs);
    return results.every((result) => result.status === "fulfilled" && result.value !== null);
  }
  return { alerts, pagination: pageResource.data, summary, rules, channels, devices, locations, load };
}
