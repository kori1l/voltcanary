import { DeviceIcon, getDeviceTone, formatEnum, formatNumber, formatRelativeTime, locationLabel, splitTags, parseMetadata, downloadText } from "../devices/deviceInventoryPresentation";
import { DeviceInventoryTable } from "../devices/DeviceInventoryTable";
import { DeviceWizardBasics } from "../devices/DeviceWizardBasics";
import { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import {
  createLocation,
  Device,
  DeviceAccount,
  DeviceType,
  getCurrentDeviceAccount,
  exportDevicesCsv,
  importDevicesCsv,
  listDeviceTypes,
  listDevices,
  listLocations,
  Location,
} from "../devices/devicesApi";
import { createDemoDevice, createDeviceWithSource, defaultSourceId, DeviceSourceMode, DeviceSetupProgress, DeviceSetupResult, deviceSetupStep } from "../devices/createDeviceFlow";
import { listSimulationPresets, SimulationPreset } from "../simulation/simulationApi";
import { Dialog, DialogClose } from "../components/Dialog";
import { EntitySelector } from "../components/EntitySelector";
import { CsvImportPreview, inspectImportCsv } from "../devices/CsvImportPreview";
import { TimeDisplay } from "../components/TimeDisplay";
import { useInventoryEvidence } from "../devices/useInventoryEvidence";
import { useSectionResource } from "../components/useSectionResource";
import { SectionFeedback } from "../components/SectionFeedback";
import { ReadRefresh } from "../components/ReadRefresh";
import { useEvidenceClock } from "../components/useEvidenceClock";
import { useConsoleQuery } from "../app/useConsoleQuery";
import { writeDeviceSourcePreference } from "../devices/deviceSourcePreferences";
import { useDebouncedValue } from "../components/useDebouncedValue";
import { PageNavigation } from "../components/PageNavigation";
import { useActionConfirmation } from "../components/useActionConfirmation";
import { markWorkflowDraftSaved } from "../components/workflowDraftContext";
import { DeviceActivationSummary, activationRoute } from "../devices/DeviceActivationSummary";
import { useDeviceSetupRecovery } from "../devices/useDeviceSetupRecovery";
import { DeviceSetupReconciliation } from "../devices/DeviceSetupReconciliation";
import { writeDeviceSetupCheckpoint, clearDeviceSetupCheckpoint } from "../devices/deviceSetupCheckpoint";
import { useTaskEvidence } from "../aiAgent/taskEvidence";

const wizardSteps = ["Basics", "Location", "Telemetry", "Review"] as const;
const demoPresets = [
  { value: "NORMAL_OPERATION", label: "Normal operation", detail: "Stable telemetry for testing dashboards and charts." },
  { value: "POWER_SPIKE", label: "Power spike", detail: "Short high-power event that should exercise alert rules." },
  { value: "OFFLINE_WINDOW", label: "Offline window", detail: "Temporary telemetry gap for offline detection workflows." },
  { value: "CONSUMPTION_GROWTH", label: "Consumption growth", detail: "Gradual load increase for trend and baseline rules." },
] as const;

export function DevicesPage() {
  const navigate = useNavigate();
  const confirmation = useActionConfirmation();
  const [ownerAccount, setOwnerAccount] = useState<DeviceAccount | null>(null);
  const [completedSetup, setCompletedSetup] = useState<DeviceSetupResult | null>(null);
  const submittingSetup = useRef(false);
  const query = useConsoleQuery();
  const [locations, setLocations] = useState<Location[]>([]);
  const [deviceTypes, setDeviceTypes] = useState<DeviceType[]>([]);
  const [simulationPresets, setSimulationPresets] = useState<SimulationPreset[]>([]);
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
  const [search, setSearch] = query.state<string>("q", "");
  const [locationFilter, setLocationFilter] = query.state<string>("locationId", "all");
  const [statusFilter, setStatusFilter] = query.state<string>("status", "all");
  const [offsetValue] = query.state<string>("offset", "0");
  const searchApplied = useDebouncedValue(search.trim());
  const lastOffset = useRef(Number(offsetValue));
  const applyingSearch = searchApplied !== search.trim();
  if (!applyingSearch) lastOffset.current = Number(offsetValue);
  const offset = applyingSearch ? lastOffset.current : Number(offsetValue);
  const inventoryScope = JSON.stringify([searchApplied, locationFilter, statusFilter, offset]);
  const inventory = useSectionResource<Device[]>(inventoryScope);
  const devices = (inventory.data ?? []).slice(0, 100);
  const hasMore = (inventory.data?.length ?? 0) > 100;
  const [referenceError, setReferenceError] = useState<string | null>(null);
  const [referencesReady, setReferencesReady] = useState(false);
  const inventoryRequest = useRef<AbortController | null>(null);
  const referencesGeneration = useRef(0);
  const setDevices = inventory.setData;
  const evidence = useInventoryEvidence(inventoryScope);
  const { latestByDevice, riskByDevice } = evidence;
  useEvidenceClock();
  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const [isCsvOpen, setIsCsvOpen] = useState(false);
  const [inventoryView, setInventoryView] = query.state<string>("view", "cards");
  const [csvText, setCsvText] = useState("name,type,locationId,manufacturer,model\n");
  const [csvMessage, setCsvMessage] = useState<string | null>(null);
  const isLoading = inventory.status === "loading" || inventory.refreshing;
  const hasInventory = inventory.data !== null;
  const loadSequence = useRef(0);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const locationById = useMemo(
    () => new Map(locations.map((location) => [location.id, locationLabel(location)])),
    [locations],
  );
  const typeByCode = useMemo(() => new Map(deviceTypes.map((deviceType) => [deviceType.code, deviceType.displayName])), [deviceTypes]);
  useTaskEvidence({ names: { location: locations.find((location) => location.id === locationFilter)?.name } });
  const roomGroups = useMemo(() => {
    const orderedLocationIds = locations.map((location) => location.id);
    const groups = new Map<string, Device[]>();
    devices.forEach((device) => {
      const groupId = device.locationId || "unassigned";
      groups.set(groupId, [...(groups.get(groupId) ?? []), device]);
    });

    return [...groups.entries()]
      .map(([locationId, roomDevices]) => ({
        locationId,
        name: locationId === "unassigned" ? "No location" : locationById.get(locationId) ?? "Unknown location",
        devices: roomDevices.sort((left, right) => left.name.localeCompare(right.name)),
        order: locationId === "unassigned" ? Number.MAX_SAFE_INTEGER : orderedLocationIds.indexOf(locationId),
      }))
      .sort((left, right) => left.order - right.order || left.name.localeCompare(right.name));
  }, [devices, locationById, locations]);
  const demoPresetOptions = simulationPresets.length > 0
    ? simulationPresets.map((preset) => ({ value: preset.id, label: preset.name, detail: preset.description }))
    : [...demoPresets];
  const selectedPreset = demoPresetOptions.find((preset) => preset.value === demoPreset) ?? demoPresetOptions[0];
  const sourceIdPreview = sourceId.trim() || "Auto-generated on create";
  const canAdvanceWizard =
    wizardStep === 0
      ? Boolean(name.trim() && type)
      : wizardStep === 1
        ? Boolean(locationMode === "existing" ? selectedLocationId : newLocationName.trim())
        : wizardStep === 2
          ? Boolean(sourceMode)
          : true;

  async function load() {
    const generation = ++loadSequence.current;
    inventoryRequest.current?.abort();
    const controller = new AbortController(); inventoryRequest.current = controller;
    const page = await inventory.load(() => listDevices({ search: searchApplied || undefined, locationId: locationFilter === "all" ? undefined : locationFilter, status: statusFilter === "all" ? undefined : statusFilter, limit: 101, offset }, controller.signal), (items) => !items.length);
    if (generation !== loadSequence.current || controller.signal.aborted) return false;
    return page ? evidence.load(page.slice(0, 100)) : false;
  }

  async function loadReferences() {
    const generation = ++referencesGeneration.current;
    const results = await Promise.allSettled([listLocations({ limit: 200 }), listDeviceTypes(), listSimulationPresets(), getCurrentDeviceAccount()]);
    if (generation !== referencesGeneration.current) return;
    const [locationResult, typeResult, presetResult, accountResult] = results;
    if (accountResult.status === "fulfilled" && typeof accountResult.value.id === "string" && typeof accountResult.value.userId === "string") setOwnerAccount(accountResult.value);
    if (locationResult.status === "fulfilled") setLocations(locationResult.value);
    if (typeResult.status === "fulfilled") { setDeviceTypes(typeResult.value); if (typeResult.value.length && !typeResult.value.some((item) => item.code === type)) setType(typeResult.value[0].code); }
    if (presetResult.status === "fulfilled") setSimulationPresets(presetResult.value);
    const unavailable = results.flatMap((result, index) => result.status === "rejected" ? ["Locations", "Device types", "Simulation presets", "Account recovery scope"][index] : []);
    setReferenceError(unavailable.length ? `${unavailable.join(", ")} unavailable. Retry these references.` : null);
    setReferencesReady(true);
  }
  useEffect(() => { void loadReferences(); return () => { referencesGeneration.current++; inventoryRequest.current?.abort(); }; }, []);
  useEffect(() => { void load(); return () => inventoryRequest.current?.abort(); }, [inventoryScope]);

  async function locationOptions({ search, offset, limit, signal }: { search: string; offset: number; limit: number; signal: AbortSignal }) {
    const records = await listLocations({ search: search || undefined, offset, limit }, signal);
    if (signal.aborted) throw new DOMException("Obsolete locations", "AbortError");
    setLocations((current) => [...new Map([...current, ...records].map((location) => [location.id, location])).values()]);
    return records.map((location) => ({ id: location.id, name: locationLabel(location) }));
  }

  function saveSetupCheckpoint() {
    const progress = setupProgress.current;
    writeDeviceSetupCheckpoint(ownerAccount, { deviceId: progress.device?.id, sourceId: progress.dataSource?.id, locationId: progress.device?.locationId ?? createdLocation.current ?? (selectedLocationId || undefined), configurationId: progress.simulationConfig?.id, runId: progress.simulationRun?.runId ?? undefined, pendingStage: progress.unknownStage, name: progress.device?.name ?? name, type: progress.device?.type ?? type, mode: sourceMode, externalId: progress.dataSource?.externalId ?? sourceId, profile: demoProfile, preset: demoPreset, startImmediately: startDemoImmediately });
  }
  const recovery = useDeviceSetupRecovery(ownerAccount, setupProgress, (saved, progress) => {
    setName(progress.device?.name ?? saved.name); setType(progress.device?.type ?? saved.type);
    setSourceMode(saved.mode); setSourceId(progress.dataSource?.externalId ?? saved.externalId);
    setDemoProfile(saved.profile); setDemoPreset(saved.preset); setStartDemoImmediately(saved.startImmediately);
    setSelectedLocationId(progress.device?.locationId ?? saved.locationId ?? ""); setLocationMode("existing");
    createdLocation.current = saved.locationId ?? null;
    setWizardStep(progress.device || progress.unknownStage ? 3 : 0);
  });
  const restoringSetup = recovery.restoring;
  const restoreFailed = recovery.failed;

  function openCreateWizard() {
    if (!referencesReady || restoringSetup) return;
    setWizardStep(setupProgress.current.device || setupProgress.current.unknownStage ? 3 : 0);
    setSetupError(null);
    if (!setupProgress.current.device && !setupProgress.current.unknownStage) setSourceId(defaultSourceId(sourceMode, name || "device"));
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
    if (submittingSetup.current || isSubmitting || setupProgress.current.unknownStage || restoringSetup || restoreFailed) return;
    submittingSetup.current = true;
    setSetupError(null);
    setIsSubmitting(true);
    try {
      const externalId = sourceId.trim() || defaultSourceId(sourceMode, name);
      setSourceId(externalId);
      setupProgress.current.checkpoint = () => { writeDeviceSetupCheckpoint(ownerAccount, { deviceId: setupProgress.current.device?.id, sourceId: setupProgress.current.dataSource?.id, locationId: setupProgress.current.device?.locationId ?? createdLocation.current ?? (selectedLocationId || undefined), configurationId: setupProgress.current.simulationConfig?.id, runId: setupProgress.current.simulationRun?.runId ?? undefined, pendingStage: setupProgress.current.unknownStage, name: setupProgress.current.device?.name ?? name, type, mode: sourceMode, externalId, profile: demoProfile, preset: demoPreset, startImmediately: startDemoImmediately }); };
      if (sourceMode === "SIMULATOR" && !setupProgress.current.sourceToken && !setupProgress.current.simulationConfig?.hasSourceToken) { const answer = await confirmation.confirm({ title: "Configure simulator credential", object: `${name} · ${externalId}`, impact: "Issues or replaces the source ingestion token, granting telemetry ingestion to this source. Existing senders using an older credential must be updated. The server stores the simulator credential; browser setup recovery does not store its plaintext." }); if (answer === null) return; }
      let locationId = selectedLocationId;
      if (locationMode === "new" && createdLocation.current) locationId = createdLocation.current;
      else if (locationMode === "new" && newLocationName.trim()) {
        const location = await deviceSetupStep("location", setupProgress.current, () => createLocation({
          parentId: newLocationParentId || null,
          name: newLocationName.trim(),
          description: null,
          building: newLocationBuilding.trim() || null,
          floor: newLocationFloor.trim() || null,
          room: newLocationRoom.trim() || null,
          zone: newLocationZone.trim() || null,
          tags: splitTags(newLocationTags),
        }));
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
          ? await createDemoDevice(deviceRequest, {
              mode: "SIMULATOR",
              externalId,
              profile: demoProfile,
              preset: demoPreset,
              startImmediately: startDemoImmediately,
            }, setupProgress.current)
          : await createDeviceWithSource(deviceRequest, {
              mode: sourceMode,
              externalId,
            }, setupProgress.current);
      writeDeviceSourcePreference(result.device.id, result.dataSource.externalId);
      setCompletedSetup(result);
      markWorkflowDraftSaved("create-device-form");
      void load();
    } catch (requestError) {
      setSetupError(requestError instanceof Error ? requestError.message : "Failed to create device");
    } finally {
      submittingSetup.current = false;
      setIsSubmitting(false);
    }
  }

  async function onImportDevicesCsv() {
    if (!inspectImportCsv(csvText, ["name", "type", "locationId", "manufacturer", "model"]).valid || isSubmitting) return;
    setError(null);
    setCsvMessage(null);
    setIsSubmitting(true);
    try {
      const response = await importDevicesCsv(csvText);
      setCsvMessage(
        response.errors.length > 0
          ? `Imported ${response.importedCount}; ${response.errors.join("; ")}`
          : `Imported ${response.importedCount} devices`,
      );
      await load();
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to import devices");
    } finally {
      setIsSubmitting(false);
    }
  }

  async function onExportDevicesCsv() {
    setError(null);
    try {
      downloadText(await exportDevicesCsv(), "devices.csv", "text/csv");
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to export devices");
    }
  }

  return (
    <>
      {confirmation.dialog}
      <header className="page-header page-header-row">
        <div>
          <h1>Devices</h1>
          <p>Devices connected to your account.</p>
        </div>
        <div className="header-actions">
          <button className="ghost-button" type="button" onClick={() => void load()} disabled={isLoading}>
            Refresh devices
          </button>
          <button className="ghost-button" type="button" onClick={() => setIsCsvOpen(true)}>
            Import CSV
          </button>
          <button className="ghost-button" type="button" onClick={() => void onExportDevicesCsv()}>
            Export CSV
          </button>
          <button className="primary-button" type="button" disabled={!referencesReady || restoringSetup} onClick={openCreateWizard}>
            Add device
          </button>
        </div>
      </header>

      <section className="panel inventory-panel">
        <ReadRefresh scope={inventoryScope} refresh={load} />
        <SectionFeedback resource={inventory} label="devices" onRetry={() => void load()} />
        <div className="inventory-toolbar">
          <input
            className="search-input"
            aria-label="Search devices"
            value={search}
            onChange={(event) => query.update({ q: event.target.value || null, offset: null }, true)}
            placeholder="Search devices..."
          />
          <EntitySelector label="Filter by location" options={locations.map((location) => ({ id: location.id, name: locationLabel(location) }))} value={locationFilter === "all" ? "" : locationFilter} onChange={(id) => query.update({ locationId: id || null, offset: null })} loadOptions={locationOptions} />
          <label>Registration status<select aria-label="Filter by registration status" value={statusFilter} onChange={(event) => query.update({ status: event.target.value === "all" ? null : event.target.value, offset: null })}>
            <option value="all">All statuses</option>
            {["ACTIVE", "DISABLED", "OFFLINE", "DECOMMISSIONED"].map((status) => (
              <option value={status} key={status}>
                {formatEnum(status)}
              </option>
            ))}
          </select></label>
        </div>

        <button type="button" className="ghost-button" onClick={() => query.update({ q: null, locationId: null, status: null, offset: null })}>Clear filters</button>
        {applyingSearch ? <p role="status">Applying search…</p> : null}
        {referenceError ? <div className="error-message">{referenceError}<button type="button" className="ghost-button" onClick={() => void loadReferences()}>Retry references</button></div> : null}
        {error ? <div className="error-message">{error}</div> : null}
        {isLoading ? <div className="empty-state">Loading devices...</div> : null}
        <label>Inventory view<select value={inventoryView} onChange={(event) => setInventoryView(event.target.value)}><option value="table">Compact table</option><option value="cards">Cards by location</option></select></label>
        <p className="muted-text">Loaded records belong to this page; fleet total is unknown. Registration status is separate from telemetry freshness.</p>
        <PageNavigation offset={offset} size={100} hasMore={hasMore} loading={isLoading || applyingSearch} loaded={devices.length} onChange={(next) => query.update({ offset: next || null })} />
        {!isLoading && hasInventory && devices.length === 0 ? (
          <div className="empty-state">
            <strong>No devices match these filters</strong>
            <span>Clear search or add a new device.</span>
          </div>
        ) : inventoryView === "table" ? (
          <DeviceInventoryTable devices={devices} locationById={locationById} typeByCode={typeByCode} latestByDevice={latestByDevice} riskByDevice={riskByDevice} evidence={evidence} />
        ) : (
          <div className="room-device-board">
            {roomGroups.map((group) => (
              <section className="room-section" key={group.locationId} aria-labelledby={`room-${group.locationId}`}>
                <div className="room-section-header">
                  <div>
                    <h2 id={`room-${group.locationId}`}>{group.name}</h2>
                    <span>{group.devices.length} devices</span>
                  </div>
                </div>
                <div className="device-icon-grid">
                  {group.devices.map((device) => {
                    const latest = latestByDevice[device.id];
                    const risk = riskByDevice[device.id];
                    return (
                      <div key={device.id}>
                      <Link className="device-icon-card" to={`/devices/${device.id}`} key={device.id}>
                        <span className={`device-icon-frame device-icon-${getDeviceTone(device.type)}`} aria-hidden="true">
                          <DeviceIcon type={device.type} />
                        </span>
                        <strong>{device.name}</strong>
                        <span className="device-card-type">{typeByCode.get(device.type) ?? formatEnum(device.type)}</span>
                        <span className="device-card-status">{formatEnum(device.status)}</span>
                        <div className="device-card-signals">
                          <span className={`status-pill status-pill-${risk?.level.toLowerCase() ?? "neutral"}`}>
                            {risk ? formatEnum(risk.level) : !evidence.risk[device.id] || evidence.risk[device.id].status === "loading" ? "Risk pending" : "Risk unavailable"}
                          </span>
                          <span>{latest?.readings.activePowerW !== undefined ? `${formatNumber(latest.readings.activePowerW)} W` : "No data"}</span>
                        </div>
                        <span className="device-card-telemetry">{latest ? formatRelativeTime(latest.timestamp) : !evidence.telemetry[device.id] || evidence.telemetry[device.id].status === "loading" ? "Telemetry pending" : evidence.telemetry[device.id].status === "empty" ? "No readings" : "Telemetry unavailable"}</span>
                      </Link>
                      {evidence.telemetry[device.id] ? <SectionFeedback resource={evidence.telemetry[device.id]} label="telemetry" context={device.name} showFreshness onRetry={() => void evidence.reloadTelemetry(device.id)} /> : null}
                      {evidence.risk[device.id] ? <SectionFeedback resource={evidence.risk[device.id]} label="risk" context={device.name} showFreshness onRetry={() => void evidence.reloadRisk(device.id)} /> : null}
                      </div>
                    );
                  })}
                </div>
              </section>
            ))}
          </div>
        )}
      </section>

      {isCreateOpen ? (
        <Dialog key={completedSetup ? "complete" : "draft"} title={completedSetup ? "Device registered" : "Add device"} onClose={() => { setIsCreateOpen(false); if (completedSetup || !setupProgress.current.device && !setupProgress.current.unknownStage && !restoreFailed) resetCreateForm(); }} guardChanges={!completedSetup} busy={isSubmitting} footer={<>
          <DialogClose />
          {!completedSetup && wizardStep > 0 ? <button className="ghost-button" type="button" onClick={() => setWizardStep((current) => Math.max(current - 1, 0))} disabled={isSubmitting || Boolean(setupProgress.current.unknownStage) || Boolean(setupProgress.current.device) && wizardStep <= 2}>Back</button> : null}
          {completedSetup ? <button className="primary-button" type="button" onClick={() => { const destination = activationRoute(completedSetup.device.id, completedSetup.dataSource.id); setIsCreateOpen(false); resetCreateForm(); navigate(destination); }}>Done · open activation</button> : <button className="primary-button" type="submit" form="create-device-form" disabled={isSubmitting || restoringSetup || restoreFailed || Boolean(setupProgress.current.unknownStage) || !canAdvanceWizard}>{isSubmitting ? "Adding..." : setupProgress.current.device ? "Resume setup" : wizardStep === wizardSteps.length - 1 ? "Add device" : "Next"}</button>}
        </>}>
            {completedSetup ? <DeviceActivationSummary device={completedSetup.device} source={completedSetup.dataSource} run={completedSetup.simulationRun} /> : <form data-draft-id="create-device-form" id="create-device-form" className="device-wizard form-stack" onSubmit={onSubmit}>
              {restoreFailed ? <button type="button" className="ghost-button" onClick={recovery.retry}>Retry setup recovery reads</button> : null}
              {restoringSetup ? <p role="status">Restoring authorized setup references…</p> : null}
              {setupProgress.current.unknownStage ? <DeviceSetupReconciliation progress={setupProgress.current} externalId={sourceId} onReviewed={() => { saveSetupCheckpoint(); setSetupError(null); setWizardStep(3); setSetupRevision((revision) => revision + 1); }} onDevice={(device) => { setName(device.name); setType(device.type); setSelectedLocationId(device.locationId ?? ""); }} onLocation={(id) => { createdLocation.current = id; setSelectedLocationId(id); setLocationMode("existing"); }} /> : null}
              {recovery.error ? <div className="error-message" role="alert">{recovery.error}</div> : null}
              {setupError ? <div className="error-message" role="alert">{setupError}</div> : null}
              {setupProgress.current.device ? <div className="status-note">Device {setupProgress.current.device.name} has been created. Retry continues its setup. <Link to={`/devices/${setupProgress.current.device.id}`}>Open created device</Link><details><summary>Completed steps</summary><p>Device: {setupProgress.current.device.id}</p>{setupProgress.current.dataSource ? <p>Source: {setupProgress.current.dataSource.id}</p> : null}{setupProgress.current.simulationConfig ? <p>Simulator configuration saved</p> : null}</details></div> : null}
              <div className="wizard-steps" aria-label="Add device steps">
                {wizardSteps.map((step, index) => (
                  <button
                    className={index === wizardStep ? "wizard-step wizard-step-active" : index < wizardStep ? "wizard-step wizard-step-done" : "wizard-step"}
                    type="button"
                    key={step}
                    onClick={() => setWizardStep(index)}
                    aria-current={index === wizardStep ? "step" : undefined}
                    disabled={isSubmitting || Boolean(setupProgress.current.unknownStage) || Boolean(setupProgress.current.device) && index < 2 || index > wizardStep}
                  >
                    <span>{index + 1}</span>
                    {step}
                  </button>
                ))}
              </div>

              {wizardStep === 0 ? <DeviceWizardBasics name={name} type={type} manufacturer={manufacturer} model={model} deviceTypes={deviceTypes} setName={setName} setType={setType} setManufacturer={setManufacturer} setModel={setModel} /> : null}

              {wizardStep === 1 ? (
                <div className="wizard-panel">
                  <label>Location choice<select value={locationMode} onChange={(event) => setLocationMode(event.target.value)}><option value="existing">Use existing location</option><option value="new">Create new location</option></select></label>
                  {locationMode === "existing" ? <>
                  <EntitySelector loadOptions={locationOptions} label="Existing location" options={locations.map((location) => ({ id: location.id, name: locationLabel(location) }))} value={selectedLocationId} onChange={setSelectedLocationId} />
                  </> : <>
                  <label>
                    New location
                    <input value={newLocationName} onChange={(event) => setNewLocationName(event.target.value)} placeholder="Kitchen" />
                  </label>
                  {newLocationName.trim() ? (
                    <details><summary>Additional location details</summary>
                      <label>
                        Parent location
                        <select value={newLocationParentId} onChange={(event) => setNewLocationParentId(event.target.value)}>
                          <option value="">No parent</option>
                          {locations.map((location) => (
                            <option value={location.id} key={location.id}>
                              {locationLabel(location)}
                            </option>
                          ))}
                        </select>
                      </label>
                      <div className="form-grid-two">
                        <label>
                          Building
                          <input value={newLocationBuilding} onChange={(event) => setNewLocationBuilding(event.target.value)} placeholder="HQ" />
                        </label>
                        <label>
                          Floor
                          <input value={newLocationFloor} onChange={(event) => setNewLocationFloor(event.target.value)} placeholder="2" />
                        </label>
                        <label>
                          Room
                          <input value={newLocationRoom} onChange={(event) => setNewLocationRoom(event.target.value)} placeholder="204" />
                        </label>
                        <label>
                          Zone
                          <input value={newLocationZone} onChange={(event) => setNewLocationZone(event.target.value)} placeholder="north" />
                        </label>
                      </div>
                      <label>
                        Tags
                        <input value={newLocationTags} onChange={(event) => setNewLocationTags(event.target.value)} placeholder="critical, it" />
                      </label>
                    </details>
                  ) : null}
                  </>}
                </div>
              ) : null}

              {wizardStep === 2 ? (
                <div className="wizard-panel">
                  <div className="source-choice-grid">
                    <button className={sourceMode === "API" ? "source-choice source-choice-active" : "source-choice"} type="button" disabled={Boolean(setupProgress.current.dataSource)} onClick={() => setSourceMode("API")}>
                      <strong>Smart plug/API</strong>
                      <span>REST telemetry source for meters, plugs, and integrations.</span>
                    </button>
                    <button className={sourceMode === "MQTT" ? "source-choice source-choice-active" : "source-choice"} type="button" disabled={Boolean(setupProgress.current.dataSource)} onClick={() => setSourceMode("MQTT")}>
                      <strong>MQTT sensor</strong>
                      <span>MQTT telemetry source for gateway and sensor feeds.</span>
                    </button>
                    <button className={sourceMode === "SIMULATOR" ? "source-choice source-choice-active" : "source-choice"} type="button" disabled={Boolean(setupProgress.current.dataSource)} onClick={() => setSourceMode("SIMULATOR")}>
                      <strong>Simulator/demo source</strong>
                      <span>Creates simulator telemetry without editing YAML first.</span>
                    </button>
                  </div>

                  {sourceMode === "SIMULATOR" ? (
                    <div className="demo-source-panel">
                      <div className="form-grid-two">
                        <label>
                          Demo profile
                          <select disabled={Boolean(setupProgress.current.simulationConfig)} value={demoProfile} onChange={(event) => setDemoProfile(event.target.value)}>
                            <option value="FRIDGE">Fridge</option>
                            <option value="GENERIC_APPLIANCE">Generic appliance</option>
                          </select>
                        </label>
                        <label>
                          Preset
                          <select disabled={Boolean(setupProgress.current.simulationConfig)} value={demoPreset} onChange={(event) => setDemoPreset(event.target.value)}>
                            {demoPresetOptions.map((preset) => (
                              <option value={preset.value} key={preset.value}>
                                {preset.label}
                              </option>
                            ))}
                          </select>
                        </label>
                      </div>
                      <div className="status-note">
                        {selectedPreset.detail} Expected metrics include active power, voltage, current, and accumulated energy.
                      </div>
                      <label className="checkbox-row">
                        <input type="checkbox" checked={startDemoImmediately} onChange={(event) => setStartDemoImmediately(event.target.checked)} />
                        Start telemetry after creation
                      </label>
                    </div>
                  ) : null}

                  <details className="assistant-advanced">
                    <summary>Advanced</summary>
                    <label>
                      Telemetry source ID
                      <input disabled={Boolean(setupProgress.current.dataSource)} value={sourceId} onChange={(event) => setSourceId(event.target.value)} placeholder="Auto-generated" />
                    </label>
                    <label>
                      Metadata JSON
                      <textarea disabled={Boolean(setupProgress.current.device)} className="small-textarea" value={metadataText} onChange={(event) => setMetadataText(event.target.value)} />
                    </label>
                  </details>
                </div>
              ) : null}

              {wizardStep === 3 ? (
                <div className="wizard-panel wizard-review">
                  <dl>
                    <div>
                      <dt>Device</dt>
                      <dd>{name || "Unnamed device"} - {typeByCode.get(type) ?? formatEnum(type)}</dd>
                    </div>
                    <div>
                      <dt>Location</dt>
                      <dd>{locationMode === "new" ? newLocationName.trim() : locationById.get(selectedLocationId) || "No location selected"}</dd>
                    </div>
                    <div>
                      <dt>Telemetry</dt>
                      <dd>{sourceMode === "SIMULATOR" ? `${selectedPreset.label} demo source` : sourceMode === "MQTT" ? "MQTT sensor" : "Smart plug/API"}</dd>
                    </div>
                    <div>
                      <dt>Source ID</dt>
                      <dd>{sourceIdPreview}</dd>
                    </div>
                  </dl>
                </div>
              ) : null}

            </form>}
        </Dialog>
      ) : null}

      {isCsvOpen ? (
        <Dialog title="Import devices" onClose={() => setIsCsvOpen(false)} dirty={csvText.trim() !== "name,type,locationId,manufacturer,model"} busy={isSubmitting} footer={<>
          <DialogClose /><button className="primary-button" type="button" onClick={() => void onImportDevicesCsv()} disabled={isSubmitting || !inspectImportCsv(csvText, ["name", "type", "locationId", "manufacturer", "model"]).valid}>{isSubmitting ? "Importing..." : "Import devices"}</button>
        </>}>
            <p>CSV header: name,type,locationId,manufacturer,model.</p>
            <div className="form-stack">
              <CsvImportPreview text={csvText} onChange={setCsvText} columns={["name", "type", "locationId", "manufacturer", "model"]} example="Kitchen fridge,FRIDGE,replace-with-location-id,Bosch,Example" busy={isSubmitting} />
              {error ? <div className="error-message" role="alert">{error}</div> : null}
              {csvMessage ? <div className="status-note" role="status">{csvMessage}</div> : null}
            </div>
        </Dialog>
      ) : null}
    </>
  );
}
