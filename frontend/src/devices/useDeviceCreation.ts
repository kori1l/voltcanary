import { locationLabel, splitTags, parseMetadata } from "./deviceInventoryPresentation";
import { Dispatch, SetStateAction, FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { createLocation, DeviceAccount, DeviceType, Location } from "./devicesApi";
import {
  createDemoDevice,
  createDeviceWithSource,
  defaultSourceId,
  DeviceSourceMode,
  DeviceSetupProgress,
  DeviceSetupResult,
  deviceSetupStep,
} from "./createDeviceFlow";
import { useDeviceSimulationPresets } from "./useDeviceSimulationPresets";
import { writeDeviceSourcePreference } from "./deviceSourcePreferences";
import { useActionConfirmation } from "../components/useActionConfirmation";
import { markWorkflowDraftSaved } from "../components/workflowDraftContext";
import { useDeviceSetupRecovery } from "./useDeviceSetupRecovery";
import { writeDeviceSetupCheckpoint, clearDeviceSetupCheckpoint } from "./deviceSetupCheckpoint";

const wizardSteps = ["Basics", "Location", "Telemetry", "Review"] as const;
const demoPresets = [
  {
    value: "NORMAL_OPERATION",
    label: "Normal operation",
    detail: "Stable telemetry for testing dashboards and charts.",
  },
  {
    value: "POWER_SPIKE",
    label: "Power spike",
    detail: "Short high-power event that should exercise alert rules.",
  },
  {
    value: "OFFLINE_WINDOW",
    label: "Offline window",
    detail: "Temporary telemetry gap for offline detection workflows.",
  },
  {
    value: "CONSUMPTION_GROWTH",
    label: "Consumption growth",
    detail: "Gradual load increase for trend and baseline rules.",
  },
] as const;

export interface DeviceCreationProps {
  ownerAccount: DeviceAccount | null;
  locations: Location[];
  deviceTypes: DeviceType[];
  referencesReady: boolean;
  setLocations: Dispatch<SetStateAction<Location[]>>;
  locationOptions: (query: {
    search: string;
    offset: number;
    limit: number;
    signal: AbortSignal;
  }) => Promise<{ id: string; name: string }[]>;
  onCreated: () => Promise<unknown>;
}

export function useDeviceCreation({
  ownerAccount,
  locations,
  deviceTypes,
  referencesReady,
  setLocations,
  onCreated,
}: DeviceCreationProps) {
  const navigate = useNavigate();
  const confirmation = useActionConfirmation();
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [completedSetup, setCompletedSetup] = useState<DeviceSetupResult | null>(null);
  const submittingSetup = useRef(false);
  const [selectedLocationId, setSelectedLocationId] = useState("");
  const [newLocationName, setNewLocationName] = useState("");
  const [newLocationParentId, setNewLocationParentId] = useState("");
  const [newLocationBuilding, setNewLocationBuilding] = useState("");
  const [newLocationFloor, setNewLocationFloor] = useState("");
  const [newLocationRoom, setNewLocationRoom] = useState("");
  const [newLocationZone, setNewLocationZone] = useState("");
  const [newLocationTags, setNewLocationTags] = useState("");
  const [name, setName] = useState("");
  const [type, setType] = useState("FRIDGE");
  const [manufacturer, setManufacturer] = useState("");
  const [model, setModel] = useState("");
  const [metadataText, setMetadataText] = useState("{}");
  const [sourceId, setSourceId] = useState("");
  const [sourceMode, setSourceMode] = useState<DeviceSourceMode>("API");
  const [demoProfile, setDemoProfile] = useState("FRIDGE");
  const [demoPreset, setDemoPreset] = useState("NORMAL_OPERATION");
  const [startDemoImmediately, setStartDemoImmediately] = useState(false);
  const [wizardStep, setWizardStep] = useState(0);
  const setupProgress = useRef<DeviceSetupProgress>({});
  const [, setSetupRevision] = useState(0);
  const createdLocation = useRef<string | null>(null);
  const [setupError, setSetupError] = useState<string | null>(null);
  const [locationMode, setLocationMode] = useState("existing");
  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const presetResource = useDeviceSimulationPresets(isCreateOpen && sourceMode === "SIMULATOR");
  const simulationPresets = (presetResource.data ?? []).filter((preset) =>
    preset.compatibleProfiles
      ? preset.compatibleProfiles.includes(demoProfile)
      : demoProfile !== "VEHICLE_GPS",
  );
  const locationById = useMemo(
    () => new Map(locations.map((location) => [location.id, locationLabel(location)])),
    [locations],
  );
  const typeByCode = useMemo(
    () => new Map(deviceTypes.map((deviceType) => [deviceType.code, deviceType.displayName])),
    [deviceTypes],
  );
  const demoPresetOptions =
    simulationPresets.length > 0
      ? simulationPresets.map((preset) => ({
          value: preset.id,
          label: preset.name,
          detail: preset.description,
        }))
      : [...demoPresets];
  const selectedPreset =
    demoPresetOptions.find((preset) => preset.value === demoPreset) ?? demoPresetOptions[0];
  const sourceIdPreview = sourceId.trim() || "Auto-generated on create";
  const canAdvanceWizard =
    wizardStep === 0
      ? Boolean(name.trim() && type)
      : wizardStep === 1
        ? Boolean(locationMode === "existing" ? selectedLocationId : newLocationName.trim())
        : wizardStep === 2
          ? Boolean(sourceMode)
          : true;

  useEffect(() => {
    if (deviceTypes.length && !deviceTypes.some((item) => item.code === type)) {
      setType(deviceTypes[0].code);
    }
  }, [deviceTypes, type]);
  function saveSetupCheckpoint() {
    const progress = setupProgress.current;
    writeDeviceSetupCheckpoint(ownerAccount, {
      deviceId: progress.device?.id,
      sourceId: progress.dataSource?.id,
      locationId:
        progress.device?.locationId ?? createdLocation.current ?? (selectedLocationId || undefined),
      configurationId: progress.simulationConfig?.id,
      runId: progress.simulationRun?.runId ?? undefined,
      pendingStage: progress.unknownStage,
      name: progress.device?.name ?? name,
      type: progress.device?.type ?? type,
      mode: sourceMode,
      externalId: progress.dataSource?.externalId ?? sourceId,
      profile: demoProfile,
      preset: demoPreset,
      startImmediately: startDemoImmediately,
    });
  }
  const recovery = useDeviceSetupRecovery(ownerAccount, setupProgress, (saved, progress) => {
    setName(progress.device?.name ?? saved.name);
    setType(progress.device?.type ?? saved.type);
    setSourceMode(saved.mode);
    setSourceId(progress.dataSource?.externalId ?? saved.externalId);
    setDemoProfile(saved.profile);
    setDemoPreset(saved.preset);
    setStartDemoImmediately(saved.startImmediately);
    setSelectedLocationId(progress.device?.locationId ?? saved.locationId ?? "");
    setLocationMode("existing");
    createdLocation.current = saved.locationId ?? null;
    setWizardStep(progress.device || progress.unknownStage ? 3 : 0);
  });
  const restoringSetup = recovery.restoring;
  const restoreFailed = recovery.failed;

  function openCreateWizard() {
    if (!referencesReady || restoringSetup) return;
    setWizardStep(setupProgress.current.device || setupProgress.current.unknownStage ? 3 : 0);
    setSetupError(null);
    if (!setupProgress.current.device && !setupProgress.current.unknownStage)
      setSourceId(defaultSourceId(sourceMode, name || "device"));
    setIsCreateOpen(true);
  }

  function resetCreateForm() {
    clearDeviceSetupCheckpoint(ownerAccount);
    setCompletedSetup(null);
    setupProgress.current = {};
    createdLocation.current = null;
    setSetupError(null);
    setName("");
    setManufacturer("");
    setModel("");
    setMetadataText("{}");
    setSourceId("");
    setSourceMode("API");
    setDemoProfile("FRIDGE");
    setDemoPreset("NORMAL_OPERATION");
    setStartDemoImmediately(false);
    setWizardStep(0);
    setNewLocationName("");
    setNewLocationParentId("");
    setNewLocationBuilding("");
    setNewLocationFloor("");
    setNewLocationRoom("");
    setNewLocationZone("");
    setNewLocationTags("");
  }

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (wizardStep < wizardSteps.length - 1) {
      if (canAdvanceWizard) {
        setWizardStep((current) => Math.min(current + 1, wizardSteps.length - 1));
      }
      return;
    }
    if (
      submittingSetup.current ||
      isSubmitting ||
      setupProgress.current.unknownStage ||
      restoringSetup ||
      restoreFailed
    )
      return;
    submittingSetup.current = true;
    setSetupError(null);
    setIsSubmitting(true);
    try {
      const externalId = sourceId.trim() || defaultSourceId(sourceMode, name);
      setSourceId(externalId);
      setupProgress.current.checkpoint = () => {
        writeDeviceSetupCheckpoint(ownerAccount, {
          deviceId: setupProgress.current.device?.id,
          sourceId: setupProgress.current.dataSource?.id,
          locationId:
            setupProgress.current.device?.locationId ??
            createdLocation.current ??
            (selectedLocationId || undefined),
          configurationId: setupProgress.current.simulationConfig?.id,
          runId: setupProgress.current.simulationRun?.runId ?? undefined,
          pendingStage: setupProgress.current.unknownStage,
          name: setupProgress.current.device?.name ?? name,
          type,
          mode: sourceMode,
          externalId,
          profile: demoProfile,
          preset: demoPreset,
          startImmediately: startDemoImmediately,
        });
      };
      if (
        sourceMode === "SIMULATOR" &&
        !setupProgress.current.sourceToken &&
        !setupProgress.current.simulationConfig?.hasSourceToken
      ) {
        const answer = await confirmation.confirm({
          title: "Configure simulator credential",
          object: `${name} · ${externalId}`,
          impact:
            "Issues or replaces the source ingestion token, granting telemetry ingestion to this source. Existing senders using an older credential must be updated. The server stores the simulator credential; browser setup recovery does not store its plaintext.",
        });
        if (answer === null) return;
      }
      let locationId = selectedLocationId;
      if (locationMode === "new" && createdLocation.current) locationId = createdLocation.current;
      else if (locationMode === "new" && newLocationName.trim()) {
        const location = await deviceSetupStep("location", setupProgress.current, () =>
          createLocation({
            parentId: newLocationParentId || null,
            name: newLocationName.trim(),
            description: null,
            building: newLocationBuilding.trim() || null,
            floor: newLocationFloor.trim() || null,
            room: newLocationRoom.trim() || null,
            zone: newLocationZone.trim() || null,
            tags: splitTags(newLocationTags),
          }),
        );
        locationId = location.id;
        createdLocation.current = location.id;
        setupProgress.current.checkpoint?.();
        setLocations((current) => [...current, location]);
      }
      if (!locationId && !setupProgress.current.device) {
        throw new Error("Choose or create a location");
      }
      const metadata = parseMetadata(metadataText);
      const deviceRequest = {
        locationId,
        name,
        type,
        manufacturer: manufacturer || "Unknown",
        model: model || "Unknown",
        metadata,
      };
      const result =
        sourceMode === "SIMULATOR"
          ? await createDemoDevice(
              deviceRequest,
              {
                mode: "SIMULATOR",
                externalId,
                profile: demoProfile,
                preset: demoPreset,
                startImmediately: startDemoImmediately,
              },
              setupProgress.current,
            )
          : await createDeviceWithSource(
              deviceRequest,
              {
                mode: sourceMode,
                externalId,
              },
              setupProgress.current,
            );
      writeDeviceSourcePreference(result.device.id, result.dataSource.externalId);
      setCompletedSetup(result);
      markWorkflowDraftSaved("create-device-form");
      void onCreated();
    } catch (requestError) {
      setSetupError(
        requestError instanceof Error ? requestError.message : "Failed to create device",
      );
    } finally {
      submittingSetup.current = false;
      setIsSubmitting(false);
    }
  }

  return {
    confirmation,
    navigate,
    isSubmitting,
    completedSetup,
    selectedLocationId,
    setSelectedLocationId,
    newLocationName,
    setNewLocationName,
    newLocationParentId,
    setNewLocationParentId,
    newLocationBuilding,
    setNewLocationBuilding,
    newLocationFloor,
    setNewLocationFloor,
    newLocationRoom,
    setNewLocationRoom,
    newLocationZone,
    setNewLocationZone,
    newLocationTags,
    setNewLocationTags,
    name,
    setName,
    type,
    setType,
    manufacturer,
    setManufacturer,
    model,
    setModel,
    metadataText,
    setMetadataText,
    sourceId,
    setSourceId,
    sourceMode,
    setSourceMode,
    demoProfile,
    setDemoProfile,
    demoPreset,
    setDemoPreset,
    startDemoImmediately,
    setStartDemoImmediately,
    wizardStep,
    setWizardStep,
    setupProgress,
    setSetupRevision,
    createdLocation,
    setupError,
    setSetupError,
    locationMode,
    setLocationMode,
    isCreateOpen,
    setIsCreateOpen,
    presetResource,
    locationById,
    typeByCode,
    demoPresetOptions,
    selectedPreset,
    sourceIdPreview,
    canAdvanceWizard,
    saveSetupCheckpoint,
    recovery,
    restoringSetup,
    restoreFailed,
    openCreateWizard,
    resetCreateForm,
    onSubmit,
    wizardSteps,
  };
}
