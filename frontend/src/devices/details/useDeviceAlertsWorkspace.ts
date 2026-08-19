import { useState } from "react";
import { acknowledgeAlert, AlertResponse, listAlerts, resolveAlert } from "../../alerts/alertsApi";

export function useDeviceAlertsWorkspace(deviceId: string | undefined) {
  const [deviceAlerts, setDeviceAlerts] = useState<AlertResponse[]>([]);
  const [deviceAlertStatusFilter, setDeviceAlertStatusFilter] = useState("all");
  const [showAllDeviceAlerts, setShowAllDeviceAlerts] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const filteredDeviceAlerts = deviceAlerts
    .filter((alert) => {
      if (deviceAlertStatusFilter === "active") return alert.status !== "RESOLVED";
      return deviceAlertStatusFilter === "all" || alert.status === deviceAlertStatusFilter;
    })
    .sort((left, right) => severityRank(right.severity) - severityRank(left.severity) || new Date(right.createdAt).getTime() - new Date(left.createdAt).getTime());
  const visibleDeviceAlerts = showAllDeviceAlerts ? filteredDeviceAlerts : filteredDeviceAlerts.slice(0, 12);

  async function loadDeviceAlerts(currentDeviceId = deviceId) {
    if (!currentDeviceId) {
      return;
    }
    setDeviceAlerts((await listAlerts().catch(() => [])).filter((alert) => alert.deviceId === currentDeviceId));
  }

  async function updateDeviceAlert(alertId: string, action: "ack" | "resolve") {
    setError(null);
    try {
      if (action === "ack") {
        await acknowledgeAlert(alertId);
      } else {
        const reason = window.prompt("Resolution reason");
        if (reason === null) {
          return;
        }
        await resolveAlert(alertId, reason);
      }
      await loadDeviceAlerts(deviceId);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to update alert");
    }
  }

  return {
    deviceAlerts,
    setDeviceAlerts,
    deviceAlertStatusFilter,
    setDeviceAlertStatusFilter,
    showAllDeviceAlerts,
    setShowAllDeviceAlerts,
    filteredDeviceAlerts,
    visibleDeviceAlerts,
    error,
    setError,
    loadDeviceAlerts,
    updateDeviceAlert,
  };
}

function severityRank(value: string) {
  const normalized = value.toUpperCase();
  if (normalized === "CRITICAL") return 4;
  if (normalized === "HIGH") return 3;
  if (normalized === "MEDIUM" || normalized === "WARNING") return 2;
  if (normalized === "LOW") return 1;
  return 0;
}
