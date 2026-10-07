

import { KeyboardEvent, MouseEvent as ReactMouseEvent, useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";



import { Device } from "../devices/devicesApi";
import { MaintenanceOperation } from "./maintenanceApi";

import { columnMinWidth, formatTimeRange, formatEnum, TimelineSlot } from "./calendarPresentation";
export function MaintenanceTimelineGrid({
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

