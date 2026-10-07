import { MaintenanceOutcomeEditor } from "./MaintenanceOutcomeEditor";
import { MaintenanceFields, validMaintenanceInterval } from "./MaintenanceFields";
import { FormEvent, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { Dialog, DialogClose } from "../components/Dialog";


import { Device, Location } from "../devices/devicesApi";
import { MaintenanceOperation } from "./maintenanceApi";

import { toDateTimeLocalValue, formatShortDateTime, formatEnum, locationLabel, rangesOverlap } from "./calendarPresentation";
export function MaintenanceOperationDetailsDrawer({
  operation,
  onClose,
  onEdit,
  onCancel,
  onFinish,
  onOutcomeSaved,
  isBusy,
}: {
  operation: MaintenanceOperation;
  onClose: () => void;
  onEdit: () => void;
  onCancel: () => void;
  onFinish: () => void;
  onOutcomeSaved: () => void;
  isBusy: boolean;
}) {
  const canChange = operation.status === "ACTIVE" || operation.status === "SCHEDULED";
  return (
    <Dialog title={operation.title} onClose={onClose} busy={isBusy} guardChanges wide>
        <p>{operation.devices.length} affected devices</p>
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
          <MaintenanceOutcomeEditor operation={operation} onSaved={onOutcomeSaved} />
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
    </Dialog>
  );
}

export interface MaintenanceOperationFormRequest {
  title: string;
  description: string;
  deviceIds: string[];
  startAt: string;
  endAt: string;
  suppressAlerts: boolean;
  excludeFromAnalytics: boolean;
  excludeFromMl: boolean;
}

export function MaintenanceOperationDrawer({
  mode,
  operation,
  devices,
  locations,
  typeByCode,
  operations,
  defaultStart,
  defaultEnd,
  error,
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
  error: string | null;
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
    if (!validMaintenanceInterval(startAt, endAt) || isBusy) return;
    onSubmit({ title, description, deviceIds: selectedDeviceIds, startAt, endAt, suppressAlerts, excludeFromAnalytics, excludeFromMl });
  }

  return (
    <Dialog title={mode === "edit" ? "Edit maintenance" : "Schedule maintenance"} onClose={onClose} busy={isBusy} guardChanges wide footer={<>
      <DialogClose /><button className="primary-button" type="submit" form="maintenance-operation-form" disabled={isBusy || selectedDeviceIds.length === 0 || !validMaintenanceInterval(startAt, endAt)}>{isBusy ? "Saving..." : mode === "edit" ? "Save changes" : "Schedule maintenance"}</button>
    </>}>
        <p>{selectedDeviceIds.length} selected devices</p>
        {error ? <p className="error-message" role="alert">{error}</p> : null}
        <form id="maintenance-operation-form" className="maintenance-drawer-form" onSubmit={submit}>
          <MaintenanceFields draft={{ title, description, startAt, endAt, suppressAlerts, excludeFromAnalytics, excludeFromMl }} onChange={(update) => { if (update.title !== undefined) setTitle(update.title); if (update.description !== undefined) setDescription(update.description); if (update.startAt !== undefined) setStartAt(update.startAt); if (update.endAt !== undefined) setEndAt(update.endAt); if (update.suppressAlerts !== undefined) setSuppressAlerts(update.suppressAlerts); if (update.excludeFromAnalytics !== undefined) setExcludeFromAnalytics(update.excludeFromAnalytics); if (update.excludeFromMl !== undefined) setExcludeFromMl(update.excludeFromMl); }} />          <div className="maintenance-device-selector">
            <div className="maintenance-device-selector-toolbar">
              <label>Search devices<input value={deviceSearch} onChange={(event) => setDeviceSearch(event.target.value)} placeholder="Search devices" /></label>
              <label>Device location<select value={locationFilter} onChange={(event) => setLocationFilter(event.target.value)}>
                <option value="all">All locations</option>
                {locations.map((location) => (
                  <option value={location.id} key={location.id}>
                    {locationLabel(location)}
                  </option>
                ))}
              </select></label>
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

        </form>
    </Dialog>
  );
}

