import { FormEvent, useEffect, useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { getDeviceRisk, RiskResponse } from "../analytics/analyticsApi";
import {
  createDataSource,
  createDevice,
  createLocation,
  Device,
  DeviceType,
  listDeviceTypes,
  listDevices,
  listLocations,
  Location,
} from "../devices/devicesApi";
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
  const [name, setName] = useState("");
  const [type, setType] = useState("FRIDGE");
  const [manufacturer, setManufacturer] = useState("");
  const [model, setModel] = useState("");
  const [sourceId, setSourceId] = useState("");
  const [search, setSearch] = useState("");
  const [locationFilter, setLocationFilter] = useState("all");
  const [statusFilter, setStatusFilter] = useState("all");
  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const locationById = useMemo(
    () => new Map(locations.map((location) => [location.id, fixKnownTypos(location.name)])),
    [locations],
  );
  const typeByCode = useMemo(() => new Map(deviceTypes.map((deviceType) => [deviceType.code, deviceType.displayName])), [deviceTypes]);
  const filteredDevices = useMemo(() => {
    const query = search.trim().toLowerCase();
    return devices.filter((device) => {
      const locationName = locationById.get(device.locationId ?? "") ?? "";
      const matchesSearch =
        !query ||
        [device.name, device.type, device.status, locationName, device.manufacturer, device.model]
          .filter(Boolean)
          .some((value) => value!.toLowerCase().includes(query));
      const matchesLocation = locationFilter === "all" || device.locationId === locationFilter;
      const matchesStatus = statusFilter === "all" || device.status === statusFilter;
      return matchesSearch && matchesLocation && matchesStatus;
    });
  }, [devices, locationById, locationFilter, search, statusFilter]);

  async function load() {
    setIsLoading(true);
    setError(null);
    try {
      const [devicesResponse, locationsResponse, deviceTypesResponse] = await Promise.all([
        listDevices(),
        listLocations(),
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
  }, []);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setIsSubmitting(true);
    try {
      let locationId = selectedLocationId;
      if (newLocationName.trim()) {
        const location = await createLocation(newLocationName.trim());
        locationId = location.id;
        setLocations((current) => [...current, location]);
      }
      if (!locationId) {
        throw new Error("Choose or create a location");
      }
      const device = await createDevice({
        locationId,
        name,
        type,
        manufacturer: manufacturer || "Unknown",
        model: model || "Unknown",
      });
      await createDataSource(device.id, {
        sourceType: "SMART_PLUG",
        protocol: "REST",
        externalId: sourceId,
      });
      localStorage.setItem(`voltcanary.deviceSource.${device.id}`, sourceId);
      setDevices((current) => [...current, device]);
      setName("");
      setManufacturer("");
      setModel("");
      setSourceId("");
      setNewLocationName("");
      setIsCreateOpen(false);
      navigate(`/devices/${device.id}`);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to create device");
    } finally {
      setIsSubmitting(false);
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
                {fixKnownTypos(location.name)}
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
        {!isLoading && filteredDevices.length === 0 ? (
          <div className="empty-state">
            <strong>No devices match these filters</strong>
            <span>Clear search or add a new device.</span>
          </div>
        ) : (
          <div className="device-list rich-device-list">
            {filteredDevices.map((device) => {
              const latest = latestByDevice[device.id];
              const risk = riskByDevice[device.id];
              return (
                <Link className="device-row rich-device-row" to={`/devices/${device.id}`} key={device.id}>
                  <div className="device-primary">
                    <span className="device-type-icon" aria-hidden="true">
                      {getDeviceInitial(typeByCode.get(device.type) ?? device.type)}
                    </span>
                    <div>
                      <strong>{device.name}</strong>
                      <span>
                        {locationById.get(device.locationId ?? "") ?? "No location"} · {typeByCode.get(device.type) ?? formatEnum(device.type)}
                      </span>
                      <span>
                        {formatEnum(device.status)} · Last telemetry: {latest ? formatRelativeTime(latest.timestamp) : "No data"}
                      </span>
                    </div>
                  </div>
                  <div className="device-meta device-signal-meta">
                    <span className={`status-pill status-pill-${risk?.level.toLowerCase() ?? "neutral"}`}>
                      Risk: {risk ? formatEnum(risk.level) : "No score"}
                    </span>
                    <span>Power: {latest?.readings.activePowerW !== undefined ? `${formatNumber(latest.readings.activePowerW)} W` : "No data"}</span>
                  </div>
                </Link>
              );
            })}
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
                      {fixKnownTypos(location.name)}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                New location
                <input value={newLocationName} onChange={(event) => setNewLocationName(event.target.value)} placeholder="Kitchen" />
              </label>
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
    </>
  );
}

function getDeviceInitial(type: string) {
  return formatEnum(type).charAt(0) || "D";
}

function formatEnum(value: string) {
  return value
    .toLowerCase()
    .split(/[_\s-]+/)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

function fixKnownTypos(value: string) {
  return value.replace(/\bKithen\b/gi, "Kitchen");
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
