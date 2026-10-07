import { useState } from "react";
import {
  cancelMaintenanceWindow,
  createMaintenanceWindow,
  finishMaintenanceWindowNow,
  listMaintenanceWindows,
  MaintenanceWindow,
} from "../devicesApi";
import { toDateTimeLocalValue } from "./deviceDetailsFormatting";
import { useActionConfirmation } from "../../components/useActionConfirmation";

export function useDeviceMaintenanceActions(deviceId: string | undefined) {
  const [maintenanceWindows, setMaintenanceWindows] = useState<MaintenanceWindow[]>([]);
  const [maintenanceTitle, setMaintenanceTitle] = useState("Planned maintenance");
  const [maintenanceDescription, setMaintenanceDescription] = useState("");
  const [maintenanceStartAt, setMaintenanceStartAt] = useState(toDateTimeLocalValue(new Date()));
  const [maintenanceEndAt, setMaintenanceEndAt] = useState(toDateTimeLocalValue(new Date(Date.now() + 60 * 60 * 1000)));
  const [maintenanceSuppressAlerts, setMaintenanceSuppressAlerts] = useState(true);
  const [maintenanceExcludeAnalytics, setMaintenanceExcludeAnalytics] = useState(true);
  const [maintenanceExcludeMl, setMaintenanceExcludeMl] = useState(true);
  const [maintenanceMessage, setMaintenanceMessage] = useState<string | null>(null);
  const [isMaintenanceBusy, setIsMaintenanceBusy] = useState(false);
  const confirmation = useActionConfirmation();

  const sortedMaintenanceWindows = [...maintenanceWindows].sort((left, right) => new Date(right.startAt).getTime() - new Date(left.startAt).getTime());
  const activeMaintenanceWindows = maintenanceWindows.filter((window) => window.status === "ACTIVE");
  const upcomingMaintenanceWindows = maintenanceWindows.filter((window) => window.status === "SCHEDULED");
  const suppressingMaintenanceWindow = activeMaintenanceWindows.find((window) => window.suppressAlerts || window.excludeFromAnalytics || window.excludeFromMl) ?? null;

  async function refreshMaintenanceWindows(currentDeviceId = deviceId) {
    if (!currentDeviceId) return;
    try { setMaintenanceWindows(await listMaintenanceWindows(currentDeviceId)); }
    catch (error) { setMaintenanceMessage(error instanceof Error ? error.message : "Maintenance unavailable. Retained windows may be stale."); }
  }

  async function submitMaintenanceWindow() {
    if (!deviceId || isMaintenanceBusy) return;
    setIsMaintenanceBusy(true);
    setMaintenanceMessage(null);
    try {
      const start = new Date(maintenanceStartAt);
      const end = new Date(maintenanceEndAt);
      if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime()) || end <= start) {
        setMaintenanceMessage("Maintenance end must be after start.");
        return;
      }
      await createMaintenanceWindow(deviceId, {
        title: maintenanceTitle.trim() || "Planned maintenance",
        description: maintenanceDescription.trim() || null,
        startAt: start.toISOString(),
        endAt: end.toISOString(),
        suppressAlerts: maintenanceSuppressAlerts,
        excludeFromAnalytics: maintenanceExcludeAnalytics,
        excludeFromMl: maintenanceExcludeMl,
      });
      setMaintenanceMessage("Maintenance window created.");
      setMaintenanceTitle("Planned maintenance");
      setMaintenanceDescription("");
      setMaintenanceStartAt(toDateTimeLocalValue(new Date()));
      setMaintenanceEndAt(toDateTimeLocalValue(new Date(Date.now() + 60 * 60 * 1000)));
      await refreshMaintenanceWindows(deviceId);
    } catch (requestError) {
      setMaintenanceMessage(requestError instanceof Error ? requestError.message : "Failed to create maintenance window");
    } finally {
      setIsMaintenanceBusy(false);
    }
  }

  async function cancelWindow(windowId: string) {
    if (!deviceId) return;
    const reason = await confirmation.confirm({ title: "Cancel maintenance window", object: maintenanceWindows.find((window) => window.id === windowId)?.title ?? windowId, impact: "Ends the window's selected alert, analytics and ML exclusions. Records the cancellation reason.", reasonRequired: true });
    if (reason === null) return;
    setIsMaintenanceBusy(true);
    setMaintenanceMessage(null);
    try {
      await cancelMaintenanceWindow(deviceId, windowId, reason);
      await refreshMaintenanceWindows(deviceId);
    } catch (requestError) {
      setMaintenanceMessage(requestError instanceof Error ? requestError.message : "Failed to cancel maintenance window");
    } finally {
      setIsMaintenanceBusy(false);
    }
  }

  async function finishWindowNow(windowId: string) {
    if (!deviceId) return;
    setIsMaintenanceBusy(true);
    setMaintenanceMessage(null);
    try {
      await finishMaintenanceWindowNow(deviceId, windowId);
      await refreshMaintenanceWindows(deviceId);
    } catch (requestError) {
      setMaintenanceMessage(requestError instanceof Error ? requestError.message : "Failed to finish maintenance window");
    } finally {
      setIsMaintenanceBusy(false);
    }
  }

  return {
    confirmationDialog: confirmation.dialog,
    maintenanceWindows,
    setMaintenanceWindows,
    maintenanceTitle,
    setMaintenanceTitle,
    maintenanceDescription,
    setMaintenanceDescription,
    maintenanceStartAt,
    setMaintenanceStartAt,
    maintenanceEndAt,
    setMaintenanceEndAt,
    maintenanceSuppressAlerts,
    setMaintenanceSuppressAlerts,
    maintenanceExcludeAnalytics,
    setMaintenanceExcludeAnalytics,
    maintenanceExcludeMl,
    setMaintenanceExcludeMl,
    maintenanceMessage,
    isMaintenanceBusy,
    sortedMaintenanceWindows,
    activeMaintenanceWindows,
    upcomingMaintenanceWindows,
    suppressingMaintenanceWindow,
    refreshMaintenanceWindows,
    submitMaintenanceWindow,
    cancelWindow,
    finishWindowNow,
  };
}
