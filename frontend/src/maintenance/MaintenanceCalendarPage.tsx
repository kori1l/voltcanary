import { startOfDay, startOfWeek, addDays, addHours, defaultsForCalendarSelection, daySpan, buildTimelineSlots, parseDateInput, toDateInputValue, toDateTimeLocalValue, formatDateRange, formatShortDateTime, formatEnum, locationLabel, CreateDefaults } from "./calendarPresentation";
import { MaintenanceTimelineGrid } from "./MaintenanceTimeline";
import { MaintenanceAgenda } from "./MaintenanceAgenda";
import { MaintenanceOperationDetailsDrawer, MaintenanceOperationDrawer, MaintenanceOperationFormRequest } from "./MaintenanceDrawers";


import { useEffect, useMemo, useRef, useState } from "react";



import { useActionConfirmation } from "../components/useActionConfirmation";
import { Device, DeviceType, listDevices, listDeviceTypes, listLocations, Location } from "../devices/devicesApi";
import { cancelMaintenanceOperation, createMaintenanceOperation, createMaintenanceOutcome, finishMaintenanceOperationNow, listMaintenanceOperations, MaintenanceOutcomeType, MaintenanceOperation, updateMaintenanceOperation } from "./maintenanceApi";

type CalendarView = "timeline" | "agenda";
type DrawerMode = "create" | "edit" | null;

const statusOptions = ["all", "SCHEDULED", "ACTIVE", "FINISHED", "CANCELLED"];
const maintenanceOutcomeOptions: MaintenanceOutcomeType[] = [
  "CONFIRMED_FAILURE",
  "CORRECTIVE_MAINTENANCE",
  "PREVENTIVE_MAINTENANCE",
  "INSPECTION_NORMAL",
  "FALSE_POSITIVE",
  "SENSOR_FAULT",
  "UNRESOLVED_ANOMALY",
  "UNKNOWN",
];

