import { useEffect, useMemo } from "react";
import {
  readDeviceSourcePreference,
  writeDeviceSourcePreference,
} from "../deviceSourcePreferences";
import { createDataSourceToken, updateDataSource } from "../devicesApi";
import {
  getSimulationConfig,
  getSimulationRuns,
  getSimulationStatus,
  getSimulationTemplate,
  resetSimulationTemplate,
  saveSimulationConfig,
  SimulationConfig,
  SimulationRun,
  startSimulation,
  stopSimulation,
  validateSimulationConfig,
} from "../../simulation/simulationApi";
import { useDeviceSimulationPresets } from "../useDeviceSimulationPresets";
import { DeviceDetailsTab } from "./DeviceTabs";
import { readSimulationSettings, SimulationSettings, updateSimulationYaml } from "./simulationYaml";
import { useActionConfirmation } from "../../components/useActionConfirmation";
import { useSectionResource } from "../../components/useSectionResource";
import { useSimulationDraft } from "./useSimulationDraft";

interface SimulationRuntime {
  status: SimulationRun;
  runs: SimulationRun[];
}

function validationMessage(result: Awaited<ReturnType<typeof validateSimulationConfig>>) {
  return result.valid
    ? "YAML is valid"
    : result.errors.map((item) => `${item.path}: ${item.message}`).join("; ");
}

