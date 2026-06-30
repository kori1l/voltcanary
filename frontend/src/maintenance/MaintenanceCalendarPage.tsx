import { FormEvent, KeyboardEvent, MouseEvent as ReactMouseEvent, useEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { Device, DeviceType, listDevices, listDeviceTypes, listLocations, Location } from "../devices/devicesApi";
import {
  cancelMaintenanceOperation,
  createMaintenanceOperation,
  finishMaintenanceOperationNow,
  listMaintenanceOperations,
  MaintenanceOperation,
  updateMaintenanceOperation,
} from "./maintenanceApi";

type CalendarView = "timeline" | "agenda";
type DrawerMode = "create" | "edit" | null;

const statusOptions = ["all", "SCHEDULED", "ACTIVE", "FINISHED", "CANCELLED"];

interface CreateDefaults {
  startAt: string;
  endAt: string;
  deviceIds: string[];
}

interface TimelineSlot {
  start: Date;
  end: Date;
  label: string;
  subLabel: string;
}

export function MaintenanceCalendarPage() {
  const today = startOfDay(new Date());
  const [view, setView] = useState<CalendarView>("timeline");
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

  async function load() {
    setIsLoading(true);
    setError(null);
    try {
      const [operationResponse, deviceResponse, locationResponse, typeResponse] = await Promise.all([
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
      setOperations(operationResponse);
      setDevices(deviceResponse);
      setLocations(locationResponse);
      setDeviceTypes(typeResponse);
      if (selectedOperationId && !operationResponse.some((operation) => operation.id === selectedOperationId)) {
        setSelectedOperationId(null);
      }
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to load maintenance calendar");
    } finally {
      setIsLoading(false);
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
    setIsBusy(true);
    setError(null);
    try {
      const updated = await cancelMaintenanceOperation(operation.id, "Cancelled from maintenance calendar.");
      setSelectedOperationId(updated.id);
      await load();
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to cancel maintenance operation");
    } finally {
      setIsBusy(false);
    }
  }

  async function finishOperation(operation: MaintenanceOperation) {
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
          <select value={view} onChange={(event) => setView(event.target.value as CalendarView)}>
            <option value="timeline">Timeline</option>
            <option value="agenda">Agenda</option>
          </select>
          <button className="ghost-button" type="button" onClick={() => void load()} disabled={isLoading}>
            Refresh
          </button>
          <button className="primary-button" type="button" onClick={openCreateDrawer}>
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
              <span>{visibleOperations.length} operations, {filteredDevices.length} devices, {rangeDayCount} days, {timelineSlots.length} columns</span>
            </div>
            {message ? <span className="success-message compact-message">{message}</span> : null}
          </div>
          {error ? <div className="error-message">{error}</div> : null}
          {isLoading ? <div className="empty-state">Loading maintenance calendar...</div> : null}
          {!isLoading && view === "timeline" ? (
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
          ) : null}
          {!isLoading && view === "agenda" ? (
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
          isBusy={isBusy}
        />
      ) : null}

      {drawerMode ? (
        <MaintenanceOperationDrawer
          mode={drawerMode}
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

function MaintenanceTimelineGrid({
  devices,
  operations,
  rangeStart,
  rangeEnd,
  slots,
  locationById,
  typeByCode,
  selectedOperationId,
  onSelect,
  onCreate,
  onCancel,
  onFinish,
  isBusy,
}: {
  devices: Device[];
  operations: MaintenanceOperation[];
  rangeStart: Date;
  rangeEnd: Date;
  slots: TimelineSlot[];
  locationById: Map<string, string>;
  typeByCode: Map<string, string>;
  selectedOperationId: string | null;
  onSelect: (operationId: string) => void;
  onCreate: (deviceId: string, start: Date, end: Date) => void;
  onCancel: (operation: MaintenanceOperation) => void;
  onFinish: (operation: MaintenanceOperation) => void;
  isBusy: boolean;
}) {
  const [selection, setSelection] = useState<{ deviceId: string; startIndex: number; endIndex: number } | null>(null);
  const selectionRef = useRef<{ deviceId: string; startIndex: number; endIndex: number } | null>(null);
  const operationsByDevice = new Map<string, MaintenanceOperation[]>();
  operations.forEach((operation) => {
    operation.devices.forEach((device) => {
      const current = operationsByDevice.get(device.deviceId) ?? [];
      current.push(operation);
      operationsByDevice.set(device.deviceId, current);
    });
  });

  useEffect(() => {
    function finishWindowSelection() {
      const current = selectionRef.current;
      if (current) {
        completeSelection(current.deviceId, current.startIndex, current.endIndex);
      }
    }
    window.addEventListener("mouseup", finishWindowSelection);
    return () => window.removeEventListener("mouseup", finishWindowSelection);
  }, [slots]);

  if (devices.length === 0) {
    return (
      <div className="empty-state">
        <strong>No devices match these filters</strong>
        <span>Adjust filters or add devices first.</span>
      </div>
    );
  }

  return (
    <div className="maintenance-week-grid maintenance-timeline-grid" style={{ gridTemplateColumns: `250px repeat(${slots.length}, minmax(${columnMinWidth(slots.length)}px, 1fr))` }}>
      <div className="maintenance-grid-header maintenance-device-heading">Device</div>
      {slots.map((slot) => (
        <div className="maintenance-grid-header" key={slot.start.toISOString()}>
          <strong>{slot.label}</strong>
          <span>{slot.subLabel}</span>
        </div>
      ))}
      {devices.map((device) => {
        const rowOperations = operationsByDevice.get(device.id) ?? [];
        return (
          <div className="maintenance-grid-row" key={device.id}>
            <Link className="maintenance-device-cell" to={`/devices/${device.id}?tab=maintenance`}>
              <strong>{device.name}</strong>
              <span>{typeByCode.get(device.type) ?? formatEnum(device.type)}</span>
              <small>{device.locationId ? locationById.get(device.locationId) ?? "Unknown location" : "No location"}</small>
            </Link>
            <div
              className="maintenance-row-timeline"
              style={{ gridTemplateColumns: `repeat(${slots.length}, minmax(${columnMinWidth(slots.length)}px, 1fr))` }}
              onMouseDown={(event) => beginSelection(event, device.id)}
              onMouseMove={(event) => updateSelection(event, device.id)}
              onMouseUp={(event) => finishSelection(event, device.id)}
            >
              {slots.map((slot, slotIndex) => {
                const isSelected = selection?.deviceId === device.id && slotIndex >= Math.min(selection.startIndex, selection.endIndex) && slotIndex <= Math.max(selection.startIndex, selection.endIndex);
                return (
                <button
                  className={`maintenance-day-lane${isSelected ? " maintenance-day-lane-selected" : ""}`}
                  key={slot.start.toISOString()}
                  type="button"
                  onKeyDown={(event) => {
                    if (event.key === "Enter" || event.key === " ") {
                      event.preventDefault();
                      onCreate(device.id, slot.start, slot.end);
                    }
                  }}
                  aria-label={`Select maintenance range for ${device.name} from ${slot.start.toLocaleDateString()}`}
                >
                  <span>+</span>
                </button>
                );
              })}
              {rowOperations.map((operation) => (
                <MaintenanceOperationBlock
                  operation={operation}
                  key={`${device.id}-${operation.id}`}
                  rangeStart={rangeStart}
                  rangeEnd={rangeEnd}
                  selected={operation.id === selectedOperationId}
                  onSelect={onSelect}
                  onCancel={onCancel}
                  onFinish={onFinish}
                  isBusy={isBusy}
                />
              ))}
            </div>
          </div>
        );
      })}
    </div>
  );

  function beginSelection(event: ReactMouseEvent<HTMLDivElement>, deviceId: string) {
    if (event.button !== 0 || isOperationEventTarget(event.target)) return;
    event.preventDefault();
    const slotIndex = slotIndexFromPointer(event, slots.length);
    const next = { deviceId, startIndex: slotIndex, endIndex: slotIndex };
    selectionRef.current = next;
    setSelection(next);
  }

  function updateSelection(event: ReactMouseEvent<HTMLDivElement>, deviceId: string) {
    let current = selectionRef.current;
    if (!current && event.buttons === 1 && !isOperationEventTarget(event.target)) {
      const slotIndex = slotIndexFromPointer(event, slots.length);
      current = { deviceId, startIndex: slotIndex, endIndex: slotIndex };
      selectionRef.current = current;
      setSelection(current);
      return;
    }
    if (!current || current.deviceId !== deviceId) {
      return;
    }
    const slotIndex = slotIndexFromPointer(event, slots.length);
    const next = { ...current, endIndex: slotIndex };
    selectionRef.current = next;
    setSelection(next);
  }

  function finishSelection(event: ReactMouseEvent<HTMLDivElement>, deviceId: string) {
    const current = selectionRef.current;
    if (!current || current.deviceId !== deviceId) {
      return;
    }
    const slotIndex = slotIndexFromPointer(event, slots.length);
    const finalEndIndex = current.endIndex === current.startIndex ? slotIndex : current.endIndex;
    completeSelection(deviceId, current.startIndex, finalEndIndex);
  }

  function slotIndexFromPointer(event: ReactMouseEvent<HTMLDivElement>, slotCount: number) {
    const rect = event.currentTarget.getBoundingClientRect();
    const x = Math.min(Math.max(event.clientX - rect.left, 0), rect.width - 1);
    return Math.min(slotCount - 1, Math.max(0, Math.floor((x / rect.width) * slotCount)));
  }

  function isOperationEventTarget(target: EventTarget) {
    return target instanceof HTMLElement && Boolean(target.closest(".maintenance-operation-block"));
  }

  function completeSelection(deviceId: string, rawStartIndex: number, rawEndIndex: number) {
    const startIndex = Math.min(rawStartIndex, rawEndIndex);
    const endIndex = Math.max(rawStartIndex, rawEndIndex);
    const start = slots[startIndex]?.start;
    const end = slots[endIndex]?.end;
    selectionRef.current = null;
    setSelection(null);
    if (start && end) {
      onCreate(deviceId, start, end);
    }
  }
}

function MaintenanceOperationBlock({
  operation,
  rangeStart,
  rangeEnd,
  selected,
  onSelect,
  onCancel,
  onFinish,
  isBusy,
}: {
  operation: MaintenanceOperation;
  rangeStart: Date;
  rangeEnd: Date;
  selected: boolean;
  onSelect: (operationId: string) => void;
  onCancel: (operation: MaintenanceOperation) => void;
  onFinish: (operation: MaintenanceOperation) => void;
  isBusy: boolean;
}) {
  const start = new Date(operation.startAt);
  const end = new Date(operation.endAt);
  const rangeMs = rangeEnd.getTime() - rangeStart.getTime();
  const left = Math.max(0, ((start.getTime() - rangeStart.getTime()) / rangeMs) * 100);
  const right = Math.min(100, ((end.getTime() - rangeStart.getTime()) / rangeMs) * 100);
  const width = Math.max(2, right - left);

  function keySelect(event: KeyboardEvent<HTMLElement>) {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      onSelect(operation.id);
    }
  }

  return (
    <article
      className={`maintenance-operation-block maintenance-operation-${operation.status.toLowerCase()}${selected ? " maintenance-operation-selected" : ""}`}
      style={{ left: `${left}%`, width: `${width}%` }}
      role="button"
      tabIndex={0}
      onClick={() => onSelect(operation.id)}
      onKeyDown={keySelect}
      aria-label={`${operation.title} ${formatEnum(operation.status)}`}
    >
      <div className="maintenance-operation-content">
        <strong>{operation.title}</strong>
        <span>{formatTimeRange(start, end)}</span>
        <span>{operation.devices.length} device{operation.devices.length === 1 ? "" : "s"}</span>
      </div>
      <div className="maintenance-operation-actions">
        <span className={`status-pill status-pill-${operation.status.toLowerCase()}`}>{formatEnum(operation.status)}</span>
        {operation.status === "ACTIVE" ? (
          <button type="button" onClick={(event) => { event.stopPropagation(); void onFinish(operation); }} disabled={isBusy}>
            Finish
          </button>
        ) : null}
        {operation.status === "ACTIVE" || operation.status === "SCHEDULED" ? (
          <button type="button" onClick={(event) => { event.stopPropagation(); void onCancel(operation); }} disabled={isBusy}>
            Cancel
          </button>
        ) : null}
      </div>
    </article>
  );
}

function MaintenanceAgenda({
  operations,
  selectedOperationId,
  onSelect,
  onCancel,
  onFinish,
  isBusy,
}: {
  operations: MaintenanceOperation[];
  selectedOperationId: string | null;
  onSelect: (operationId: string) => void;
  onCancel: (operation: MaintenanceOperation) => void;
  onFinish: (operation: MaintenanceOperation) => void;
  isBusy: boolean;
}) {
  if (operations.length === 0) {
    return (
      <div className="empty-state">
        <strong>No maintenance operations in this range</strong>
        <span>Schedule maintenance or move to another range.</span>
      </div>
    );
  }

  return (
    <div className="maintenance-agenda">
      <div className="maintenance-agenda-head">
        <span>Status</span>
        <span>Title</span>
        <span>Devices</span>
        <span>Window</span>
        <span>Policies</span>
        <span>Actions</span>
      </div>
      {operations.map((operation) => (
        <article
          className={`maintenance-agenda-row${operation.id === selectedOperationId ? " maintenance-agenda-row-selected" : ""}`}
          key={operation.id}
          role="button"
          tabIndex={0}
          onClick={() => onSelect(operation.id)}
          onKeyDown={(event) => {
            if (event.key === "Enter" || event.key === " ") {
              event.preventDefault();
              onSelect(operation.id);
            }
          }}
        >
          <span className={`status-pill status-pill-${operation.status.toLowerCase()}`}>{formatEnum(operation.status)}</span>
          <strong>{operation.title}</strong>
          <span>{operation.devices.map((device) => device.deviceName).join(", ")}</span>
          <span>{formatShortDateTime(operation.startAt)} - {formatShortDateTime(operation.endAt)}</span>
          <span className="maintenance-policy-pills">
            {operation.suppressAlerts ? <i>Alerts</i> : null}
            {operation.excludeFromAnalytics ? <i>Analytics</i> : null}
            {operation.excludeFromMl ? <i>ML</i> : null}
          </span>
          <span className="maintenance-agenda-actions">
            {operation.status === "ACTIVE" ? (
              <button className="ghost-button" type="button" onClick={(event) => { event.stopPropagation(); void onFinish(operation); }} disabled={isBusy}>
                Finish now
              </button>
            ) : null}
            {operation.status === "ACTIVE" || operation.status === "SCHEDULED" ? (
              <button className="ghost-button" type="button" onClick={(event) => { event.stopPropagation(); void onCancel(operation); }} disabled={isBusy}>
                Cancel
              </button>
            ) : null}
          </span>
        </article>
      ))}
    </div>
  );
}

function MaintenanceOperationDetailsDrawer({
  operation,
  onClose,
  onEdit,
  onCancel,
  onFinish,
  isBusy,
}: {
  operation: MaintenanceOperation;
  onClose: () => void;
  onEdit: () => void;
  onCancel: () => void;
  onFinish: () => void;
  isBusy: boolean;
}) {
  const canChange = operation.status === "ACTIVE" || operation.status === "SCHEDULED";
  return (
    <div className="modal-backdrop maintenance-drawer-backdrop" role="presentation" onMouseDown={onClose}>
      <section className="maintenance-drawer maintenance-details-drawer" role="dialog" aria-modal="true" aria-labelledby="maintenance-details-title" onMouseDown={(event) => event.stopPropagation()}>
        <div className="section-header">
          <div>
            <h2 id="maintenance-details-title">{operation.title}</h2>
            <p>{operation.devices.length} affected device{operation.devices.length === 1 ? "" : "s"}</p>
          </div>
          <button className="ghost-button" type="button" onClick={onClose}>
            Close
          </button>
        </div>
        <div className="maintenance-detail-stack">
          <div className="maintenance-detail-hero">
            <span className={`status-pill status-pill-${operation.status.toLowerCase()}`}>{formatEnum(operation.status)}</span>
            <strong>{formatShortDateTime(operation.startAt)} - {formatShortDateTime(operation.endAt)}</strong>
            {operation.description ? <p>{operation.description}</p> : null}
          </div>
          <dl className="maintenance-detail-grid">
            <div><dt>Suppress alerts</dt><dd>{operation.suppressAlerts ? "Yes" : "No"}</dd></div>
            <div><dt>Exclude analytics</dt><dd>{operation.excludeFromAnalytics ? "Yes" : "No"}</dd></div>
            <div><dt>Exclude ML</dt><dd>{operation.excludeFromMl ? "Yes" : "No"}</dd></div>
            <div><dt>Updated</dt><dd>{formatShortDateTime(operation.updatedAt)}</dd></div>
            {operation.cancelledAt ? <div><dt>Cancelled</dt><dd>{formatShortDateTime(operation.cancelledAt)}</dd></div> : null}
            {operation.cancellationReason ? <div><dt>Cancel reason</dt><dd>{operation.cancellationReason}</dd></div> : null}
          </dl>
          <div className="maintenance-detail-device-list">
            <strong>Devices</strong>
            {operation.devices.map((device) => (
              <Link to={`/devices/${device.deviceId}?tab=maintenance`} key={device.windowId}>
                <span>
                  <b>{device.deviceName}</b>
                  <small>{device.deviceType ? formatEnum(device.deviceType) : "Unknown type"} - {device.locationName ?? "No location"}</small>
                </span>
                <i className={`status-pill status-pill-${device.status.toLowerCase()}`}>{formatEnum(device.status)}</i>
              </Link>
            ))}
          </div>
          <div className="action-row">
            {canChange ? (
              <button className="primary-button" type="button" onClick={onEdit} disabled={isBusy}>
                Edit
              </button>
            ) : null}
            {operation.status === "ACTIVE" ? (
              <button className="ghost-button" type="button" onClick={onFinish} disabled={isBusy}>
                Finish now
              </button>
            ) : null}
            {canChange ? (
              <button className="danger-button" type="button" onClick={onCancel} disabled={isBusy}>
                Cancel operation
              </button>
            ) : null}
          </div>
        </div>
      </section>
    </div>
  );
}

interface MaintenanceOperationFormRequest {
  title: string;
  description: string;
  deviceIds: string[];
  startAt: string;
  endAt: string;
  suppressAlerts: boolean;
  excludeFromAnalytics: boolean;
  excludeFromMl: boolean;
}

function MaintenanceOperationDrawer({
  mode,
  operation,
  devices,
  locations,
  typeByCode,
  operations,
  defaultStart,
  defaultEnd,
  defaultDeviceIds,
  isBusy,
  onClose,
  onSubmit,
}: {
  mode: "create" | "edit";
  operation: MaintenanceOperation | null;
  devices: Device[];
  locations: Location[];
  typeByCode: Map<string, string>;
  operations: MaintenanceOperation[];
  defaultStart: string;
  defaultEnd: string;
  defaultDeviceIds: string[];
  isBusy: boolean;
  onClose: () => void;
  onSubmit: (request: MaintenanceOperationFormRequest) => void;
}) {
  const [title, setTitle] = useState(operation?.title ?? "Planned maintenance");
  const [description, setDescription] = useState(operation?.description ?? "");
  const [startAt, setStartAt] = useState(operation ? toDateTimeLocalValue(new Date(operation.startAt)) : defaultStart);
  const [endAt, setEndAt] = useState(operation ? toDateTimeLocalValue(new Date(operation.endAt)) : defaultEnd);
  const [deviceSearch, setDeviceSearch] = useState("");
  const [locationFilter, setLocationFilter] = useState("all");
  const [selectedDeviceIds, setSelectedDeviceIds] = useState<string[]>(operation?.devices.map((device) => device.deviceId) ?? defaultDeviceIds);
  const [suppressAlerts, setSuppressAlerts] = useState(operation?.suppressAlerts ?? true);
  const [excludeFromAnalytics, setExcludeFromAnalytics] = useState(operation?.excludeFromAnalytics ?? true);
  const [excludeFromMl, setExcludeFromMl] = useState(operation?.excludeFromMl ?? true);
  const locationById = useMemo(() => new Map(locations.map((location) => [location.id, locationLabel(location)])), [locations]);
  const visibleDevices = devices
    .filter((device) => locationFilter === "all" || device.locationId === locationFilter)
    .filter((device) => !deviceSearch.trim() || `${device.name} ${device.type}`.toLowerCase().includes(deviceSearch.trim().toLowerCase()))
    .sort((left, right) => left.name.localeCompare(right.name));
  const conflicts = useMemo(() => {
    const start = new Date(startAt);
    const end = new Date(endAt);
    if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime()) || end <= start || selectedDeviceIds.length === 0) {
      return [];
    }
    const selected = new Set(selectedDeviceIds);
    return operations.filter((candidate) => {
      if (candidate.id === operation?.id || candidate.status === "CANCELLED" || candidate.status === "FINISHED") return false;
      if (!candidate.devices.some((device) => selected.has(device.deviceId))) return false;
      return rangesOverlap(start, end, new Date(candidate.startAt), new Date(candidate.endAt));
    });
  }, [endAt, operation?.id, operations, selectedDeviceIds, startAt]);

  function toggleDevice(deviceId: string) {
    setSelectedDeviceIds((current) => current.includes(deviceId) ? current.filter((id) => id !== deviceId) : [...current, deviceId]);
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    onSubmit({ title, description, deviceIds: selectedDeviceIds, startAt, endAt, suppressAlerts, excludeFromAnalytics, excludeFromMl });
  }

  return (
    <div className="modal-backdrop maintenance-drawer-backdrop" role="presentation" onMouseDown={onClose}>
      <section className="maintenance-drawer" role="dialog" aria-modal="true" aria-labelledby="maintenance-drawer-title" onMouseDown={(event) => event.stopPropagation()}>
        <div className="section-header">
          <div>
            <h2 id="maintenance-drawer-title">{mode === "edit" ? "Edit maintenance" : "Schedule maintenance"}</h2>
            <p>{selectedDeviceIds.length} selected device{selectedDeviceIds.length === 1 ? "" : "s"}</p>
          </div>
          <button className="ghost-button" type="button" onClick={onClose}>
            Close
          </button>
        </div>
        <form className="maintenance-drawer-form" onSubmit={submit}>
          <div className="form-grid-two">
            <label>
              Title
              <input value={title} onChange={(event) => setTitle(event.target.value)} required />
            </label>
            <label>
              Description
              <input value={description} onChange={(event) => setDescription(event.target.value)} />
            </label>
            <label>
              Start
              <input type="datetime-local" value={startAt} onChange={(event) => setStartAt(event.target.value)} required />
            </label>
            <label>
              End
              <input type="datetime-local" value={endAt} onChange={(event) => setEndAt(event.target.value)} required />
            </label>
          </div>
          <div className="maintenance-policy-grid">
            <label>
              <input type="checkbox" checked={suppressAlerts} onChange={(event) => setSuppressAlerts(event.target.checked)} />
              Suppress alerts
            </label>
            <label>
              <input type="checkbox" checked={excludeFromAnalytics} onChange={(event) => setExcludeFromAnalytics(event.target.checked)} />
              Exclude analytics
            </label>
            <label>
              <input type="checkbox" checked={excludeFromMl} onChange={(event) => setExcludeFromMl(event.target.checked)} />
              Exclude ML
            </label>
          </div>
          <div className="maintenance-device-selector">
            <div className="maintenance-device-selector-toolbar">
              <input value={deviceSearch} onChange={(event) => setDeviceSearch(event.target.value)} placeholder="Search devices" />
              <select value={locationFilter} onChange={(event) => setLocationFilter(event.target.value)}>
                <option value="all">All locations</option>
                {locations.map((location) => (
                  <option value={location.id} key={location.id}>
                    {locationLabel(location)}
                  </option>
                ))}
              </select>
              <button className="ghost-button" type="button" onClick={() => setSelectedDeviceIds(visibleDevices.map((device) => device.id))}>
                Select visible
              </button>
            </div>
            <div className="maintenance-device-selector-list">
              {visibleDevices.map((device) => (
                <label className="maintenance-device-option" key={device.id}>
                  <input type="checkbox" checked={selectedDeviceIds.includes(device.id)} onChange={() => toggleDevice(device.id)} />
                  <span>
                    <strong>{device.name}</strong>
                    <small>{typeByCode.get(device.type) ?? formatEnum(device.type)} - {device.locationId ? locationById.get(device.locationId) ?? "Unknown location" : "No location"}</small>
                  </span>
                </label>
              ))}
            </div>
          </div>
          {conflicts.length > 0 ? (
            <div className="maintenance-conflict-warning">
              <strong>{conflicts.length} overlapping operation{conflicts.length === 1 ? "" : "s"}</strong>
              <span>{conflicts.slice(0, 3).map((conflict) => conflict.title).join(", ")}</span>
            </div>
          ) : null}
          <div className="maintenance-impact-summary">
            <strong>Impact</strong>
            <span>Alerts will be suppressed for selected devices during this window.</span>
            <span>Analytics and ML pipelines will ignore samples overlapping the interval when enabled.</span>
          </div>
          <div className="action-row">
            <button className="ghost-button" type="button" onClick={onClose}>
              Cancel
            </button>
            <button className="primary-button" type="submit" disabled={isBusy || selectedDeviceIds.length === 0}>
              {isBusy ? "Saving..." : mode === "edit" ? "Save changes" : "Schedule maintenance"}
            </button>
          </div>
        </form>
      </section>
    </div>
  );
}

function startOfDay(date: Date) {
  const next = new Date(date);
  next.setHours(0, 0, 0, 0);
  return next;
}

function startOfWeek(date: Date) {
  const next = startOfDay(date);
  const day = next.getDay() || 7;
  next.setDate(next.getDate() - day + 1);
  return next;
}

function addDays(date: Date, days: number) {
  const next = new Date(date);
  next.setDate(next.getDate() + days);
  return next;
}

function addHours(date: Date, hours: number) {
  const next = new Date(date);
  next.setHours(next.getHours() + hours);
  return next;
}

function defaultsForCalendarSelection(deviceId: string, selectionStart: Date, selectionEnd: Date): CreateDefaults {
  const normalizedStart = selectionStart <= selectionEnd ? selectionStart : selectionEnd;
  const normalizedEnd = selectionStart <= selectionEnd ? selectionEnd : selectionStart;
  const start = new Date(normalizedStart);
  start.setHours(9, 0, 0, 0);
  const end = new Date(normalizedEnd);
  end.setHours(17, 0, 0, 0);
  if (end <= start) {
    end.setTime(addHours(start, 2).getTime());
  }
  return {
    startAt: toDateTimeLocalValue(start),
    endAt: toDateTimeLocalValue(end),
    deviceIds: [deviceId],
  };
}

function daySpan(start: Date, end: Date) {
  return Math.max(1, Math.ceil((startOfDay(end).getTime() - startOfDay(start).getTime()) / 86400000));
}

function buildTimelineSlots(start: Date, end: Date): TimelineSlot[] {
  const days = daySpan(start, end);
  if (days <= 21) {
    return buildDailySlots(start, end);
  }
  if (days <= 120) {
    return buildWeeklySlots(start, end);
  }
  return buildMonthlySlots(start, end);
}

function buildDailySlots(start: Date, end: Date) {
  const slots: TimelineSlot[] = [];
  let cursor = startOfDay(start);
  const limit = startOfDay(end);
  while (cursor < limit) {
    slots.push({
      start: cursor,
      end: addDays(cursor, 1),
      label: weekdayShort(cursor),
      subLabel: cursor.toLocaleDateString([], { month: "short", day: "numeric" }),
    });
    cursor = addDays(cursor, 1);
  }
  return slots.length > 0 ? slots : [{
    start: startOfDay(start),
    end: addDays(startOfDay(start), 1),
    label: weekdayShort(start),
    subLabel: start.toLocaleDateString([], { month: "short", day: "numeric" }),
  }];
}

function buildWeeklySlots(start: Date, end: Date) {
  const slots: TimelineSlot[] = [];
  let cursor = startOfDay(start);
  const limit = startOfDay(end);
  while (cursor < limit) {
    const next = new Date(Math.min(addDays(cursor, 7).getTime(), limit.getTime()));
    slots.push({
      start: cursor,
      end: next,
      label: formatCompactDate(cursor),
      subLabel: next > addDays(cursor, 1) ? `to ${formatCompactDate(addDays(next, -1))}` : "",
    });
    cursor = next;
  }
  return slots;
}

function buildMonthlySlots(start: Date, end: Date) {
  const slots: TimelineSlot[] = [];
  let cursor = startOfDay(start);
  const limit = startOfDay(end);
  while (cursor < limit) {
    const monthEnd = new Date(cursor.getFullYear(), cursor.getMonth() + 1, 1);
    const next = new Date(Math.min(monthEnd.getTime(), limit.getTime()));
    slots.push({
      start: cursor,
      end: next,
      label: cursor.toLocaleDateString([], { month: "short", year: "numeric" }),
      subLabel: `${cursor.getDate()}-${addDays(next, -1).getDate()}`,
    });
    cursor = next;
  }
  return slots;
}

function columnMinWidth(slotCount: number) {
  if (slotCount <= 10) return 116;
  if (slotCount <= 16) return 96;
  return 76;
}

function formatCompactDate(date: Date) {
  return date.toLocaleDateString([], { month: "short", day: "numeric" });
}

function parseDateInput(value: string) {
  if (!value) return null;
  const [year, month, day] = value.split("-").map(Number);
  if (!year || !month || !day) return null;
  return startOfDay(new Date(year, month - 1, day));
}

function toDateInputValue(date: Date) {
  return toDateTimeLocalValue(date).slice(0, 10);
}

function toDateTimeLocalValue(date: Date) {
  return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}

function formatDateRange(start: Date, end: Date) {
  return `${start.toLocaleDateString([], { month: "short", day: "numeric" })} - ${end.toLocaleDateString([], { month: "short", day: "numeric", year: "numeric" })}`;
}

function formatShortDateTime(value: string) {
  return new Date(value).toLocaleString([], { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
}

function formatTimeRange(start: Date, end: Date) {
  return `${start.toLocaleString([], { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" })} - ${end.toLocaleString([], { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" })}`;
}

function weekdayShort(date: Date) {
  return date.toLocaleDateString([], { weekday: "short" });
}

function formatEnum(value: string) {
  return value.toLowerCase().split("_").map((part) => part.charAt(0).toUpperCase() + part.slice(1)).join(" ");
}

function locationLabel(location: Location) {
  return [location.name, location.building, location.floor ? `Floor ${location.floor}` : null, location.room].filter(Boolean).join(" - ");
}

function rangesOverlap(leftStart: Date, leftEnd: Date, rightStart: Date, rightEnd: Date) {
  return leftStart < rightEnd && rightStart < leftEnd;
}
