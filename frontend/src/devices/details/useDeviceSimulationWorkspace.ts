import { useEffect, useMemo, useState } from "react";
import { readDeviceSourcePreference, writeDeviceSourcePreference } from "../deviceSourcePreferences";
import { createDataSourceToken, updateDataSource } from "../devicesApi";
import {
  getSimulationConfig,
  getSimulationRuns,
  getSimulationStatus,
  getSimulationTemplate,
  listSimulationPresets,
  SimulationPreset,
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
import { useActionConfirmation } from "../../components/useActionConfirmation";

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
  const [simulationPresets, setSimulationPresets] = useState<SimulationPreset[]>([]);
  const [simulationPreset, setSimulationPreset] = useState("NORMAL_OPERATION");
  const confirmation = useActionConfirmation();
  const hasUnsavedSimulation = Boolean(simulationConfig && (simulationYaml !== simulationConfig.yamlConfig || simulationProfile !== simulationConfig.profile || simulationProtocol !== simulationConfig.protocol));
  useEffect(() => { let live = true; listSimulationPresets().then((presets) => { if (live) setSimulationPresets(presets); }).catch(() => undefined); return () => { live = false; }; }, []);

  const simulationSettings = useMemo(() => readSimulationSettings(simulationYaml), [simulationYaml]);
  const simulationSourceId = simulationSettings.sourceId || readDeviceSourcePreference(deviceId) || "plug-001";

  async function loadSimulation(currentDeviceId = deviceId) {
    if (!currentDeviceId) {
      return;
    }
    try {
      const [configResponse, statusResponse, runsResponse] = await Promise.all([
        getSimulationConfig(currentDeviceId),
        getSimulationStatus(currentDeviceId),
        getSimulationRuns(currentDeviceId),
      ]);
      setSimulationConfig(configResponse);
      setSimulationYaml(configResponse.yamlConfig);
      setSimulationProfile(configResponse.profile);
      setSimulationProtocol(configResponse.protocol);
      setSimulationStatus(statusResponse);
      setSimulationRuns(runsResponse);
    } catch (error) {
      setSimulationMessage(error instanceof Error ? error.message : "Simulator unavailable. Retained configuration may be stale.");
    }
  }

  async function refreshSimulationRuntime(currentDeviceId = deviceId) {
    if (!currentDeviceId) {
      return;
    }
    try {
      const [statusResponse, runsResponse] = await Promise.all([
        getSimulationStatus(currentDeviceId),
        getSimulationRuns(currentDeviceId),
      ]);
      setSimulationStatus(statusResponse);
      setSimulationRuns(runsResponse);
    } catch {
      setSimulationMessage("Runtime refresh failed. Retained status and runs may be stale.");
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
    if (simulationStatus?.status !== "RUNNING" && await confirmation.confirm({ title: "Start simulator", object: simulationConfig.name, impact: `${hasUnsavedSimulation ? "Saves the current draft, then starts" : "Starts"} telemetry generation. HTTP start rotates the source credential; external senders sharing this source must be updated.` }) === null) return;
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
    if (!deviceId || !simulationConfig || isSimulationBusy) return;
    if (hasUnsavedSimulation && await confirmation.confirm({ title: "Replace simulator draft", object: simulationConfig.name, impact: "Replace unsaved YAML with the server template. Review and save the new draft before starting." }) === null) return;
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

  async function applyPreset() {
    if (!deviceId || !simulationConfig) return;
    if (hasUnsavedSimulation && await confirmation.confirm({ title: "Replace simulator draft", object: simulationConfig.name, impact: "Replaces unsaved YAML with the selected preset. Saved configuration and current run are unchanged until you save or start." }) === null) return;
    setIsSimulationBusy(true);
    try { const template = await getSimulationTemplate(simulationProfile, simulationSourceId, simulationPreset); setSimulationYaml(template.yamlConfig); setSimulationMessage("Preset loaded as a draft. Validate and save before using it."); }
    catch (error) { setSimulationMessage(error instanceof Error ? error.message : "Preset unavailable"); }
    finally { setIsSimulationBusy(false); }
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
    hasUnsavedSimulation,
    simulationPresets,
    simulationPreset,
    setSimulationPreset,
    applyPreset,
    confirmationDialog: confirmation.dialog,
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