export function useDeviceSimulationWorkspace({
  deviceId,
  activeTab,
  onSourceTokenGenerated,
}: {
  deviceId: string | undefined;
  activeTab: DeviceDetailsTab;
  onSourceTokenGenerated: (token: string) => void;
}) {
  const config = useSectionResource<SimulationConfig>(deviceId);
  const runtime = useSectionResource<SimulationRuntime>(deviceId);
  const templates = useSectionResource<{ yamlConfig: string }>(deviceId);
  const validation =
    useSectionResource<Awaited<ReturnType<typeof validateSimulationConfig>>>(deviceId);
  const draft = useSimulationDraft(deviceId);
  const { yaml, profile, protocol, preset, message } = draft.value;
  const simulationConfig = config.data;
  const simulationStatus = runtime.data?.status ?? null;
  const presetResource = useDeviceSimulationPresets(activeTab === "simulation");
  const simulationPresets = (presetResource.data ?? []).filter((item) =>
    item.compatibleProfiles ? item.compatibleProfiles.includes(profile) : profile !== "VEHICLE_GPS",
  );
  const confirmation = useActionConfirmation();
  const simulationSettings = useMemo(() => readSimulationSettings(yaml), [yaml]);
  const simulationSourceId =
    simulationSettings.sourceId || readDeviceSourcePreference(deviceId) || "plug-001";
  const hasUnsavedSimulation = Boolean(
    simulationConfig &&
    (yaml !== simulationConfig.yamlConfig ||
      profile !== simulationConfig.profile ||
      protocol !== simulationConfig.protocol),
  );

  function setSimulationYaml(value: string | ((previous: string) => string)) {
    draft.edit((previous) => ({
      ...previous,
      yaml: typeof value === "function" ? value(previous.yaml) : value,
      validation: {
        valid: false,
        message: "Draft changed. Validating YAML…",
      },
    }));
  }

  async function loadSimulation(currentDeviceId = deviceId) {
    if (!currentDeviceId || currentDeviceId !== deviceId) return;
    const current = draft.unchanged();
    const inScope = draft.ticket();
    const loaded = await config.load(() => getSimulationConfig(currentDeviceId));
    if (loaded && current())
      draft.edit({
        yaml: loaded.yamlConfig,
        profile: loaded.profile,
        protocol: loaded.protocol,
        preset: loaded.profile === "VEHICLE_GPS" ? "CITY_LOOP" : "NORMAL_OPERATION",
      });
    if (inScope()) await refreshSimulationRuntime(currentDeviceId);
  }

  async function refreshSimulationRuntime(currentDeviceId = deviceId) {
    if (!currentDeviceId || currentDeviceId !== deviceId) return;
    await runtime.load(async () => {
      const [status, runs] = await Promise.all([
        getSimulationStatus(currentDeviceId),
        getSimulationRuns(currentDeviceId),
      ]);
      return { status, runs };
    });
  }

  useEffect(() => {
    if (!deviceId || activeTab !== "simulation") return;
    if (simulationConfig) void refreshSimulationRuntime();
    else void loadSimulation();
  }, [deviceId, activeTab, config.load]);

  useEffect(() => {
    if (!deviceId || simulationStatus?.status !== "RUNNING") return;
    const interval = window.setInterval(() => void refreshSimulationRuntime(), 5000);
    return () => window.clearInterval(interval);
  }, [deviceId, simulationStatus?.status, runtime.load]);

  useEffect(() => {
    if (!deviceId || !simulationConfig || !yaml) return;
    const current = draft.unchanged();
    const timer = window.setTimeout(async () => {
      const result = await validation.load(() => validateSimulationConfig(deviceId, yaml));
      if (result && current())
        draft.update({
          validation: {
            valid: result.valid,
            message: validationMessage(result),
          },
        });
    }, 350);
    return () => window.clearTimeout(timer);
  }, [deviceId, simulationConfig?.id, yaml, validation.load]);

  async function validateYaml() {
    if (!deviceId) return;
    const unchanged = draft.unchanged();
    draft.update({ validation: { valid: false, message: "Validating YAML…" } });
    await draft.run("YAML validation", async (current) => {
      const result = await validateSimulationConfig(deviceId, yaml);
      if (current() && unchanged())
        draft.update({
          validation: {
            valid: result.valid,
            message: validationMessage(result),
          },
          message: validationMessage(result),
        });
    });
  }

  async function saveCurrentSimulationConfig(rotateToken: boolean, current: () => boolean) {
    if (!deviceId || !simulationConfig) throw new Error("Simulation config is not loaded");
    const yamlConfig = updateSimulationYaml(
      yaml.replace(/protocol:\s*(HTTP|MQTT)/, `protocol: ${protocol}`),
      "sourceId",
      simulationSourceId,
    );
    await updateDataSource(deviceId, simulationConfig.dataSourceId, {
      sourceType: "SIMULATOR",
      protocol: protocol === "MQTT" ? "MQTT" : "REST",
      externalId: simulationSourceId,
    });
    if (!current()) return null;
    const token = rotateToken
      ? await createDataSourceToken(deviceId, simulationConfig.dataSourceId)
      : null;
    if (!current()) return null;
    if (token) onSourceTokenGenerated(token.token);
    writeDeviceSourcePreference(deviceId, simulationSourceId);
    return saveSimulationConfig(deviceId, {
      dataSourceId: simulationConfig.dataSourceId,
      name: simulationConfig.name,
      profile,
      protocol,
      yamlConfig,
      ...(token ? { sourceToken: token.token } : {}),
    });
  }

  async function saveYaml() {
    if (!deviceId || !simulationConfig) return;
    const unchanged = draft.unchanged();
    await draft.run("Save configuration", async (current) => {
      const saved = await saveCurrentSimulationConfig(false, current);
      if (!saved || !current()) return;
      config.setData(saved);
      if (unchanged())
        draft.edit({
          yaml: saved.yamlConfig,
          profile: saved.profile,
          protocol: saved.protocol,
        });
      draft.update({ message: "Config saved" });
    });
  }

  function updateSimulationField(field: keyof SimulationSettings, value: string | boolean) {
    setSimulationYaml((previous) => updateSimulationYaml(previous, field, value));
  }

  async function loadTemplate(loader: () => Promise<{ yamlConfig: string }>, success: string) {
    const current = draft.unchanged();
    const template = await templates.load(loader);
    if (template && current())
      draft.edit({
        yaml: template.yamlConfig,
        message: success,
        validation: {
          valid: false,
          message: "Draft changed. Validating YAML…",
        },
      });
  }

  function changeSimulationProfile(nextProfile: string) {
    const nextPreset = nextProfile === "VEHICLE_GPS" ? "CITY_LOOP" : "NORMAL_OPERATION";
    draft.edit({
      profile: nextProfile,
      preset: nextPreset,
      validation: { valid: false, message: "Loading profile template…" },
    });
    void loadTemplate(
      () => getSimulationTemplate(nextProfile, simulationSourceId, nextPreset),
      "Profile template loaded as an unsaved draft. Save to apply.",
    );
  }

  function changeSimulationProtocol(nextProtocol: string) {
    draft.edit((previous) => ({
      ...previous,
      protocol: nextProtocol,
      yaml: updateSimulationYaml(previous.yaml, "protocol", nextProtocol),
      validation: {
        valid: false,
        message: "Draft changed. Validating YAML…",
      },
    }));
  }

  async function toggleSimulation() {
    if (!deviceId || !simulationConfig) return;
    const unchanged = draft.unchanged();
    await draft.run("Simulation action", async (current) => {
      if (simulationStatus?.status !== "RUNNING") {
        const confirmed = await confirmation.confirm({
          title: "Start simulator",
          object: simulationConfig.name,
          impact: `${hasUnsavedSimulation ? "Saves the current draft, then starts" : "Starts"} telemetry generation. HTTP start rotates the source credential; external senders sharing this source must be updated.`,
        });
        if (confirmed === null || !current() || !unchanged()) return;
        const saved = await saveCurrentSimulationConfig(protocol === "HTTP", current);
        if (!saved || !current()) return;
        config.setData(saved);
        await startSimulation(deviceId);
      } else await stopSimulation(deviceId);
      if (current()) await loadSimulation();
    });
  }

  async function resetYamlTemplate() {
    if (!deviceId || !simulationConfig) return;
    const unchanged = draft.unchanged();
    await draft.run("Reset template", async (current) => {
      if (hasUnsavedSimulation) {
        const confirmed = await confirmation.confirm({
          title: "Replace simulator draft",
          object: simulationConfig.name,
          impact:
            "Replace unsaved YAML with the server template. Review and save the new draft before starting.",
        });
        if (confirmed === null || !current() || !unchanged()) return;
      }
      await loadTemplate(async () => {
        const template = await resetSimulationTemplate(deviceId, profile, simulationSourceId);
        return {
          yamlConfig: template.yamlConfig.replace(
            /protocol:\s*(HTTP|MQTT)/,
            `protocol: ${protocol}`,
          ),
        };
      }, "Template loaded");
    });
  }

  async function applyPreset() {
    if (!deviceId || !simulationConfig) return;
    const unchanged = draft.unchanged();
    await draft.run("Load preset", async (current) => {
      if (hasUnsavedSimulation) {
        const confirmed = await confirmation.confirm({
          title: "Replace simulator draft",
          object: simulationConfig.name,
          impact:
            "Replaces unsaved YAML with the selected preset. Review and save the new draft before starting.",
        });
        if (confirmed === null || !current() || !unchanged()) return;
      }
      await loadTemplate(
        () => getSimulationTemplate(profile, simulationSourceId, preset),
        "Preset loaded as a draft. Validate and save before using it.",
      );
    });
  }

  async function syncSourceToken(dataSourceId: string, token: string) {
    if (!deviceId || simulationConfig?.dataSourceId !== dataSourceId) return;
    await draft.run(
      "Save source credential",
      async (current) => {
        const latest = await getSimulationConfig(deviceId);
        if (!current() || latest.dataSourceId !== dataSourceId) return;
        const saved = await saveSimulationConfig(deviceId, {
          dataSourceId,
          name: latest.name,
          profile: latest.profile,
          protocol: latest.protocol,
          yamlConfig: latest.yamlConfig,
          sourceToken: token,
        });
        if (current()) config.setData(saved);
      },
      { waitForActive: true, propagateFailure: true },
    );
  }

  return {
    presetResource,
    hasUnsavedSimulation,
    simulationPresets,
    simulationPreset: preset,
    setSimulationPreset: (value: string) => draft.edit({ preset: value }),
    applyPreset,
    confirmationDialog: confirmation.dialog,
    simulationConfig,
    setSimulationConfig: config.setData,
    simulationStatus,
    simulationRuns: runtime.data?.runs ?? [],
    simulationYaml: yaml,
    setSimulationYaml,
    simulationProfile: profile,
    simulationProtocol: protocol,
    simulationMessage:
      message ?? templates.error ?? validation.error ?? config.error ?? runtime.error,
    simulationValidation: validation.error
      ? { valid: false, message: validation.error }
      : draft.value.validation,
    isSimulationBusy: draft.busy || templates.refreshing,
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
