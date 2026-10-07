import { DeviceIcon, getDeviceTone, formatEnum, formatNumber, formatRelativeTime, locationLabel, splitTags, parseMetadata, downloadText } from "../devices/deviceInventoryPresentation";
import { DeviceInventoryTable } from "../devices/DeviceInventoryTable";
import { DeviceWizardBasics } from "../devices/DeviceWizardBasics";
import { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { getDeviceRisk, RiskResponse } from "../analytics/analyticsApi";
import {
  createLocation,
  Device,
  DeviceType,
  exportDevicesCsv,
  importDevicesCsv,
  listDeviceTypes,
  listDevices,
  listLocations,
  Location,
} from "../devices/devicesApi";
import { createDemoDevice, createDeviceWithSource, defaultSourceId, DeviceSourceMode, DeviceSetupProgress } from "../devices/createDeviceFlow";
import { listSimulationPresets, SimulationPreset } from "../simulation/simulationApi";
import { getLatestTelemetry, LatestTelemetry } from "../telemetry/telemetryApi";
import { Dialog, DialogClose } from "../components/Dialog";
import { EntitySelector } from "../components/EntitySelector";
import { CsvImportPreview, inspectImportCsv } from "../devices/CsvImportPreview";
import { TimeDisplay } from "../components/TimeDisplay";

const wizardSteps = ["Basics", "Location", "Telemetry", "Review"] as const;
const demoPresets = [
  { value: "NORMAL_OPERATION", label: "Normal operation", detail: "Stable telemetry for testing dashboards and charts." },
  { value: "POWER_SPIKE", label: "Power spike", detail: "Short high-power event that should exercise alert rules." },
  { value: "OFFLINE_WINDOW", label: "Offline window", detail: "Temporary telemetry gap for offline detection workflows." },
  { value: "CONSUMPTION_GROWTH", label: "Consumption growth", detail: "Gradual load increase for trend and baseline rules." },
] as const;

export function DevicesPage() {
  const navigate = useNavigate();
  const [devices, setDevices] = useState<Device[]>([]);
  const [locations, setLocations] = useState<Location[]>([]);
  const [deviceTypes, setDeviceTypes] = useState<DeviceType[]>([]);
  const [simulationPresets, setSimulationPresets] = useState<SimulationPreset[]>([]);
  const [latestByDevice, setLatestByDevice] = useState<Record<string, LatestTelemetry | null>>({});
  const [riskByDevice, setRiskByDevice] = useState<Record<string, RiskResponse | null>>({});
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
  const createdLocation = useRef<string | null>(null);
  const [setupError, setSetupError] = useState<string | null>(null);
  const [locationMode, setLocationMode] = useState("existing");
  const [search, setSearch] = useState("");
  const [locationFilter, setLocationFilter] = useState("all");
  const [statusFilter, setStatusFilter] = useState("all");
  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const [isCsvOpen, setIsCsvOpen] = useState(false);
  const [inventoryView, setInventoryView] = useState("cards");
  const [csvText, setCsvText] = useState("name,type,locationId,manufacturer,model\n");
  const [csvMessage, setCsvMessage] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [hasInventory, setHasInventory] = useState(false);
  const loadSequence = useRef(0);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const locationById = useMemo(
    () => new Map(locations.map((location) => [location.id, locationLabel(location)])),
    [locations],
  );
  const typeByCode = useMemo(() => new Map(deviceTypes.map((deviceType) => [deviceType.code, deviceType.displayName])), [deviceTypes]);
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
    setIsLoading(true);
    setError(null);
    try {
      const [deviceResult, locationResult, typeResult, presetResult] = await Promise.allSettled([
        listDevices({
          search: search.trim() || undefined,
          locationId: locationFilter === "all" ? undefined : locationFilter,
          status: statusFilter === "all" ? undefined : statusFilter,
          limit: 100,
          offset: 0,
        }),
        listLocations({ limit: 200, offset: 0 }),
        listDeviceTypes(),
        listSimulationPresets(),
      ]);
      if (generation !== loadSequence.current) return;
      if (deviceResult.status === "fulfilled") {
        setHasInventory(true);
        setDevices(deviceResult.value);
        void loadDeviceSignals(deviceResult.value);
      }
      if (locationResult.status === "fulfilled") setLocations(locationResult.value);
      if (typeResult.status === "fulfilled") {
        setDeviceTypes(typeResult.value);
        if (typeResult.value.length && !typeResult.value.some((item) => item.code === type)) setType(typeResult.value[0].code);
      }
      if (presetResult.status === "fulfilled") setSimulationPresets(presetResult.value);
      const failures = [deviceResult, locationResult, typeResult, presetResult].flatMap((result, index) => result.status === "rejected" ? [`${["Devices", "Locations", "Device types", "Simulation presets"][index]} unavailable: ${result.reason instanceof Error ? result.reason.message : "request failed"}`] : []);
      if (failures.length) setError(`${failures.join("; ")}. Retained results may be stale.`);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to load devices");
    } finally {
      if (generation === loadSequence.current) setIsLoading(false);
    }
  }

  async function loadDeviceSignals(devicesToLoad: Device[]) {
    const generation = loadSequence.current;
    const entries = await Promise.all(devicesToLoad.map(async (device) => {
      const [latest, risk] = await Promise.allSettled([getLatestTelemetry(device.id), getDeviceRisk(device.id)]);
      return { deviceId: device.id, latest, risk };
    }));
    if (generation !== loadSequence.current) return;
    setLatestByDevice((previous) => Object.fromEntries(entries.map(({ deviceId, latest }) => [deviceId, latest.status === "fulfilled" ? latest.value : previous[deviceId] ?? null])));
    setRiskByDevice((previous) => Object.fromEntries(entries.map(({ deviceId, risk }) => [deviceId, risk.status === "fulfilled" ? risk.value : previous[deviceId] ?? null])));
    if (entries.some(({ latest, risk }) => latest.status === "rejected" || risk.status === "rejected")) setError((current) => `${current ?? ""} Some telemetry or risk sections are unavailable; retained values may be stale.`);
  }

  useEffect(() => {
    void load();
  }, [locationFilter, search, statusFilter]);

  function openCreateWizard() {
    setWizardStep(setupProgress.current.device ? 3 : 0);
    setSetupError(null);
    if (!setupProgress.current.device) setSourceId(defaultSourceId(sourceMode, name || "device"));
    setIsCreateOpen(true);
  }

  function resetCreateForm() {
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
    if (isSubmitting) return;
    setSetupError(null);
    setIsSubmitting(true);
    try {
      let locationId = selectedLocationId;
      if (locationMode === "new" && createdLocation.current) locationId = createdLocation.current;
      else if (locationMode === "new" && newLocationName.trim()) {
        const location = await createLocation({
          parentId: newLocationParentId || null,
          name: newLocationName.trim(),
          description: null,
          building: newLocationBuilding.trim() || null,
          floor: newLocationFloor.trim() || null,
          room: newLocationRoom.trim() || null,
          zone: newLocationZone.trim() || null,
          tags: splitTags(newLocationTags),
        });
        locationId = location.id;
        createdLocation.current = location.id;
        setLocations((current) => [...current, location]);
      }
      if (!locationId) {
        throw new Error("Choose or create a location");
      }
      const metadata = parseMetadata(metadataText);
      const externalId = sourceId.trim() || defaultSourceId(sourceMode, name);
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
      const device = result.device;
      localStorage.setItem(`voltcanary.deviceSource.${device.id}`, externalId);
      setDevices((current) => [...current, device]);
      resetCreateForm();
      setIsCreateOpen(false);
      navigate(sourceMode === "SIMULATOR" ? `/devices/${device.id}?tab=telemetry` : `/devices/${device.id}`);
    } catch (requestError) {
      setSetupError(requestError instanceof Error ? requestError.message : "Failed to create device");
    } finally {
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
          <button className="primary-button" type="button" onClick={openCreateWizard}>
            Add device
          </button>
        </div>
      </header>

      <section className="panel inventory-panel">
        <div className="inventory-toolbar">
          <input
            className="search-input"
            aria-label="Search devices"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Search devices..."
          />
          <label>Location<select aria-label="Filter by location" value={locationFilter} onChange={(event) => setLocationFilter(event.target.value)}>
            <option value="all">All locations</option>
            {locations.map((location) => (
              <option value={location.id} key={location.id}>
                {locationLabel(location)}
              </option>
            ))}
          </select></label>
          <label>Registration status<select aria-label="Filter by registration status" value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)}>
            <option value="all">All statuses</option>
            {[...new Set(devices.map((device) => device.status))].map((status) => (
              <option value={status} key={status}>
                {formatEnum(status)}
              </option>
            ))}
          </select></label>
        </div>

        {error ? <div className="error-message">{error}</div> : null}
        {isLoading ? <div className="empty-state">Loading devices...</div> : null}
        <label>Inventory view<select value={inventoryView} onChange={(event) => setInventoryView(event.target.value)}><option value="table">Compact table</option><option value="cards">Cards by location</option></select></label>
        <p className="muted-text">Showing up to 100 matching devices. Registration status is separate from telemetry freshness.</p>
        {!isLoading && hasInventory && devices.length === 0 ? (
          <div className="empty-state">
            <strong>No devices match these filters</strong>
            <span>Clear search or add a new device.</span>
          </div>
        ) : inventoryView === "table" ? (
          <DeviceInventoryTable devices={devices} locationById={locationById} typeByCode={typeByCode} latestByDevice={latestByDevice} riskByDevice={riskByDevice} />
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
                      <Link className="device-icon-card" to={`/devices/${device.id}`} key={device.id}>
                        <span className={`device-icon-frame device-icon-${getDeviceTone(device.type)}`} aria-hidden="true">
                          <DeviceIcon type={device.type} />
                        </span>
                        <strong>{device.name}</strong>
                        <span className="device-card-type">{typeByCode.get(device.type) ?? formatEnum(device.type)}</span>
                        <span className="device-card-status">{formatEnum(device.status)}</span>
                        <div className="device-card-signals">
                          <span className={`status-pill status-pill-${risk?.level.toLowerCase() ?? "neutral"}`}>
                            {risk ? formatEnum(risk.level) : "No score"}
                          </span>
                          <span>{latest?.readings.activePowerW !== undefined ? `${formatNumber(latest.readings.activePowerW)} W` : "No data"}</span>
                        </div>
                        <span className="device-card-telemetry">{latest ? formatRelativeTime(latest.timestamp) : "No telemetry"}</span>
                      </Link>
                    );
                  })}
                </div>
              </section>
            ))}
          </div>
        )}
      </section>

      {isCreateOpen ? (
        <Dialog title="Add device" onClose={() => { setIsCreateOpen(false); if (!setupProgress.current.device) resetCreateForm(); }} guardChanges busy={isSubmitting} footer={<>
          <DialogClose />
          {wizardStep > 0 ? <button className="ghost-button" type="button" onClick={() => setWizardStep((current) => Math.max(current - 1, 0))} disabled={isSubmitting || Boolean(setupProgress.current.device)}>Back</button> : null}
          <button className="primary-button" type="submit" form="create-device-form" disabled={isSubmitting || !canAdvanceWizard}>{isSubmitting ? "Adding..." : setupProgress.current.device ? "Resume setup" : wizardStep === wizardSteps.length - 1 ? "Add device" : "Next"}</button>
        </>}>
            <form id="create-device-form" className="device-wizard form-stack" onSubmit={onSubmit}>
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
                    disabled={isSubmitting || Boolean(setupProgress.current.device) || index > wizardStep}
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
                  <EntitySelector label="Existing location" options={locations.map((location) => ({ id: location.id, name: locationLabel(location) }))} value={selectedLocationId} onChange={setSelectedLocationId} />
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
                    <button className={sourceMode === "API" ? "source-choice source-choice-active" : "source-choice"} type="button" onClick={() => setSourceMode("API")}>
                      <strong>Smart plug/API</strong>
                      <span>REST telemetry source for meters, plugs, and integrations.</span>
                    </button>
                    <button className={sourceMode === "MQTT" ? "source-choice source-choice-active" : "source-choice"} type="button" onClick={() => setSourceMode("MQTT")}>
                      <strong>MQTT sensor</strong>
                      <span>MQTT telemetry source for gateway and sensor feeds.</span>
                    </button>
                    <button className={sourceMode === "SIMULATOR" ? "source-choice source-choice-active" : "source-choice"} type="button" onClick={() => setSourceMode("SIMULATOR")}>
                      <strong>Simulator/demo source</strong>
                      <span>Creates simulator telemetry without editing YAML first.</span>
                    </button>
                  </div>

                  {sourceMode === "SIMULATOR" ? (
                    <div className="demo-source-panel">
                      <div className="form-grid-two">
                        <label>
                          Demo profile
                          <select value={demoProfile} onChange={(event) => setDemoProfile(event.target.value)}>
                            <option value="FRIDGE">Fridge</option>
                            <option value="GENERIC_APPLIANCE">Generic appliance</option>
                          </select>
                        </label>
                        <label>
                          Preset
                          <select value={demoPreset} onChange={(event) => setDemoPreset(event.target.value)}>
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
                      <input value={sourceId} onChange={(event) => setSourceId(event.target.value)} placeholder="Auto-generated" />
                    </label>
                    <label>
                      Metadata JSON
                      <textarea className="small-textarea" value={metadataText} onChange={(event) => setMetadataText(event.target.value)} />
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

            </form>
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
