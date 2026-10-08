import { startOfDay, startOfWeek, addDays, addHours, defaultsForCalendarSelection, daySpan, buildTimelineSlots, parseDateInput, toDateInputValue, toDateTimeLocalValue, formatDateRange, formatShortDateTime, formatEnum, locationLabel, CreateDefaults } from "./calendarPresentation";
import { MaintenanceTimelineGrid } from "./MaintenanceTimeline";
import { MaintenanceAgenda } from "./MaintenanceAgenda";
import { MaintenanceOperationDetailsDrawer, MaintenanceOperationDrawer, MaintenanceOperationFormRequest } from "./MaintenanceDrawers";


import { useEffect, useMemo, useRef, useState } from "react";



import { useActionConfirmation } from "../components/useActionConfirmation";
import { ConsoleRouteContext, useConsoleQuery } from "../app/useConsoleQuery";
import { useSectionResource } from "../components/useSectionResource";
import { SectionFeedback } from "../components/SectionFeedback";
import { PageNavigation } from "../components/PageNavigation";
import { EntitySelector } from "../components/EntitySelector";
import { useDebouncedValue } from "../components/useDebouncedValue";
import { useTaskEvidence } from "../aiAgent/taskEvidence";
import { Device, DeviceType, listDevices, listDeviceTypes, listLocations, Location } from "../devices/devicesApi";
import { cancelMaintenanceOperation, createMaintenanceOperation, createMaintenanceOutcome, finishMaintenanceOperationNow, getMaintenanceOperation, listMaintenanceOperations, MaintenanceOutcomeType, MaintenanceOperation, updateMaintenanceOperation } from "./maintenanceApi";

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
  return <ConsoleRouteContext fallbackPath="/maintenance"><MaintenanceCalendarScreen /></ConsoleRouteContext>;
}
function MaintenanceCalendarScreen() {
  const query = useConsoleQuery();
  const confirmation = useActionConfirmation();
  const today = startOfDay(new Date());
  const [view, setView] = query.state<CalendarView>("view", window.matchMedia?.("(max-width: 760px)").matches ? "agenda" : "timeline");
  const [rangeStart, setRangeStart] = query.dateState("from", startOfWeek(today));
  const [rangeEnd, setRangeEnd] = query.dateState("to", addDays(startOfWeek(today), 7));
  const [devices, setDevices] = useState<Device[]>([]);
  const [locations, setLocations] = useState<Location[]>([]);
  const [deviceTypes, setDeviceTypes] = useState<DeviceType[]>([]);
  const [search, setSearch] = query.state<string>("q", "");
  const [locationFilter, setLocationFilter] = query.state<string>("locationId", "all");
  const [typeFilter, setTypeFilter] = query.state<string>("deviceType", "all");
  const [statusFilter, setStatusFilter] = query.state<string>("status", "all");
  const [includeCancelled, setIncludeCancelled] = query.flagState("includeCancelled");
  const [offsetValue] = query.state<string>("offset", "0");
  const offset = Number(offsetValue);
  const appliedSearch = useDebouncedValue(search.trim());
  const operationScope = JSON.stringify([rangeStart.toISOString(), rangeEnd.toISOString(), includeCancelled, locationFilter, statusFilter, offset]);
  const operationSection = useSectionResource<MaintenanceOperation[]>(operationScope);
  const operations = (operationSection.data ?? []).slice(0, 100);
  const hasMoreOperations = (operationSection.data?.length ?? 0) > 100;
  const operationRequest = useRef<AbortController | null>(null);
  const referencesGeneration = useRef(0);
  const [hasMoreDevices, setHasMoreDevices] = useState(false);
  const [isLoadingRoster, setIsLoadingRoster] = useState(false);
  const [referenceError, setReferenceError] = useState<string | null>(null);
  const rosterOffset = useRef(0);
  const hasOperations = operationSection.data !== null;
  const [drawerMode, setDrawerMode] = useState<DrawerMode>(null);
  const [editingOperation, setEditingOperation] = useState<MaintenanceOperation | null>(null);
  const [createDefaults, setCreateDefaults] = useState<CreateDefaults | null>(null);
  const [selectedOperationId, setSelectedOperationId] = query.state<string | null>("operationId", null);
  const [isLoading, setIsLoading] = useState(true);
  const [hasDevices, setHasDevices] = useState(false);
  const [isBusy, setIsBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const locationById = useMemo(() => new Map(locations.map((location) => [location.id, locationLabel(location)])), [locations]);
  const typeByCode = useMemo(() => new Map(deviceTypes.map((type) => [type.code, type.displayName])), [deviceTypes]);
  const selectedResource = useSectionResource<MaintenanceOperation>(selectedOperationId ?? "none");
  const selectedOperation = operations.find((operation) => operation.id === selectedOperationId) ?? selectedResource.data;
  useEffect(() => { if (!selectedOperationId || operations.some((operation) => operation.id === selectedOperationId)) return; const controller = new AbortController(); void selectedResource.load(() => getMaintenanceOperation(selectedOperationId, controller.signal)); return () => controller.abort(); }, [selectedOperationId, operationScope, selectedResource.load]);
  useTaskEvidence({ names: { operation: selectedOperation?.title, location: locations.find((location) => location.id === locationFilter)?.name }, interval: { from: rangeStart.toISOString(), to: rangeEnd.toISOString() } });
  const rangeDayCount = useMemo(() => daySpan(rangeStart, rangeEnd), [rangeStart, rangeEnd]);
  const timelineSlots = useMemo(() => buildTimelineSlots(rangeStart, rangeEnd), [rangeStart, rangeEnd]);

  const filteredDevices = useMemo(() => {
    const normalizedSearch = appliedSearch.toLowerCase();
    return devices
      .filter((device) => locationFilter === "all" || device.locationId === locationFilter)
      .filter((device) => typeFilter === "all" || device.type === typeFilter)
      .filter((device) => !normalizedSearch || `${device.name} ${device.type} ${locationById.get(device.locationId ?? "") ?? ""}`.toLowerCase().includes(normalizedSearch))
      .sort((left, right) => left.name.localeCompare(right.name));
  }, [devices, locationById, locationFilter, appliedSearch, typeFilter]);

  const visibleOperations = useMemo(() => operations.filter((operation) => {
    if (statusFilter !== "all" && operation.status !== statusFilter) return false;
    if (locationFilter === "all" && typeFilter === "all" && !appliedSearch) return true;
    return operation.devices.some((device) => (locationFilter === "all" || device.locationId === locationFilter) && (typeFilter === "all" || device.deviceType === typeFilter) && (!appliedSearch || `${device.deviceName} ${device.deviceType ?? ""} ${device.locationName ?? ""}`.toLowerCase().includes(appliedSearch.toLowerCase())));
  }).sort((left, right) => new Date(left.startAt).getTime() - new Date(right.startAt).getTime()), [operations, statusFilter, locationFilter, typeFilter, appliedSearch]);

  const loadSequence = useRef(0);
  async function load() {
    const sequence = ++loadSequence.current;
    operationRequest.current?.abort();
    const controller = new AbortController(); operationRequest.current = controller;
    setIsLoading(true);
    await operationSection.load(() => listMaintenanceOperations({ from: rangeStart.toISOString(), to: rangeEnd.toISOString(), includeCancelled, locationId: locationFilter === "all" ? undefined : locationFilter, status: statusFilter === "all" ? undefined : statusFilter, limit: 101, offset }, controller.signal), (items) => !items.length);
    if (sequence === loadSequence.current) setIsLoading(false);
  }
  async function loadReferences() {
    const sequence = ++referencesGeneration.current;
    const results = await Promise.allSettled([listDevices({ limit: 101 }), listLocations({ limit: 101 }), listDeviceTypes()]);
    if (sequence !== referencesGeneration.current) return;
    const [deviceResult, locationResult, typeResult] = results;
    if (deviceResult.status === "fulfilled") { setDevices(deviceResult.value.slice(0, 100)); rosterOffset.current = 100; setHasMoreDevices(deviceResult.value.length > 100); setHasDevices(true); }
    if (locationResult.status === "fulfilled") setLocations(locationResult.value.slice(0, 100));
    if (typeResult.status === "fulfilled") setDeviceTypes(typeResult.value);
    const failures = results.flatMap((result, index) => result.status === "rejected" ? [["Devices", "Locations", "Device types"][index]] : []);
    setReferenceError(failures.length ? `${failures.join(", ")} unavailable. Retry references; loaded counts do not represent the whole fleet.` : null);
  }
  async function loadMoreDevices() {
    if (isLoadingRoster || !hasMoreDevices) return;
    setIsLoadingRoster(true);
    try { const records = await listDevices({ limit: 101, offset: rosterOffset.current }); setDevices((current) => [...new Map([...current, ...records.slice(0, 100)].map((device) => [device.id, device])).values()]); rosterOffset.current += 100; setHasMoreDevices(records.length > 100); }
    catch { setReferenceError("More device choices unavailable. Retry loading the roster."); } finally { setIsLoadingRoster(false); }
  }
  async function locationOptions({ search, offset, limit, signal }: { search: string; offset: number; limit: number; signal: AbortSignal }) {
    const records = await listLocations({ search: search || undefined, offset, limit }, signal);
    if (signal.aborted) throw new DOMException("Obsolete locations", "AbortError");
    setLocations((current) => [...new Map([...current, ...records].map((location) => [location.id, location])).values()]);
    return records.map((location) => ({ id: location.id, name: locationLabel(location) }));
  }
  useEffect(() => { void loadReferences(); return () => { referencesGeneration.current++; operationRequest.current?.abort(); }; }, []);
  useEffect(() => { void load(); return () => operationRequest.current?.abort(); }, [operationScope]);

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
    query.update({ offset: null });
    const span = Math.max(1, rangeDayCount);
    setRangeStart(addDays(rangeStart, span * direction));
    setRangeEnd(addDays(rangeEnd, span * direction));
  }

  function setTodayRange() {
    query.update({ offset: null });
    const span = Math.max(1, rangeDayCount);
    const start = startOfDay(new Date());
    setRangeStart(start);
    setRangeEnd(addDays(start, span));
  }

  function setStartFromInput(value: string) {
    query.update({ offset: null });
    const nextStart = parseDateInput(value);
    if (!nextStart) return;
    setRangeStart(nextStart);
    if (rangeEnd <= nextStart) {
      setRangeEnd(addDays(nextStart, 1));
    }
  }

  function setEndFromInput(value: string) {
    query.update({ offset: null });
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
            <input value={search} onChange={(event) => query.update({ q: event.target.value || null, offset: null }, true)} placeholder="Device, type, location" />
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
          <EntitySelector label="Location" loadOptions={locationOptions} options={locations.map((location) => ({ id: location.id, name: locationLabel(location) }))} value={locationFilter === "all" ? "" : locationFilter} onChange={(id) => query.update({ locationId: id || null, offset: null })} />
          <label>
            Type
            <select value={typeFilter} onChange={(event) => query.update({ deviceType: event.target.value === "all" ? null : event.target.value, offset: null })}>
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
            <select value={statusFilter} onChange={(event) => query.update({ status: event.target.value === "all" ? null : event.target.value, offset: null })}>
              {statusOptions.map((status) => (
                <option value={status} key={status}>
                  {status === "all" ? "All statuses" : formatEnum(status)}
                </option>
              ))}
            </select>
          </label>
          <label className="checkbox-row">
            <input type="checkbox" checked={includeCancelled} onChange={(event) => query.update({ includeCancelled: event.target.checked, offset: null })} />
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
              <span>{hasOperations ? visibleOperations.length : "Unavailable"} loaded page operations, {hasDevices ? filteredDevices.length : "Unavailable"} loaded devices, {rangeDayCount} days, {timelineSlots.length} columns</span>
            </div>
            {message ? <span className="success-message compact-message">{message}</span> : null}
          </div>
          {error ? <div className="error-message">{error}</div> : null}
          <SectionFeedback resource={operationSection} label="maintenance operations" onRetry={() => void load()} />
          <PageNavigation offset={offset} size={100} hasMore={hasMoreOperations} loading={isLoading} loaded={visibleOperations.length} onChange={(next) => query.update({ offset: next || null })} />
          <p className="muted-text">Search/type filter the loaded operation page and roster; continue through pages for later records. Total unknown.</p>
          <button type="button" className="ghost-button" onClick={() => query.update({ q: null, locationId: null, deviceType: null, status: null, includeCancelled: false, offset: null })}>Clear filters</button>
          {hasMoreDevices ? <button type="button" className="ghost-button" disabled={isLoadingRoster} onClick={() => void loadMoreDevices()}>Load more devices</button> : null}
          {referenceError ? <div className="error-message">{referenceError}<button type="button" className="ghost-button" onClick={() => void loadReferences()}>Retry references</button></div> : null}
          {selectedOperationId && !selectedOperation ? <SectionFeedback resource={selectedResource} label="selected operation" onRetry={() => void selectedResource.load(() => getMaintenanceOperation(selectedOperationId))} /> : null}
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
