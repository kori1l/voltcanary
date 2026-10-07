import { useMemo, useState } from "react";
import { decommissionDevice, Device, disableDevice, enableDevice, getDevice, listLocations, Location } from "../devicesApi";
import { locationLabel } from "./deviceDetailsFormatting";
import { useSectionResource } from "../../components/useSectionResource";
import { useActionConfirmation } from "../../components/useActionConfirmation";

export function useDeviceDetailsData(deviceId: string | undefined) {
  const deviceSection = useSectionResource<Device>(deviceId);
  const locationSection = useSectionResource<Location[]>();
  const device = deviceSection.data;
  const setDevice = deviceSection.setData;
  const locations = locationSection.data ?? [];
  const confirmation = useActionConfirmation();
  const [statusBusy, setStatusBusy] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const locationById = useMemo(() => new Map(locations.map((location) => [location.id, locationLabel(location)])), [locations]);
  const locationName = device?.locationId ? locationById.get(device.locationId) ?? "Location unavailable" : "No location";

  async function loadDeviceContext(currentDeviceId = deviceId) {
    if (!currentDeviceId) {
      return null;
    }
    const [deviceResponse] = await Promise.all([
      deviceSection.load(() => getDevice(currentDeviceId)),
      locationSection.load(listLocations),
    ]);
    return deviceResponse;
  }

  async function changeDeviceStatus(action: "enable" | "disable" | "decommission") {
    if (!deviceId) return;
    if (statusBusy || await confirmation.confirm({ title: `${action[0].toUpperCase()}${action.slice(1)} device`, object: device?.name ?? deviceId, impact: action === "decommission" ? "Retire this device from active operations. Its history remains available." : `Change registration status to ${action === "enable" ? "ACTIVE" : "DISABLED"}. Telemetry freshness is reported separately.` }) === null) return;
    setStatusBusy(true);
    setError(null);
    try {
      const nextDevice =
        action === "enable"
          ? await enableDevice(deviceId)
          : action === "disable"
            ? await disableDevice(deviceId)
            : await decommissionDevice(deviceId);
      setDevice(nextDevice);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to update device status");
    } finally {
      setStatusBusy(false);
    }
  }

  return {
    confirmationDialog: confirmation.dialog,
    statusBusy,
    device,
    setDevice,
    locations,
    locationName,
    isLoading,
    setIsLoading,
    resource: deviceSection,
    locationResource: locationSection,
    actionError: error,
    error: error ?? deviceSection.error,
    setError,
    loadDeviceContext,
    changeDeviceStatus,
  };
}
