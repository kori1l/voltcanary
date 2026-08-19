import { useMemo, useState } from "react";
import { decommissionDevice, Device, disableDevice, enableDevice, getDevice, listLocations, Location } from "../devicesApi";
import { locationLabel } from "./deviceDetailsFormatting";

export function useDeviceDetailsData(deviceId: string | undefined) {
  const [device, setDevice] = useState<Device | null>(null);
  const [locations, setLocations] = useState<Location[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const locationById = useMemo(() => new Map(locations.map((location) => [location.id, locationLabel(location)])), [locations]);
  const locationName = device ? locationById.get(device.locationId ?? "") ?? "No location" : "No location";

  async function loadDeviceContext(currentDeviceId = deviceId) {
    if (!currentDeviceId) {
      return null;
    }
    const [deviceResponse, locationsResponse] = await Promise.all([
      getDevice(currentDeviceId),
      listLocations().catch(() => []),
    ]);
    setDevice(deviceResponse);
    setLocations(locationsResponse);
    return deviceResponse;
  }

  async function changeDeviceStatus(action: "enable" | "disable" | "decommission") {
    if (!deviceId) return;
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
    }
  }

  return {
    device,
    setDevice,
    locations,
    locationName,
    isLoading,
    setIsLoading,
    error,
    setError,
    loadDeviceContext,
    changeDeviceStatus,
  };
}
