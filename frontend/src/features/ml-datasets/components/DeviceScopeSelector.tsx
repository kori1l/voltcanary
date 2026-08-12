import type { Device } from "../../../devices/devicesApi";
import type { DatasetWorkbenchDraft } from "../model/datasetWorkbench.types";
import { filterDevices, selectedDeviceSummary, statusLabel } from "../model/datasetWorkbench.utils";
import type { MlDatasetWorkbenchPreviewResponse } from "../../../analytics/mlModuleApi";

export function DeviceScopeSelector({
  devices,
  draft,
  preview,
  onPatch,
}: {
  devices: Device[];
  draft: DatasetWorkbenchDraft;
  preview: MlDatasetWorkbenchPreviewResponse | null;
  onPatch: (patch: Partial<DatasetWorkbenchDraft>, affectsPreview?: boolean) => void;
}) {
  const filteredDevices = filterDevices(devices, draft);
  const summary = selectedDeviceSummary(devices, draft.deviceIds, preview);
  const deviceTypes = Array.from(new Set(devices.map((device) => device.type))).sort();
  const statuses = Array.from(new Set(devices.map((device) => device.status))).sort();

  return (
    <section className="workbench-section" aria-labelledby="workbench-device-scope-title">
      <div className="workbench-section-header">
        <div>
          <h3 id="workbench-device-scope-title">Device scope</h3>
          <span>{summary.selectedCount} selected / {summary.activeCount} active / {summary.telemetryPresentCount} telemetry-present</span>
        </div>
      </div>
      <div className="workbench-control-grid">
        <label>
          Search
          <input
            aria-label="Device search"
            placeholder="Name, id, type, status, location"
            value={draft.deviceQuery}
            onChange={(event) => onPatch({ deviceQuery: event.target.value }, false)}
          />
        </label>
        <label>
          Type
          <select value={draft.deviceTypeFilter} onChange={(event) => onPatch({ deviceTypeFilter: event.target.value })}>
            <option value="all">All types</option>
            {deviceTypes.map((type) => <option key={type} value={type}>{type}</option>)}
          </select>
        </label>
        <label>
          Status
          <select value={draft.deviceStatusFilter} onChange={(event) => onPatch({ deviceStatusFilter: event.target.value }, false)}>
            <option value="all">All statuses</option>
            {statuses.map((status) => <option key={status} value={status}>{statusLabel(status)}</option>)}
          </select>
        </label>
      </div>
      {devices.length === 0 ? (
        <div className="empty-state">Device list unavailable. Retry after the device service is reachable.</div>
      ) : (
        <div className="workbench-device-checklist" role="group" aria-label="Device selector">
          {filteredDevices.length === 0 ? (
            <div className="empty-state">No devices match the current search and filters.</div>
          ) : filteredDevices.map((device) => {
            const selected = draft.deviceIds.includes(device.id);
            const telemetryState = deviceHasTelemetryMetadata(device, preview) ? "Telemetry present" : "Telemetry unknown";
            return (
              <label className={`workbench-device-row ${selected ? "workbench-device-row-selected" : ""}`} key={device.id}>
                <input
                  checked={selected}
                  onChange={(event) => {
                    const nextIds = event.target.checked
                      ? [...draft.deviceIds, device.id]
                      : draft.deviceIds.filter((id) => id !== device.id);
                    onPatch({ deviceIds: nextIds });
                  }}
                  type="checkbox"
                />
                <span className="workbench-device-main">
                  <strong>{device.name}</strong>
                  <small>{device.type} / {statusLabel(device.status)}{device.locationId ? ` / ${device.locationId}` : ""}</small>
                </span>
                <span className="workbench-device-context">
                  <small>{telemetryState}</small>
                  <code>{shortId(device.id)}</code>
                </span>
              </label>
            );
          })}
        </div>
      )}
      <dl className="workbench-summary-grid">
        <div><dt>Known selected</dt><dd>{summary.knownCount}</dd></div>
        <div><dt>Inactive</dt><dd>{summary.inactiveCount}</dd></div>
        <div><dt>Missing telemetry</dt><dd>{summary.missingTelemetryCount}</dd></div>
        <div><dt>Unavailable</dt><dd>{summary.staleDeviceIds.length}</dd></div>
      </dl>
      {summary.staleDeviceIds.length > 0 && (
        <div className="warning-message">Unavailable devices in draft: {summary.staleDeviceIds.join(", ")}</div>
      )}
    </section>
  );
}

function deviceHasTelemetryMetadata(device: Device, preview: MlDatasetWorkbenchPreviewResponse | null) {
  if (preview?.availableMetrics.some((metric) => metric.devicesWithMetric > 0)) {
    return true;
  }
  const metadata = device.metadata ?? {};
  return ["metrics", "mlMetrics", "telemetryMetrics"].some((key) => {
    const raw = metadata[key];
    if (Array.isArray(raw)) {
      return raw.length > 0;
    }
    if (typeof raw === "string") {
      return raw.split(/[\n,]+/).some((item) => item.trim());
    }
    return false;
  });
}

function shortId(id: string) {
  return id.length <= 12 ? id : `${id.slice(0, 8)}...${id.slice(-4)}`;
}
