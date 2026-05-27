import { FormEvent, useEffect, useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { getDeviceRisk, RiskResponse } from "../analytics/analyticsApi";
import {
  createDataSource,
  createDataSourceToken,
  createDevice,
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
import { getSimulationTemplate, saveSimulationConfig } from "../simulation/simulationApi";
import { getLatestTelemetry, LatestTelemetry } from "../telemetry/telemetryApi";

export function DevicesPage() {
  const navigate = useNavigate();
  const [devices, setDevices] = useState<Device[]>([]);
  const [locations, setLocations] = useState<Location[]>([]);
  const [deviceTypes, setDeviceTypes] = useState<DeviceType[]>([]);
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
  const [sourceType, setSourceType] = useState("SMART_PLUG");
  const [search, setSearch] = useState("");
  const [locationFilter, setLocationFilter] = useState("all");
  const [statusFilter, setStatusFilter] = useState("all");
  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const [isCsvOpen, setIsCsvOpen] = useState(false);
  const [csvText, setCsvText] = useState("name,type,locationId,manufacturer,model\n");
  const [csvMessage, setCsvMessage] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
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

  async function load() {
    setIsLoading(true);
    setError(null);
    try {
      const [devicesResponse, locationsResponse, deviceTypesResponse] = await Promise.all([
        listDevices({
          search: search.trim() || undefined,
          locationId: locationFilter === "all" ? undefined : locationFilter,
          status: statusFilter === "all" ? undefined : statusFilter,
          limit: 100,
          offset: 0,
        }),
        listLocations({ limit: 200, offset: 0 }),
        listDeviceTypes(),
      ]);
      setDevices(devicesResponse);
      setLocations(locationsResponse);
      setDeviceTypes(deviceTypesResponse);
      if (!selectedLocationId && locationsResponse.length > 0) {
        setSelectedLocationId(locationsResponse[0].id);
      }
      if (deviceTypesResponse.length > 0 && !deviceTypesResponse.some((item) => item.code === type)) {
        setType(deviceTypesResponse[0].code);
      }
      void loadDeviceSignals(devicesResponse);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to load devices");
    } finally {
      setIsLoading(false);
    }
  }

  async function loadDeviceSignals(devicesToLoad: Device[]) {
    const entries = await Promise.all(
      devicesToLoad.map(async (device) => {
        const [latest, risk] = await Promise.all([
          getLatestTelemetry(device.id).catch(() => null),
          getDeviceRisk(device.id).catch(() => null),
        ]);
        return [device.id, latest, risk] as const;
      }),
    );
    setLatestByDevice(Object.fromEntries(entries.map(([deviceId, latest]) => [deviceId, latest])));
    setRiskByDevice(Object.fromEntries(entries.map(([deviceId, , risk]) => [deviceId, risk])));
  }

  useEffect(() => {
    void load();
  }, [locationFilter, search, statusFilter]);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setIsSubmitting(true);
    try {
      let locationId = selectedLocationId;
      if (newLocationName.trim()) {
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
        setLocations((current) => [...current, location]);
      }
      if (!locationId) {
        throw new Error("Choose or create a location");
      }
      const metadata = parseMetadata(metadataText);
      const device = await createDevice({
        locationId,
        name,
        type,
        manufacturer: manufacturer || "Unknown",
        model: model || "Unknown",
        metadata,
      });
      const dataSource = await createDataSource(device.id, {
        sourceType,
        protocol: "REST",
        externalId: sourceId,
      });
      if (sourceType === "SIMULATOR") {
        const token = await createDataSourceToken(device.id, dataSource.id);
        const template = await getSimulationTemplate("FRIDGE", sourceId);
        await saveSimulationConfig(device.id, {
          dataSourceId: dataSource.id,
          name: `${name} Simulator`,
          profile: "FRIDGE",
          protocol: "HTTP",
          yamlConfig: template.yamlConfig.replace("sim-fridge-001", sourceId),
          sourceToken: token.token,
        });
      }
      localStorage.setItem(`voltcanary.deviceSource.${device.id}`, sourceId);
      setDevices((current) => [...current, device]);
      setName("");
      setManufacturer("");
      setModel("");
      setMetadataText("{}");
      setSourceId("");
      setSourceType("SMART_PLUG");
      setNewLocationName("");
      setNewLocationParentId("");
      setNewLocationBuilding("");
      setNewLocationFloor("");
      setNewLocationRoom("");
      setNewLocationZone("");
      setNewLocationTags("");
      setIsCreateOpen(false);
      navigate(sourceType === "SIMULATOR" ? `/devices/${device.id}?tab=simulation` : `/devices/${device.id}`);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to create device");
    } finally {
      setIsSubmitting(false);
    }
  }

  async function onImportDevicesCsv() {
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
          <button className="primary-button" type="button" onClick={() => setIsCreateOpen(true)}>
            Add device
          </button>
        </div>
      </header>

      <section className="panel inventory-panel">
        <div className="inventory-toolbar">
          <input
            className="search-input"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Search devices..."
          />
          <select value={locationFilter} onChange={(event) => setLocationFilter(event.target.value)}>
            <option value="all">All locations</option>
            {locations.map((location) => (
              <option value={location.id} key={location.id}>
                {locationLabel(location)}
              </option>
            ))}
          </select>
          <select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)}>
            <option value="all">All statuses</option>
            {[...new Set(devices.map((device) => device.status))].map((status) => (
              <option value={status} key={status}>
                {formatEnum(status)}
              </option>
            ))}
          </select>
        </div>

        {error ? <div className="error-message">{error}</div> : null}
        {isLoading ? <div className="empty-state">Loading devices...</div> : null}
        {!isLoading && devices.length === 0 ? (
          <div className="empty-state">
            <strong>No devices match these filters</strong>
            <span>Clear search or add a new device.</span>
          </div>
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
        <div className="modal-backdrop" role="presentation" onMouseDown={() => setIsCreateOpen(false)}>
          <section className="modal-panel" role="dialog" aria-modal="true" aria-labelledby="add-device-title" onMouseDown={(event) => event.stopPropagation()}>
            <div className="section-header">
              <div>
                <h2 id="add-device-title">Add device</h2>
                <p>Connect a device and its telemetry source.</p>
              </div>
              <button className="ghost-button icon-button-text" type="button" onClick={() => setIsCreateOpen(false)}>
                Close
              </button>
            </div>
            <form className="form-stack" onSubmit={onSubmit}>
              <label>
                Name
                <input value={name} onChange={(event) => setName(event.target.value)} required placeholder="Kitchen fridge" />
              </label>
              <label>
                Location
                <select value={selectedLocationId} onChange={(event) => setSelectedLocationId(event.target.value)}>
                  <option value="">Create new location</option>
                  {locations.map((location) => (
                    <option value={location.id} key={location.id}>
                      {locationLabel(location)}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                New location
                <input value={newLocationName} onChange={(event) => setNewLocationName(event.target.value)} placeholder="Kitchen" />
              </label>
              {newLocationName.trim() ? (
                <>
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
                </>
              ) : null}
              <label>
                Device type
                <select value={type} onChange={(event) => setType(event.target.value)}>
                  {deviceTypes.map((deviceType) => (
                    <option value={deviceType.code} key={deviceType.code}>
                      {deviceType.displayName}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                Manufacturer
                <input value={manufacturer} onChange={(event) => setManufacturer(event.target.value)} placeholder="Bosch" />
              </label>
              <label>
                Model
                <input value={model} onChange={(event) => setModel(event.target.value)} placeholder="Unknown" />
              </label>
              <label>
                Metadata JSON
                <textarea className="small-textarea" value={metadataText} onChange={(event) => setMetadataText(event.target.value)} />
              </label>
              <label>
                Data source
                <select value={sourceType} onChange={(event) => setSourceType(event.target.value)}>
                  <option value="SMART_PLUG">Manual REST/API</option>
                  <option value="SIMULATOR">Simulator</option>
                  <option value="MQTT">MQTT</option>
                </select>
              </label>
              <label>
                Telemetry source ID
                <input value={sourceId} onChange={(event) => setSourceId(event.target.value)} required placeholder="plug-001" />
              </label>
              <div className="action-row">
                <button className="ghost-button" type="button" onClick={() => setIsCreateOpen(false)}>
                  Cancel
                </button>
                <button className="primary-button" type="submit" disabled={isSubmitting}>
                  {isSubmitting ? "Adding..." : "Add device"}
                </button>
              </div>
            </form>
          </section>
        </div>
      ) : null}

      {isCsvOpen ? (
        <div className="modal-backdrop" role="presentation" onMouseDown={() => setIsCsvOpen(false)}>
          <section className="modal-panel" role="dialog" aria-modal="true" aria-labelledby="device-import-title" onMouseDown={(event) => event.stopPropagation()}>
            <div className="section-header">
              <div>
                <h2 id="device-import-title">Import devices</h2>
                <p>CSV header: name,type,locationId,manufacturer,model.</p>
              </div>
              <button className="ghost-button icon-button-text" type="button" onClick={() => setIsCsvOpen(false)}>
                Close
              </button>
            </div>
            <div className="form-stack">
              <textarea className="csv-textarea" value={csvText} onChange={(event) => setCsvText(event.target.value)} />
              {csvMessage ? <div className="success-message">{csvMessage}</div> : null}
              <div className="action-row">
                <button className="ghost-button" type="button" onClick={() => setIsCsvOpen(false)}>
                  Cancel
                </button>
                <button className="primary-button" type="button" onClick={() => void onImportDevicesCsv()} disabled={isSubmitting}>
                  {isSubmitting ? "Importing..." : "Import devices"}
                </button>
              </div>
            </div>
          </section>
        </div>
      ) : null}
    </>
  );
}

function DeviceIcon({ type }: { type: string }) {
  const normalizedType = type.toUpperCase();
  if (normalizedType.includes("FRIDGE") || normalizedType.includes("FREEZER")) {
    return (
      <svg viewBox="0 0 48 48" role="img">
        <rect x="15" y="6" width="18" height="36" rx="3" />
        <line x1="15" y1="20" x2="33" y2="20" />
        <line x1="20" y1="12" x2="20" y2="16" />
        <line x1="20" y1="25" x2="20" y2="31" />
      </svg>
    );
  }
  if (normalizedType.includes("HEATER") || normalizedType.includes("BOILER")) {
    return (
      <svg viewBox="0 0 48 48" role="img">
        <rect x="11" y="14" width="26" height="22" rx="4" />
        <line x1="17" y1="20" x2="31" y2="20" />
        <path d="M18 30c2-4 5-4 7 0s5 4 7 0" />
        <line x1="16" y1="40" x2="16" y2="36" />
        <line x1="32" y1="40" x2="32" y2="36" />
      </svg>
    );
  }
  if (normalizedType.includes("AC") || normalizedType.includes("AIR")) {
    return (
      <svg viewBox="0 0 48 48" role="img">
        <rect x="8" y="12" width="32" height="16" rx="4" />
        <line x1="14" y1="22" x2="34" y2="22" />
        <path d="M17 33c2 2 4 2 6 0" />
        <path d="M25 33c2 2 4 2 6 0" />
      </svg>
    );
  }
  if (normalizedType.includes("WASH")) {
    return (
      <svg viewBox="0 0 48 48" role="img">
        <rect x="12" y="7" width="24" height="34" rx="4" />
        <circle cx="24" cy="27" r="8" />
        <circle cx="18" cy="13" r="1.8" />
        <line x1="23" y1="13" x2="31" y2="13" />
      </svg>
    );
  }
  if (normalizedType.includes("PUMP")) {
    return (
      <svg viewBox="0 0 48 48" role="img">
        <circle cx="22" cy="24" r="10" />
        <path d="M31 20h7v8h-7" />
        <path d="M12 24H7" />
        <path d="M20 18l7 6-7 6z" />
      </svg>
    );
  }
  if (normalizedType.includes("SERVER") || normalizedType.includes("NAS")) {
    return (
      <svg viewBox="0 0 48 48" role="img">
        <rect x="12" y="8" width="24" height="32" rx="3" />
        <line x1="17" y1="17" x2="31" y2="17" />
        <line x1="17" y1="25" x2="31" y2="25" />
        <circle cx="18" cy="33" r="1.7" />
        <circle cx="24" cy="33" r="1.7" />
      </svg>
    );
  }
  if (normalizedType.includes("PRINTER")) {
    return (
      <svg viewBox="0 0 48 48" role="img">
        <rect x="14" y="7" width="20" height="11" rx="2" />
        <rect x="10" y="18" width="28" height="16" rx="3" />
        <rect x="15" y="30" width="18" height="10" rx="2" />
        <circle cx="33" cy="24" r="1.8" />
      </svg>
    );
  }
  return (
    <svg viewBox="0 0 48 48" role="img">
      <rect x="14" y="10" width="20" height="28" rx="4" />
      <path d="M19 10V6" />
      <path d="M29 10V6" />
      <path d="M20 20h8" />
      <path d="M20 27h8" />
    </svg>
  );
}

function getDeviceTone(type: string) {
  const normalizedType = type.toUpperCase();
  if (normalizedType.includes("FRIDGE") || normalizedType.includes("FREEZER")) {
    return "cool";
  }
  if (normalizedType.includes("HEATER") || normalizedType.includes("BOILER")) {
    return "warm";
  }
  if (normalizedType.includes("SERVER") || normalizedType.includes("NAS")) {
    return "compute";
  }
  if (normalizedType.includes("PUMP") || normalizedType.includes("WASH")) {
    return "water";
  }
  return "neutral";
}

function formatEnum(value: string) {
  return value
    .toLowerCase()
    .split(/[_\s-]+/)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

function formatNumber(value: number) {
  return Number.isInteger(value) ? value.toLocaleString() : value.toLocaleString(undefined, { maximumFractionDigits: 2 });
}

function formatRelativeTime(value: string) {
  const diffMs = Date.now() - new Date(value).getTime();
  const diffMinutes = Math.max(0, Math.round(diffMs / 60000));
  if (diffMinutes < 1) {
    return "just now";
  }
  if (diffMinutes < 60) {
    return `${diffMinutes} min ago`;
  }
  const diffHours = Math.round(diffMinutes / 60);
  if (diffHours < 24) {
    return `${diffHours} hr ago`;
  }
  return new Date(value).toLocaleString([], { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
}

function locationLabel(location: Location) {
  const hierarchy = [location.building, location.floor ? `Floor ${location.floor}` : null, location.room, location.zone]
    .filter(Boolean)
    .join(" / ");
  return hierarchy ? `${location.name} - ${hierarchy}` : location.name;
}

function splitTags(value: string) {
  return value
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
}

function parseMetadata(value: string) {
  const trimmed = value.trim();
  if (!trimmed || trimmed === "{}") {
    return null;
  }
  const parsed = JSON.parse(trimmed) as Record<string, unknown>;
  const secretKey = Object.keys(parsed).find((key) => /password|token|secret|credential/i.test(key));
  if (secretKey) {
    throw new Error(`Metadata must not contain secret key: ${secretKey}`);
  }
  return parsed;
}

function downloadText(text: string, filename: string, type: string) {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}
