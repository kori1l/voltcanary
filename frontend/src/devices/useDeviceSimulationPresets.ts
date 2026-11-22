import { useCallback, useEffect } from "react";
import { listSimulationPresets, type SimulationPreset } from "../simulation/simulationApi";
import { useSectionResource } from "../components/useSectionResource";

/** Simulator reference reads belong to simulator onboarding, not fleet browsing. */
export function useDeviceSimulationPresets(enabled: boolean) {
  const resource = useSectionResource<SimulationPreset[]>("device-simulation-presets");
  const load = resource.load;
  const retry = useCallback(() => load(listSimulationPresets, (items) => !items.length), [load]);
  useEffect(() => {
    if (enabled) void retry();
  }, [enabled, retry]);
  return { ...resource, retry };
}
