import { useEffect, useRef, useState, type MutableRefObject } from "react";
import { getSimulationConfig, getSimulationStatus } from "../simulation/simulationApi";
import { getDevice, listDataSources, type DeviceAccount } from "./devicesApi";
import { readDeviceSetupCheckpoint, type DeviceSetupCheckpoint } from "./deviceSetupCheckpoint";
import type { DeviceSetupProgress } from "./createDeviceFlow";

export function useDeviceSetupRecovery(account: DeviceAccount | null, progress: MutableRefObject<DeviceSetupProgress>, onRestored: (saved: DeviceSetupCheckpoint, restored: DeviceSetupProgress) => void) {
  const callback = useRef(onRestored); callback.current = onRestored;
  const [restoring, setRestoring] = useState(false);
  const [failed, setFailed] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    const saved = readDeviceSetupCheckpoint(account);
    if (!saved) return;
    let active = true;
    const controller = new AbortController();
    setRestoring(true); setFailed(false); setError(null);
    void (async () => {
      const restored: DeviceSetupProgress = { unknownStage: saved.pendingStage };
      if (saved.deviceId) {
        restored.device = await getDevice(saved.deviceId, controller.signal);
        if (restored.device.id !== saved.deviceId || typeof restored.device.name !== "string") throw new Error("Invalid restored device");
        if (saved.sourceId) {
          restored.dataSource = (await listDataSources(saved.deviceId)).find((source) => source.id === saved.sourceId && source.deviceId === saved.deviceId);
          if (!restored.dataSource) restored.unknownStage = "source";
        }
        if (saved.configurationId) { const config = await getSimulationConfig(saved.deviceId); if (config.id === saved.configurationId && config.deviceId === saved.deviceId && config.dataSourceId === saved.sourceId) restored.simulationConfig = config; else restored.unknownStage = "configuration"; }
        if (saved.runId) { const run = await getSimulationStatus(saved.deviceId, controller.signal); if (run.runId === saved.runId) restored.simulationRun = run; else restored.unknownStage = "run"; }
      }
      if (!active) return;
      progress.current = restored;
      callback.current(saved, restored);
    })().catch(() => { if (active) { setFailed(true); setError("Saved setup could not be authorized or loaded. Retry these reads before continuing; the device will not be recreated."); } }).finally(() => { if (active) setRestoring(false); });
    return () => { active = false; controller.abort(); };
  }, [account?.id, attempt]);
  return { restoring, failed, error, retry: () => setAttempt((value) => value + 1) };
}
