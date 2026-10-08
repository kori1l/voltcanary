import { useEffect, useMemo, useRef } from "react";
import { listAlerts, type AlertResponse } from "../../alerts/alertsApi";
import { listDevices, listLocations, type Device, type Location } from "../../devices/devicesApi";
import { getLatestTelemetry, type LatestTelemetry } from "../../telemetry/telemetryApi";
import { useSectionResource } from "../../components/useSectionResource";
import { ApiError } from "../../api/client";

export interface FleetSignal { device: Device; telemetry: LatestTelemetry | null; error: string | null }
export const FRESH_SIGNAL_MS = 5 * 60 * 1000;
export function isFreshSignal(telemetry: LatestTelemetry | null, now = Date.now()) {
  if (!telemetry || !Object.keys(telemetry.readings).length) return false;
  const age = now - Date.parse(telemetry.timestamp);
  return Number.isFinite(age) && age >= -60_000 && age <= FRESH_SIGNAL_MS;
}

export function useFleetDashboard() {
  const inventoryGeneration = useRef(0);
  const inventory = useSectionResource<Device[]>();
  const alerts = useSectionResource<AlertResponse[]>();
  const locations = useSectionResource<Location[]>();
  const signals = useSectionResource<FleetSignal[]>();
  const reloadInventory = async () => {
    const generation = ++inventoryGeneration.current;
    signals.waitForDependency();
    const devices = await inventory.load(() => listDevices({ limit: 200 }), (data) => !data.length);
    if (generation !== inventoryGeneration.current) return null;
    if (devices) await reloadSignals(devices);
    else signals.waitForDependency("Inventory unavailable. Retry inventory to verify signals.");
    return devices;
  };
  const reloadAlerts = () => alerts.load(() => listAlerts({ limit: 100 }), (data) => !data.length);
  const reloadLocations = () => locations.load(listLocations);
  async function reloadSignals(devices = inventory.data) {
    if (!devices) return;
    await signals.load(async () => Promise.all(devices.slice(0, 6).map(async (device): Promise<FleetSignal> => {
      try { return { device, telemetry: await getLatestTelemetry(device.id), error: null }; }
      catch (error) { return { device, telemetry: error instanceof ApiError && [401, 403].includes(error.status) ? null : signals.data?.find((signal) => signal.device.id === device.id)?.telemetry ?? null, error: error instanceof Error ? error.message : "Telemetry unavailable" }; }
    })), (data) => !data.length);
  }
  async function reload() {
    const results = await Promise.allSettled([reloadInventory(), reloadAlerts(), reloadLocations()]);
    return results.every((result) => result.status === "fulfilled" && result.value !== null);
  }
  useEffect(() => { void reload(); }, []);
  const attention = useMemo(() => {
    const priority: Record<string, number> = { CRITICAL: 4, HIGH: 3, WARNING: 2, INFO: 1 };
    return [...(alerts.data ?? [])].filter((alert) => alert.status !== "RESOLVED").sort((a, b) => (priority[b.severity] ?? 0) - (priority[a.severity] ?? 0) || Date.parse(b.createdAt) - Date.parse(a.createdAt));
  }, [alerts.data]);
  return { inventory, alerts, locations, signals, attention, reload, reloadInventory, reloadAlerts, reloadSignals, reloadLocations };
}
