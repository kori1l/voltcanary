import { CompactEntityFilter } from "../../components/CompactEntityFilter";
import { ReadRefresh } from "../../components/ReadRefresh";
import { loadDeviceOptions } from "../../devices/fleetEntityOptions";
import type { Device } from "../../devices/devicesApi";
import type { AlertRule } from "../../alerts/alertsApi";
import { formatEnum } from "./AlertInbox";

interface Props {
  view: string;
  onView: (value: "inbox" | "rules") => void;
  device: string;
  devices: Device[];
  onDevice: (id: string) => void;
  severity: string;
  severities: string[];
  onSeverity: (value: string) => void;
  status: string;
  onStatus: (value: string) => void;
  rule: string;
  rules: AlertRule[];
  onRule: (value: string) => void;
  sort: string;
  onSort: (value: string) => void;
  onClear: () => void;
  scope: string;
  refresh: () => Promise<unknown>;
}
export function AlertInboxControls(p: Props) {
  return (
    <section className="panel compact-console-controls">
      <div className="device-inventory-controls">
        <div className="action-row">
          <button
            type="button"
            className={p.view === "inbox" ? "primary-button" : "ghost-button"}
            onClick={() => p.onView("inbox")}
          >
            Inbox
          </button>
          <button
            type="button"
            className={p.view === "rules" ? "primary-button" : "ghost-button"}
            onClick={() => p.onView("rules")}
          >
            Rules
          </button>
        </div>
        {p.view === "inbox" ? (
          <>
            <CompactEntityFilter
              label="Device"
              allLabel="All devices"
              value={p.device === "all" ? "" : p.device}
              options={p.devices.map((d) => ({ id: d.id, name: d.name }))}
              loadOptions={loadDeviceOptions}
              onChange={p.onDevice}
            />
            <label>
              <span className="sr-only">Severity</span>
              <select value={p.severity} onChange={(e) => p.onSeverity(e.target.value)}>
                <option value="all">All severities</option>
                {p.severities.map((s) => (
                  <option key={s} value={s}>
                    {formatEnum(s)}
                  </option>
                ))}
              </select>
            </label>
            <label>
              <span className="sr-only">Status</span>
              <select value={p.status} onChange={(e) => p.onStatus(e.target.value)}>
                <option value="active">Active only</option>
                <option value="all">All statuses</option>
                <option value="OPEN">Open</option>
                <option value="ACKNOWLEDGED">Acknowledged</option>
                <option value="RESOLVED">Resolved</option>
              </select>
            </label>
            <label>
              <span className="sr-only">Rule</span>
              <select value={p.rule} onChange={(e) => p.onRule(e.target.value)}>
                <option value="all">All rules</option>
                {p.rules.map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.name}
                  </option>
                ))}
              </select>
            </label>
            {p.rule !== "all" ? (
              <button type="button" className="ghost-button" onClick={() => p.onRule("all")}>
                Clear rule
              </button>
            ) : null}
            <button type="button" className="ghost-button" onClick={p.onClear}>
              Clear filters
            </button>
          </>
        ) : null}
        <details className="device-inventory-more">
          <summary>Options</summary>
          <div className="device-inventory-popover">
            {p.view === "inbox" ? (
              <label>
                Sort alerts
                <select value={p.sort} onChange={(e) => p.onSort(e.target.value)}>
                  <option value="severity">Severity, then newest</option>
                  <option value="time">Newest first</option>
                  <option value="status">Status, then newest</option>
                </select>
              </label>
            ) : null}
            <ReadRefresh scope={p.scope} refresh={p.refresh} />
          </div>
        </details>
      </div>
    </section>
  );
}
