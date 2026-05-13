import { FormEvent, useEffect, useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
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

export function DevicesPage() {
  const navigate = useNavigate();
  const [devices, setDevices] = useState<Device[]>([]);
  const [locations, setLocations] = useState<Location[]>([]);
  const [deviceTypes, setDeviceTypes] = useState<DeviceType[]>([]);
  const [selectedLocationId, setSelectedLocationId] = useState("");
  const [newLocationName, setNewLocationName] = useState("");
  const [name, setName] = useState("");
  const [type, setType] = useState("FRIDGE");
  const [manufacturer, setManufacturer] = useState("");
  const [model, setModel] = useState("");
  const [sourceId, setSourceId] = useState("");
  const [isLoading, setIsLoading] = useState(true);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const locationById = useMemo(
    () => new Map(locations.map((location) => [location.id, location.name])),
    [locations],
  );

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
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to load devices");
    } finally {
      setIsLoading(false);
    }
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
      navigate(`/devices/${device.id}`);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to create device");
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <>
      <header className="page-header">
        <h1>Devices</h1>
        <p>Real devices from your account.</p>
      </header>

      <div className="content-grid">
        <section className="panel">
          <div className="section-header">
            <h2>Inventory</h2>
            <button className="ghost-button" type="button" onClick={() => void load()} disabled={isLoading}>
              Refresh
            </button>
          </div>
          {error ? <div className="error-message">{error}</div> : null}
          {isLoading ? <div className="empty-state">Loading devices...</div> : null}
          {!isLoading && devices.length === 0 ? (
            <div className="empty-state">No devices yet</div>
          ) : (
            <div className="device-list">
              {devices.map((device) => (
                <Link className="device-row" to={`/devices/${device.id}`} key={device.id}>
                  <div>
                    <strong>{device.name}</strong>
                    <span>{locationById.get(device.locationId ?? "") ?? "No location"}</span>
                  </div>
                  <div className="device-meta">
                    <span>{device.type}</span>
                    <span>{device.status}</span>
                  </div>
                </Link>
              ))}
            </div>
          )}
        </section>

        <section className="panel">
          <h2>Create Device</h2>
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
                    {location.name}
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
              Source ID
              <input value={sourceId} onChange={(event) => setSourceId(event.target.value)} required placeholder="plug-001" />
            </label>
            <button className="primary-button" type="submit" disabled={isSubmitting}>
              {isSubmitting ? "Creating..." : "Create device"}
            </button>
          </form>
        </section>
      </div>
    </>
  );
}
