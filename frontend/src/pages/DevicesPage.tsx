import { DeviceCreationWizard } from "../devices/DeviceCreationWizard";
import {
  DeviceIcon,
  getDeviceTone,
  formatEnum,
  formatNumber,
  formatRelativeTime,
  locationLabel,
  downloadText,
} from "../devices/deviceInventoryPresentation";
import { DeviceInventoryTable } from "../devices/DeviceInventoryTable";
import { useEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";
import {
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
import { Dialog, DialogClose } from "../components/Dialog";
import { CsvImportPreview, inspectImportCsv } from "../devices/CsvImportPreview";
import { useInventoryEvidence } from "../devices/useInventoryEvidence";
import { useSectionResource } from "../components/useSectionResource";
import { SectionFeedback } from "../components/SectionFeedback";
import { DeviceInventoryControls } from "../devices/DeviceInventoryControls";
import { useEvidenceClock } from "../components/useEvidenceClock";
import { useConsoleQuery } from "../app/useConsoleQuery";
import { useDebouncedValue } from "../components/useDebouncedValue";
import { PageNavigation } from "../components/PageNavigation";
import { useTaskEvidence } from "../aiAgent/taskEvidence";

export function DevicesPage() {
  const [ownerAccount, setOwnerAccount] = useState<DeviceAccount | null>(null);
  const query = useConsoleQuery();
  const [locations, setLocations] = useState<Location[]>([]);
  const [deviceTypes, setDeviceTypes] = useState<DeviceType[]>([]);
  const [search] = query.state<string>("q", "");
  const [locationFilter] = query.state<string>("locationId", "all");
  const [statusFilter] = query.state<string>("status", "all");
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

  const evidence = useInventoryEvidence(inventoryScope);
  const { latestByDevice, riskByDevice } = evidence;
  useEvidenceClock();
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
  const typeByCode = useMemo(
    () => new Map(deviceTypes.map((deviceType) => [deviceType.code, deviceType.displayName])),
    [deviceTypes],
  );
  useTaskEvidence({
    names: { location: locations.find((location) => location.id === locationFilter)?.name },
  });
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
        name:
          locationId === "unassigned"
            ? "No location"
            : (locationById.get(locationId) ?? "Unknown location"),
        devices: roomDevices.sort((left, right) => left.name.localeCompare(right.name)),
        order:
          locationId === "unassigned"
            ? Number.MAX_SAFE_INTEGER
            : orderedLocationIds.indexOf(locationId),
      }))
      .sort((left, right) => left.order - right.order || left.name.localeCompare(right.name));
  }, [devices, locationById, locations]);
  async function load() {
    const generation = ++loadSequence.current;
    inventoryRequest.current?.abort();
    const controller = new AbortController();
    inventoryRequest.current = controller;
    const page = await inventory.load(
      () =>
        listDevices(
          {
            search: searchApplied || undefined,
            locationId: locationFilter === "all" ? undefined : locationFilter,
            status: statusFilter === "all" ? undefined : statusFilter,
            limit: 101,
            offset,
          },
          controller.signal,
        ),
      (items) => !items.length,
    );
    if (generation !== loadSequence.current || controller.signal.aborted) return false;
    return page ? evidence.load(page.slice(0, 100)) : false;
  }

  async function refreshInventory() {
    const results = await Promise.all([load(), loadReferences()]);
    return results[0];
  }

  async function loadReferences() {
    const generation = ++referencesGeneration.current;
    const results = await Promise.allSettled([
      listLocations({ limit: 200 }),
      listDeviceTypes(),
      getCurrentDeviceAccount(),
    ]);
    if (generation !== referencesGeneration.current) return;
    const [locationResult, typeResult, accountResult] = results;
    if (
      accountResult.status === "fulfilled" &&
      typeof accountResult.value.id === "string" &&
      typeof accountResult.value.userId === "string"
    )
      setOwnerAccount(accountResult.value);
    if (locationResult.status === "fulfilled") setLocations(locationResult.value);
    if (typeResult.status === "fulfilled") {
      setDeviceTypes(typeResult.value);
    }
    const unavailable = results.flatMap((result, index) =>
      result.status === "rejected"
        ? ["Locations", "Device types", "Account recovery scope"][index]
        : [],
    );
    setReferenceError(
      unavailable.length ? `${unavailable.join(", ")} unavailable. Retry these references.` : null,
    );
    setReferencesReady(true);
  }
  useEffect(() => {
    void loadReferences();
    return () => {
      referencesGeneration.current++;
      inventoryRequest.current?.abort();
    };
  }, []);
  useEffect(() => {
    void load();
    return () => inventoryRequest.current?.abort();
  }, [inventoryScope]);

  async function locationOptions({
    search,
    offset,
    limit,
    signal,
  }: {
    search: string;
    offset: number;
    limit: number;
    signal: AbortSignal;
  }) {
    const records = await listLocations({ search: search || undefined, offset, limit }, signal);
    if (signal.aborted) throw new DOMException("Obsolete locations", "AbortError");
    setLocations((current) => [
      ...new Map([...current, ...records].map((location) => [location.id, location])).values(),
    ]);
    return records.map((location) => ({ id: location.id, name: locationLabel(location) }));
  }

  async function onImportDevicesCsv() {
    if (
      !inspectImportCsv(csvText, ["name", "type", "locationId", "manufacturer", "model"]).valid ||
      isSubmitting
    )
      return;
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
          <Link className="ghost-button" to="/devices/deletions">
            Удалённые устройства
          </Link>
          <button
            className="ghost-button"
            type="button"
            onClick={() => void refreshInventory()}
            disabled={isLoading}
          >
            Refresh devices
          </button>
          <button className="ghost-button" type="button" onClick={() => setIsCsvOpen(true)}>
            Import CSV
          </button>
          <button className="ghost-button" type="button" onClick={() => void onExportDevicesCsv()}>
            Export CSV
          </button>
          <DeviceCreationWizard
            ownerAccount={ownerAccount}
            locations={locations}
            deviceTypes={deviceTypes}
            referencesReady={referencesReady}
            setLocations={setLocations}
            locationOptions={locationOptions}
            onCreated={load}
          />
        </div>
      </header>

      <section className="panel inventory-panel inventory-panel-compact">
        <DeviceInventoryControls
          search={search}
          onSearch={(value) => query.update({ q: value || null, offset: null }, true)}
          location={locationFilter}
          locations={locations.map((location) => ({
            id: location.id,
            name: locationLabel(location),
          }))}
          loadLocations={locationOptions}
          onLocation={(id) => query.update({ locationId: id || null, offset: null })}
          status={statusFilter}
          onStatus={(value) =>
            query.update({ status: value === "all" ? null : value, offset: null })
          }
          view={inventoryView}
          onView={setInventoryView}
          scope={inventoryScope}
          refresh={refreshInventory}
          onClear={() => query.update({ q: null, locationId: null, status: null, offset: null })}
        />
        <SectionFeedback
          resource={inventory}
          label="devices"
          onRetry={() => void refreshInventory()}
        />
        {applyingSearch ? <p role="status">Applying search…</p> : null}
        {referenceError ? (
          <details className="inventory-reference-warning">
            <summary>Some device options are unavailable</summary>
            <p>{referenceError}</p>
            <button type="button" className="ghost-button" onClick={() => void loadReferences()}>
              Retry references
            </button>
          </details>
        ) : null}
        {error ? (
          <div className="error-message" role="alert">
            {error}
          </div>
        ) : null}
        {!isLoading && hasInventory && devices.length === 0 ? (
          <div className="empty-state">
            <strong>No devices match these filters</strong>
            <span>Clear search or add a new device.</span>
          </div>
        ) : inventoryView === "table" ? (
          <DeviceInventoryTable
            devices={devices}
            locationById={locationById}
            typeByCode={typeByCode}
            latestByDevice={latestByDevice}
            riskByDevice={riskByDevice}
            evidence={evidence}
          />
        ) : (
          <div className="room-device-board">
            {roomGroups.map((group) => (
              <section
                className="room-section"
                key={group.locationId}
                aria-labelledby={`room-${group.locationId}`}
              >
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
                        <Link
                          className="device-icon-card"
                          to={`/devices/${device.id}`}
                          key={device.id}
                        >
                          <span
                            className={`device-icon-frame device-icon-${getDeviceTone(device.type)}`}
                            aria-hidden="true"
                          >
                            <DeviceIcon type={device.type} />
                          </span>
                          <strong>{device.name}</strong>
                          <span className="device-card-type">
                            {typeByCode.get(device.type) ?? formatEnum(device.type)}
                          </span>
                          <span className="device-card-status">{formatEnum(device.status)}</span>
                          <div className="device-card-signals">
                            <span
                              className={`status-pill status-pill-${risk?.level.toLowerCase() ?? "neutral"}`}
                            >
                              {risk
                                ? formatEnum(risk.level)
                                : !evidence.risk[device.id] ||
                                    evidence.risk[device.id].status === "loading"
                                  ? "Risk pending"
                                  : evidence.risk[device.id].status === "empty"
                                    ? "Risk not calculated"
                                    : "Risk unavailable"}
                            </span>
                            <span>
                              {latest?.readings.activePowerW !== undefined
                                ? `${formatNumber(latest.readings.activePowerW)} W`
                                : "No data"}
                            </span>
                          </div>
                          <span className="device-card-telemetry">
                            {latest
                              ? formatRelativeTime(latest.timestamp)
                              : !evidence.telemetry[device.id] ||
                                  evidence.telemetry[device.id].status === "loading"
                                ? "Telemetry pending"
                                : evidence.telemetry[device.id].status === "empty"
                                  ? "No readings"
                                  : "Telemetry unavailable"}
                          </span>
                        </Link>
                        {evidence.telemetry[device.id] ? (
                          <SectionFeedback
                            resource={evidence.telemetry[device.id]}
                            label="telemetry"
                            context={device.name}
                            onRetry={() => void evidence.reloadTelemetry(device.id)}
                          />
                        ) : null}
                        {evidence.risk[device.id] ? (
                          <SectionFeedback
                            resource={evidence.risk[device.id]}
                            label="risk"
                            context={device.name}
                            onRetry={() => void evidence.reloadRisk(device.id)}
                          />
                        ) : null}
                      </div>
                    );
                  })}
                </div>
              </section>
            ))}
          </div>
        )}
        <footer className="device-inventory-footer">
          {hasMore || offset > 0 ? (
            <PageNavigation
              offset={offset}
              size={100}
              hasMore={hasMore}
              loading={isLoading || applyingSearch}
              loaded={devices.length}
              onChange={(next) => query.update({ offset: next || null })}
            />
          ) : hasInventory ? (
            <small>{devices.length} devices on this page</small>
          ) : null}
        </footer>
      </section>

      {isCsvOpen ? (
        <Dialog
          title="Import devices"
          onClose={() => setIsCsvOpen(false)}
          dirty={csvText.trim() !== "name,type,locationId,manufacturer,model"}
          busy={isSubmitting}
          footer={
            <>
              <DialogClose />
              <button
                className="primary-button"
                type="button"
                onClick={() => void onImportDevicesCsv()}
                disabled={
                  isSubmitting ||
                  !inspectImportCsv(csvText, [
                    "name",
                    "type",
                    "locationId",
                    "manufacturer",
                    "model",
                  ]).valid
                }
              >
                {isSubmitting ? "Importing..." : "Import devices"}
              </button>
            </>
          }
        >
          <p>CSV header: name,type,locationId,manufacturer,model.</p>
          <div className="form-stack">
            <CsvImportPreview
              text={csvText}
              onChange={setCsvText}
              columns={["name", "type", "locationId", "manufacturer", "model"]}
              example="Kitchen fridge,FRIDGE,replace-with-location-id,Bosch,Example"
              busy={isSubmitting}
            />
            {error ? (
              <div className="error-message" role="alert">
                {error}
              </div>
            ) : null}
            {csvMessage ? (
              <div className="status-note" role="status">
                {csvMessage}
              </div>
            ) : null}
          </div>
        </Dialog>
      ) : null}
    </>
  );
}
