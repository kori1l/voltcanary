import { type AlertFilters, type AlertResponse, type AlertSummaryResponse, type AlertRule, type NotificationChannelResponse, listAlerts, getAlertSummary, listAlertRules, listNotificationChannels } from "../../alerts/alertsApi";
import { type Device, type Location, listDevices, listLocations } from "../../devices/devicesApi";
import { useSectionResource } from "../../components/useSectionResource";

export function useAlertConsoleData(filters: AlertFilters) {
  const scope = JSON.stringify(filters);
  const alerts = useSectionResource<AlertResponse[]>(scope);
  const summary = useSectionResource<AlertSummaryResponse>(scope);
  const rules = useSectionResource<AlertRule[]>();
  const channels = useSectionResource<NotificationChannelResponse[]>();
  const devices = useSectionResource<Device[]>();
  const locations = useSectionResource<Location[]>();
  async function load() {
    await Promise.allSettled([
      alerts.load(() => listAlerts(filters), (data) => !data.length),
      summary.load(() => getAlertSummary(filters)),
      rules.load(listAlertRules, (data) => !data.length),
      channels.load(listNotificationChannels, (data) => !data.length),
      devices.load(listDevices, (data) => !data.length),
      locations.load(listLocations, (data) => !data.length),
    ]);
  }
  return { alerts, summary, rules, channels, devices, locations, load };
}