export function MaintenanceCalendarPage() {
  const confirmation = useActionConfirmation();
  const today = startOfDay(new Date());
  const [view, setView] = useState<CalendarView>(() => window.matchMedia?.("(max-width: 760px)").matches ? "agenda" : "timeline");
  const [rangeStart, setRangeStart] = useState(startOfWeek(today));
  const [rangeEnd, setRangeEnd] = useState(addDays(startOfWeek(today), 7));
  const [operations, setOperations] = useState<MaintenanceOperation[]>([]);
  const [devices, setDevices] = useState<Device[]>([]);
  const [locations, setLocations] = useState<Location[]>([]);
  const [deviceTypes, setDeviceTypes] = useState<DeviceType[]>([]);
  const [search, setSearch] = useState("");
  const [locationFilter, setLocationFilter] = useState("all");
  const [typeFilter, setTypeFilter] = useState("all");
  const [statusFilter, setStatusFilter] = useState("all");
  const [includeCancelled, setIncludeCancelled] = useState(false);
  const [drawerMode, setDrawerMode] = useState<DrawerMode>(null);
  const [editingOperation, setEditingOperation] = useState<MaintenanceOperation | null>(null);
  const [createDefaults, setCreateDefaults] = useState<CreateDefaults | null>(null);
  const [selectedOperationId, setSelectedOperationId] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [hasDevices, setHasDevices] = useState(false);
  const [hasOperations, setHasOperations] = useState(false);
  const [isBusy, setIsBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const locationById = useMemo(() => new Map(locations.map((location) => [location.id, locationLabel(location)])), [locations]);
  const typeByCode = useMemo(() => new Map(deviceTypes.map((type) => [type.code, type.displayName])), [deviceTypes]);
  const selectedOperation = useMemo(() => operations.find((operation) => operation.id === selectedOperationId) ?? null, [operations, selectedOperationId]);
  const rangeDayCount = useMemo(() => daySpan(rangeStart, rangeEnd), [rangeStart, rangeEnd]);
  const timelineSlots = useMemo(() => buildTimelineSlots(rangeStart, rangeEnd), [rangeStart, rangeEnd]);

  const filteredDevices = useMemo(() => {
    const normalizedSearch = search.trim().toLowerCase();
    return devices
      .filter((device) => locationFilter === "all" || device.locationId === locationFilter)
      .filter((device) => typeFilter === "all" || device.type === typeFilter)
      .filter((device) => !normalizedSearch || `${device.name} ${device.type} ${locationById.get(device.locationId ?? "") ?? ""}`.toLowerCase().includes(normalizedSearch))
      .sort((left, right) => left.name.localeCompare(right.name));
  }, [devices, locationById, locationFilter, search, typeFilter]);

  const filteredDeviceIds = useMemo(() => new Set(filteredDevices.map((device) => device.id)), [filteredDevices]);
  const visibleOperations = useMemo(() => {
    return operations
      .filter((operation) => statusFilter === "all" || operation.status === statusFilter)
      .filter((operation) => operation.devices.some((device) => filteredDeviceIds.has(device.deviceId)))
      .sort((left, right) => new Date(left.startAt).getTime() - new Date(right.startAt).getTime());
  }, [filteredDeviceIds, operations, statusFilter]);

  useEffect(() => {
    void load();
  }, [rangeStart.getTime(), rangeEnd.getTime(), includeCancelled]);

  const loadSequence = useRef(0);
  async function load() {
    const sequence = ++loadSequence.current;
    setIsLoading(true);
    setError(null);
    try {
      const [operationResponse, deviceResponse, locationResponse, typeResponse] = await Promise.allSettled([
        listMaintenanceOperations({
          from: rangeStart.toISOString(),
          to: rangeEnd.toISOString(),
          includeCancelled,
          limit: 200,
          offset: 0,
        }),
        listDevices({ limit: 200, offset: 0 }),
        listLocations({ limit: 200, offset: 0 }),
        listDeviceTypes(),
      ]);
      if (sequence !== loadSequence.current) return;
      if (operationResponse.status === "fulfilled") {
        setHasOperations(true);
        setOperations(operationResponse.value);
        if (selectedOperationId && !operationResponse.value.some((operation) => operation.id === selectedOperationId)) setSelectedOperationId(null);
      }
      if (deviceResponse.status === "fulfilled") { setDevices(deviceResponse.value); setHasDevices(true); }
      if (locationResponse.status === "fulfilled") setLocations(locationResponse.value);
      if (typeResponse.status === "fulfilled") setDeviceTypes(typeResponse.value);
      const failures = [operationResponse, deviceResponse, locationResponse, typeResponse].flatMap((result, index) => result.status === "rejected" ? [["Operations", "Devices", "Locations", "Device types"][index]] : []);
      if (failures.length) setError(`${failures.join(", ")} unavailable. Retained sections may be stale; counts represent only loaded records.`);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to load maintenance calendar");
    } finally {
      if (sequence === loadSequence.current) setIsLoading(false);
    }
  }

  function openCreateDrawer(defaults?: CreateDefaults) {
    setSelectedOperationId(null);
    setEditingOperation(null);
    setCreateDefaults(defaults ?? null);
    setDrawerMode("create");
  }

  function openEditDrawer(operation: MaintenanceOperation) {
    setSelectedOperationId(operation.id);
    setEditingOperation(operation);
    setDrawerMode("edit");
  }

  function closeFormDrawer() {
    setDrawerMode(null);
    setEditingOperation(null);
    setCreateDefaults(null);
  }

  async function saveOperation(request: MaintenanceOperationFormRequest) {
    setIsBusy(true);
    setMessage(null);
    setError(null);
    try {
      const wasEditing = Boolean(editingOperation);
      const payload = {
        ...request,
        description: request.description.trim() || null,
        startAt: new Date(request.startAt).toISOString(),
        endAt: new Date(request.endAt).toISOString(),
      };
      const saved = editingOperation ? await updateMaintenanceOperation(editingOperation.id, payload) : await createMaintenanceOperation(payload);
      setMessage(wasEditing ? "Maintenance operation updated." : "Maintenance operation scheduled.");
      setSelectedOperationId(wasEditing ? saved.id : null);
      closeFormDrawer();
      await load();
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to save maintenance operation");
    } finally {
      setIsBusy(false);
    }
  }

  async function cancelOperation(operation: MaintenanceOperation) {
    if (isBusy) return;
    const reason = await confirmation.confirm({ title: "Cancel maintenance", object: operation.title, impact: "Cancel the scheduled windows for every device in this operation. Alert suppression and ML exclusions end with those windows.", reasonRequired: true });
    if (reason === null) return;
    setIsBusy(true);
    setError(null);
    try {
      const updated = await cancelMaintenanceOperation(operation.id, reason);
      setSelectedOperationId(updated.id);
      await load();
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to cancel maintenance operation");
    } finally {
      setIsBusy(false);
    }
  }

  async function finishOperation(operation: MaintenanceOperation) {
    if (isBusy || await confirmation.confirm({ title: "Finish maintenance now", object: operation.title, impact: "End all active windows in this operation at the current time. Record observed outcomes separately." }) === null) return;
    setIsBusy(true);
    setError(null);
    try {
      const updated = await finishMaintenanceOperationNow(operation.id);
      setSelectedOperationId(updated.id);
      await load();
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to finish maintenance operation");
    } finally {
      setIsBusy(false);
    }
  }

  async function recordOutcome(operation: MaintenanceOperation, request: Parameters<typeof createMaintenanceOutcome>[1]) {
    setIsBusy(true);
    setError(null);
    try {
      await createMaintenanceOutcome(operation.id, request);
      setMessage("Maintenance outcome recorded.");
      await load();
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to record maintenance outcome");
    } finally {
      setIsBusy(false);
    }
  }

  function shiftRange(direction: -1 | 1) {
    const span = Math.max(1, rangeDayCount);
    setRangeStart(addDays(rangeStart, span * direction));
    setRangeEnd(addDays(rangeEnd, span * direction));
  }

  function setTodayRange() {
    const span = Math.max(1, rangeDayCount);
    const start = startOfDay(new Date());
    setRangeStart(start);
    setRangeEnd(addDays(start, span));
  }

  function setStartFromInput(value: string) {
    const nextStart = parseDateInput(value);
    if (!nextStart) return;
    setRangeStart(nextStart);
    if (rangeEnd <= nextStart) {
      setRangeEnd(addDays(nextStart, 1));
    }
  }

  function setEndFromInput(value: string) {
    const inclusiveEnd = parseDateInput(value);
    if (!inclusiveEnd) return;
    const nextEnd = addDays(inclusiveEnd, 1);
    if (nextEnd > rangeStart) {
      setRangeEnd(nextEnd);
    }
  }

  return (
    <>
      {confirmation.dialog}
      <header className="page-header page-header-row maintenance-page-header">
        <div>
          <h1>Maintenance</h1>
          <p>Plan service windows across any fleet interval and control downstream suppression.</p>
        </div>
        <div className="header-actions">
          <button className="ghost-button" type="button" onClick={() => shiftRange(-1)}>
            Previous
          </button>
          <button className="ghost-button" type="button" onClick={setTodayRange}>
            Today
          </button>
          <button className="ghost-button" type="button" onClick={() => shiftRange(1)}>
            Next
          </button>
          <select aria-label="Calendar view" value={view} onChange={(event) => setView(event.target.value as CalendarView)}>
            <option value="timeline">Timeline</option>
            <option value="agenda">Agenda</option>
          </select>
          <button className="ghost-button" type="button" onClick={() => void load()} disabled={isLoading}>
            Refresh
          </button>
          <button className="primary-button" type="button" disabled={!hasDevices} onClick={() => openCreateDrawer()}>
            Schedule maintenance
          </button>
        </div>
      </header>

      <section className="maintenance-workspace">
        <aside className="maintenance-filter-rail">
          <label>
            Search
            <input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Device, type, location" />
          </label>
          <div className="maintenance-range-filter">
            <label>
              From
              <input type="date" value={toDateInputValue(rangeStart)} onChange={(event) => setStartFromInput(event.target.value)} />
            </label>
            <label>
              To
              <input type="date" value={toDateInputValue(addDays(rangeEnd, -1))} onChange={(event) => setEndFromInput(event.target.value)} />
            </label>
          </div>
          <label>
            Location
            <select value={locationFilter} onChange={(event) => setLocationFilter(event.target.value)}>
              <option value="all">All locations</option>
              {locations.map((location) => (
                <option value={location.id} key={location.id}>
                  {locationLabel(location)}
                </option>
              ))}
            </select>
          </label>
          <label>
            Type
            <select value={typeFilter} onChange={(event) => setTypeFilter(event.target.value)}>
              <option value="all">All types</option>
              {deviceTypes.map((type) => (
                <option value={type.code} key={type.code}>
                  {type.displayName}
                </option>
              ))}
            </select>
          </label>
          <label>
            Status
            <select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)}>
              {statusOptions.map((status) => (
                <option value={status} key={status}>
                  {status === "all" ? "All statuses" : formatEnum(status)}
                </option>
              ))}
            </select>
          </label>
          <label className="checkbox-row">
            <input type="checkbox" checked={includeCancelled} onChange={(event) => setIncludeCancelled(event.target.checked)} />
            Show cancelled
          </label>
          <div className="maintenance-legend">
            <span className="maintenance-legend-item"><i className="legend-dot legend-active" /> Active</span>
            <span className="maintenance-legend-item"><i className="legend-dot legend-scheduled" /> Scheduled</span>
            <span className="maintenance-legend-item"><i className="legend-dot legend-finished" /> Finished</span>
            <span className="maintenance-legend-item"><i className="legend-dot legend-cancelled" /> Cancelled</span>
          </div>
        </aside>

        <section className="maintenance-calendar-panel">
          <div className="maintenance-calendar-title">
            <div>
              <h2>{formatDateRange(rangeStart, addDays(rangeEnd, -1))}</h2>
              <span>{hasOperations ? visibleOperations.length : "Unavailable"} operations, {hasDevices ? filteredDevices.length : "Unavailable"} devices, {rangeDayCount} days, {timelineSlots.length} columns</span>
            </div>
            {message ? <span className="success-message compact-message">{message}</span> : null}
          </div>
          {error ? <div className="error-message">{error}</div> : null}
          {isLoading ? <div className="empty-state">Loading maintenance calendar...</div> : null}
          {!isLoading && hasDevices && view === "timeline" ? (
            <>
            <MaintenanceTimelineGrid
              devices={filteredDevices}
              operations={visibleOperations}
              rangeStart={rangeStart}
              rangeEnd={rangeEnd}
              slots={timelineSlots}
              locationById={locationById}
              typeByCode={typeByCode}
              selectedOperationId={selectedOperationId}
              onSelect={setSelectedOperationId}
              onCreate={(deviceId, start, end) => openCreateDrawer(defaultsForCalendarSelection(deviceId, start, end))}
              onCancel={cancelOperation}
              onFinish={finishOperation}
              isBusy={isBusy}
            />
            <section className="maintenance-timeline-list" aria-label="Timeline operations"><p className="muted-text">Short windows use a minimum marker width. Exact intervals and titles are listed here.</p>{visibleOperations.map((operation) => <button type="button" className="ghost-button maintenance-timeline-label" key={operation.id} onClick={() => setSelectedOperationId(operation.id)}><strong>{operation.title}</strong><span>{formatShortDateTime(operation.startAt)} – {formatShortDateTime(operation.endAt)} · {formatEnum(operation.status)}</span></button>)}</section>
            </>
          ) : null}
          {!isLoading && hasOperations && view === "agenda" ? (
            <MaintenanceAgenda
              operations={visibleOperations}
              selectedOperationId={selectedOperationId}
              onSelect={setSelectedOperationId}
              onCancel={cancelOperation}
              onFinish={finishOperation}
              isBusy={isBusy}
            />
          ) : null}
        </section>
      </section>

      {selectedOperation && !drawerMode ? (
        <MaintenanceOperationDetailsDrawer
          operation={selectedOperation}
          onClose={() => setSelectedOperationId(null)}
          onEdit={() => openEditDrawer(selectedOperation)}
          onCancel={() => void cancelOperation(selectedOperation)}
          onFinish={() => void finishOperation(selectedOperation)}
          onOutcomeSaved={() => void load()}
          isBusy={isBusy}
        />
      ) : null}

      {drawerMode ? (
        <MaintenanceOperationDrawer
          mode={drawerMode}
          error={error}
          operation={editingOperation}
          devices={devices}
          locations={locations}
          typeByCode={typeByCode}
          operations={operations}
          defaultStart={createDefaults?.startAt ?? toDateTimeLocalValue(rangeStart)}
          defaultEnd={createDefaults?.endAt ?? toDateTimeLocalValue(addHours(rangeStart, 2))}
          defaultDeviceIds={createDefaults?.deviceIds ?? []}
          isBusy={isBusy}
          onClose={closeFormDrawer}
          onSubmit={saveOperation}
        />
      ) : null}
    </>
  );
}
