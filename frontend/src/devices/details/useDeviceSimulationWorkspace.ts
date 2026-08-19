import { useEffect, useMemo, useState } from "react";
import { createDataSourceToken, updateDataSource } from "../devicesApi";
import {
  getSimulationConfig,
  getSimulationRuns,
  getSimulationStatus,
  resetSimulationTemplate,
  saveSimulationConfig,
  SimulationConfig,
  SimulationRun,
  startSimulation,
  stopSimulation,
  validateSimulationConfig,
} from "../../simulation/simulationApi";
import { DeviceDetailsTab } from "./DeviceTabs";
import { readSimulationSettings, SimulationSettings, updateSimulationYaml } from "./simulationYaml";

export function useDeviceSimulationWorkspace({
  deviceId,
  activeTab,
  onSourceTokenGenerated,
}: {
  deviceId: string | undefined;
  activeTab: DeviceDetailsTab;
  onSourceTokenGenerated: (token: string) => void;
}) {
  const [simulationConfig, setSimulationConfig] = useState<SimulationConfig | null>(null);
  const [simulationStatus, setSimulationStatus] = useState<SimulationRun | null>(null);
  const [simulationRuns, setSimulationRuns] = useState<SimulationRun[]>([]);
  const [simulationYaml, setSimulationYaml] = useState("");
  const [simulationProfile, setSimulationProfile] = useState("FRIDGE");
  const [simulationProtocol, setSimulationProtocol] = useState("HTTP");
  const [simulationMessage, setSimulationMessage] = useState<string | null>(null);
  const [simulationValidation, setSimulationValidation] = useState<{ valid: boolean; message: string }>({ valid: true, message: "YAML is valid" });
  const [isSimulationBusy, setIsSimulationBusy] = useState(false);

  const simulationSettings = useMemo(() => readSimulationSettings(simulationYaml), [simulationYaml]);
  const simulationSourceId = simulationSettings.sourceId || readDeviceSourcePreference(deviceId) || "plug-001";

  async function loadSimulation(currentDeviceId = deviceId) {
    if (!currentDeviceId) {
      return;
    }
    try {
      const [configResponse, statusResponse, runsResponse] = await Promise.all([
        getSimulationConfig(currentDeviceId),
        getSimulationStatus(currentDeviceId).catch(() => null),
        getSimulationRuns(currentDeviceId).catch(() => []),
      ]);
      setSimulationConfig(configResponse);
      setSimulationYaml(configResponse.yamlConfig);
      setSimulationProfile(configResponse.profile);
      setSimulationProtocol(configResponse.protocol);
      setSimulationStatus(statusResponse);
      setSimulationRuns(runsResponse);
    } catch {
      setSimulationConfig(null);
      setSimulationYaml("");
      setSimulationStatus(null);
      setSimulationRuns([]);
      setSimulationProfile("FRIDGE");
      setSimulationProtocol("HTTP");
    }
  }

  async function refreshSimulationRuntime(currentDeviceId = deviceId) {
    if (!currentDeviceId) {
      return;
    }
    try {
      const [statusResponse, runsResponse] = await Promise.all([
        getSimulationStatus(currentDeviceId).catch(() => null),
        getSimulationRuns(currentDeviceId).catch(() => []),
      ]);
      setSimulationStatus(statusResponse);
      setSimulationRuns(runsResponse);
    } catch {
      setSimulationStatus(null);
      setSimulationRuns([]);
    }
  }

  useEffect(() => {
    if (!deviceId || !simulationConfig || !simulationYaml) {
      return;
    }
    let cancelled = false;
    const timeoutId = window.setTimeout(() => {
      validateSimulationConfig(deviceId, simulationYaml)
        .then((result) => {
          if (cancelled) {
            return;
          }
          setSimulationValidation({
            valid: result.valid,
            message: result.valid ? "YAML is valid" : result.errors.map((item) => `${item.path}: ${item.message}`).join("; "),
          });
        })
        .catch((requestError) => {
          if (cancelled) {
            return;
          }
          setSimulationValidation({
            valid: false,
            message: requestError instanceof Error ? requestError.message : "YAML validation failed",
          });
        });
    }, 350);
    return () => {
      cancelled = true;
      window.clearTimeout(timeoutId);
    };
  }, [deviceId, simulationConfig, simulationYaml]);

  useEffect(() => {
    if (!deviceId || activeTab !== "simulation") {
      return;
    }
    if (simulationConfig) {
      void refreshSimulationRuntime(deviceId);
    } else {
      void loadSimulation(deviceId);
    }
  }, [deviceId, activeTab, simulationConfig?.id]);

  useEffect(() => {
    if (!deviceId || simulationStatus?.status !== "RUNNING") {
      return;
    }
    const intervalId = window.setInterval(() => {
      void refreshSimulationRuntime(deviceId);
    }, 5000);
    return () => window.clearInterval(intervalId);
  }, [deviceId, simulationStatus?.status]);

  async function validateYaml() {
    if (!deviceId) return;
    setIsSimulationBusy(true);
    setSimulationMessage(null);
    try {
      const result = await validateSimulationConfig(deviceId, simulationYaml);
      const message = result.valid ? "YAML is valid" : result.errors.map((item) => `${item.path}: ${item.message}`).join("; ");
      setSimulationValidation({ valid: result.valid, message });
      setSimulationMessage(message);
    } finally {
      setIsSimulationBusy(false);
    }
  }

  async function saveYaml() {
    if (!deviceId || !simulationConfig) return;
    setIsSimulationBusy(true);
    setSimulationMessage(null);
    try {
      const saved = await saveCurrentSimulationConfig(false);
      setSimulationConfig(saved);
      setSimulationProfile(saved.profile);
      setSimulationProtocol(saved.protocol);
      setSimulationMessage("Config saved");
    } finally {
      setIsSimulationBusy(false);
    }
  }

  function updateSimulationField(field: keyof SimulationSettings, value: string | boolean) {
    setSimulationYaml((current) => updateSimulationYaml(current, field, value));
  }

  function changeSimulationProfile(profile: string) {
    setSimulationProfile(profile);
    setSimulationYaml((current) => updateSimulationYaml(current, "profile", profile));
  }

  function changeSimulationProtocol(protocol: string) {
    setSimulationProtocol(protocol);
    setSimulationYaml((current) => updateSimulationYaml(current, "protocol", protocol));
  }

  async function toggleSimulation() {
    if (!deviceId || !simulationConfig) return;
    setIsSimulationBusy(true);
    setSimulationMessage(null);
    try {
      if (simulationStatus?.status === "RUNNING") {
        setSimulationStatus(await stopSimulation(deviceId));
      } else {
        const saved = await saveCurrentSimulationConfig(simulationProtocol === "HTTP");
        setSimulationConfig(saved);
        setSimulationStatus(await startSimulation(deviceId));
      }
      await loadSimulation(deviceId);
    } catch (requestError) {
      setSimulationMessage(requestError instanceof Error ? requestError.message : "Simulation action failed");
    } finally {
      setIsSimulationBusy(false);
    }
  }

  async function resetYamlTemplate() {
    if (!deviceId || !simulationConfig) return;
    setIsSimulationBusy(true);
    setSimulationMessage(null);
    try {
      const template = await resetSimulationTemplate(deviceId, simulationProfile, simulationSourceId);
      setSimulationYaml(template.yamlConfig.replace(/protocol:\s*(HTTP|MQTT)/, `protocol: ${simulationProtocol}`));
      setSimulationMessage("Template loaded");
    } finally {
      setIsSimulationBusy(false);
    }
  }

  async function saveCurrentSimulationConfig(rotateToken: boolean) {
    if (!deviceId || !simulationConfig) {
      throw new Error("Simulation config is not loaded");
    }

    const yamlConfig = updateSimulationYaml(
      simulationYaml.replace(/protocol:\s*(HTTP|MQTT)/, `protocol: ${simulationProtocol}`),
      "sourceId",
      simulationSourceId,
    );
    await updateDataSource(deviceId, simulationConfig.dataSourceId, {
      sourceType: "SIMULATOR",
      protocol: simulationProtocol === "MQTT" ? "MQTT" : "REST",
      externalId: simulationSourceId,
    }).catch(() => undefined);
    const token = rotateToken ? await createDataSourceToken(deviceId, simulationConfig.dataSourceId) : null;
    if (token) {
      onSourceTokenGenerated(token.token);
    }
    writeDeviceSourcePreference(deviceId, simulationSourceId);
    return saveSimulationConfig(deviceId, {
      dataSourceId: simulationConfig.dataSourceId,
      name: simulationConfig.name,
      profile: simulationProfile,
      protocol: simulationProtocol,
      yamlConfig,
      ...(token ? { sourceToken: token.token } : {}),
    });
  }

  async function syncSourceToken(dataSourceId: string, token: string) {
    if (!deviceId || simulationConfig?.dataSourceId !== dataSourceId) {
      return;
    }
    const saved = await saveSimulationConfig(deviceId, {
      dataSourceId: simulationConfig.dataSourceId,
      name: simulationConfig.name,
      profile: simulationProfile,
      protocol: simulationProtocol,
      yamlConfig: simulationYaml.replace(/protocol:\s*(HTTP|MQTT)/, `protocol: ${simulationProtocol}`),
      sourceToken: token,
    });
    setSimulationConfig(saved);
  }

  return {
    simulationConfig,
    setSimulationConfig,
    simulationStatus,
    simulationRuns,
    simulationYaml,
    setSimulationYaml,
    simulationProfile,
    simulationProtocol,
    simulationMessage,
    simulationValidation,
    isSimulationBusy,
    simulationSettings,
    simulationSourceId,
    loadSimulation,
    refreshSimulationRuntime,
    validateYaml,
    saveYaml,
    updateSimulationField,
    changeSimulationProfile,
    changeSimulationProtocol,
    toggleSimulation,
    resetYamlTemplate,
    syncSourceToken,
  };
}

function deviceSourcePreferenceKey(deviceId: string) {
  return `voltcanary.deviceSource.${deviceId}`;
}

function readDeviceSourcePreference(deviceId: string | undefined) {
  if (!deviceId) {
    return null;
  }
  return window.localStorage.getItem(deviceSourcePreferenceKey(deviceId));
}

function writeDeviceSourcePreference(deviceId: string, value: string) {
  window.localStorage.setItem(deviceSourcePreferenceKey(deviceId), value);
}
