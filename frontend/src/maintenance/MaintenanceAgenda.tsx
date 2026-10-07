







import { MaintenanceOperation } from "./maintenanceApi";

import { formatShortDateTime, formatEnum } from "./calendarPresentation";
export function MaintenanceAgenda({
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

