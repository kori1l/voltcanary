import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { acknowledgeAlert, AlertResponse, listAlerts, resolveAlert } from "../alerts/alertsApi";
import { Device, listDevices, listLocations, Location } from "../devices/devicesApi";

export function AlertsPage() {
  const [alerts, setAlerts] = useState<AlertResponse[]>([]);
  const [devices, setDevices] = useState<Device[]>([]);
  const [locations, setLocations] = useState<Location[]>([]);
  const [deviceFilter, setDeviceFilter] = useState("all");
  const [severityFilter, setSeverityFilter] = useState("all");
  const [statusFilter, setStatusFilter] = useState("all");
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  async function load() {
    setIsLoading(true);
    setError(null);
    try {
      const [alertsResponse, devicesResponse, locationsResponse] = await Promise.all([
        listAlerts(),
        listDevices().catch(() => []),
        listLocations().catch(() => []),
      ]);
      setAlerts(alertsResponse);
      setDevices(devicesResponse);
      setLocations(locationsResponse);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to load alerts");
    } finally {
      setIsLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  async function updateStatus(alertId: string, action: "ack" | "resolve") {
    setError(null);
    try {
      if (action === "ack") {
        await acknowledgeAlert(alertId);
      } else {
        const reason = window.prompt("Resolution reason");
        if (reason === null) {
          return;
        }
        await resolveAlert(alertId, reason);
      }
      await load();
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to update alert");
    }
  }

  const deviceById = useMemo(() => new Map(devices.map((device) => [device.id, device])), [devices]);
  const locationById = useMemo(() => new Map(locations.map((location) => [location.id, fixKnownTypos(location.name)])), [locations]);
  const filteredAlerts = useMemo(() => {
    return alerts
      .filter((alert) => deviceFilter === "all" || alert.deviceId === deviceFilter)
      .filter((alert) => severityFilter === "all" || alert.severity === severityFilter)
      .filter((alert) => {
        if (statusFilter === "active") {
          return alert.status !== "RESOLVED";
        }
        return statusFilter === "all" || alert.status === statusFilter;
      })
      .sort((left, right) => severityRank(right.severity) - severityRank(left.severity) || new Date(right.createdAt).getTime() - new Date(left.createdAt).getTime());
  }, [alerts, deviceFilter, severityFilter, statusFilter]);
  const grouped = useMemo(
    () => ({
      new: filteredAlerts.filter((alert) => alert.status === "OPEN"),
      acknowledged: filteredAlerts.filter((alert) => alert.status === "ACKNOWLEDGED"),
      resolved: filteredAlerts.filter((alert) => alert.status === "RESOLVED"),
    }),
    [filteredAlerts],
  );
  const mostImportant = grouped.new[0] ?? grouped.acknowledged[0] ?? null;
  const severities = [...new Set(alerts.map((alert) => alert.severity))];

  return (
    <>
      <header className="page-header page-header-row">
        <div>
          <h1>Alerts</h1>
          <p>Review active issues, acknowledge what you are checking, then resolve with a reason.</p>
        </div>
        <button className="ghost-button" type="button" onClick={() => void load()} disabled={isLoading}>
          Refresh alerts
        </button>
      </header>

      <section className="alert-summary-panel">
        <div className="alert-summary-counts">
          <SummaryMetric label="New alerts" value={alerts.filter((alert) => alert.status === "OPEN").length} />
          <SummaryMetric label="Acknowledged" value={alerts.filter((alert) => alert.status === "ACKNOWLEDGED").length} />
          <SummaryMetric label="Resolved" value={alerts.filter((alert) => alert.status === "RESOLVED").length} />
        </div>
        <div className="most-important-alert">
          <span className="status-pill">Most important</span>
          {mostImportant ? (
            <>
              <strong>{mostImportant.title}</strong>
              <span>
                {deviceById.get(mostImportant.deviceId)?.name ?? "Unknown device"} · {formatShortDateTime(mostImportant.createdAt)}
              </span>
            </>
          ) : (
            <>
              <strong>No active alerts</strong>
              <span>There are no open or acknowledged issues in the current filters.</span>
            </>
          )}
        </div>
      </section>

      <section className="panel alerts-filter-panel">
        <div className="inventory-toolbar">
          <select value={deviceFilter} onChange={(event) => setDeviceFilter(event.target.value)}>
            <option value="all">All devices</option>
            {devices.map((device) => (
              <option value={device.id} key={device.id}>
                {device.name}
              </option>
            ))}
          </select>
          <select value={severityFilter} onChange={(event) => setSeverityFilter(event.target.value)}>
            <option value="all">All severities</option>
            {severities.map((severity) => (
              <option value={severity} key={severity}>
                {formatEnum(severity)}
              </option>
            ))}
          </select>
          <select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)}>
            <option value="all">All statuses</option>
            <option value="active">Active only</option>
            <option value="OPEN">New</option>
            <option value="ACKNOWLEDGED">Acknowledged</option>
            <option value="RESOLVED">Resolved</option>
          </select>
        </div>
      </section>

      {error ? <div className="error-message">{error}</div> : null}
      {isLoading ? <section className="panel">Loading alerts...</section> : null}

      {!isLoading ? (
        <section className="content-grid alerts-grid">
          <AlertColumn
            title="New"
            alerts={grouped.new}
            deviceById={deviceById}
            locationById={locationById}
            onUpdate={updateStatus}
          />
          <AlertColumn
            title="Acknowledged"
            alerts={grouped.acknowledged}
            deviceById={deviceById}
            locationById={locationById}
            onUpdate={updateStatus}
          />
          <AlertColumn
            title="Resolved"
            alerts={grouped.resolved}
            deviceById={deviceById}
            locationById={locationById}
            onUpdate={updateStatus}
          />
        </section>
      ) : null}
    </>
  );
}

function SummaryMetric({ label, value }: { label: string; value: number }) {
  return (
    <article>
      <strong>{value}</strong>
      <span>{label}</span>
    </article>
  );
}

function AlertColumn({
  title,
  alerts,
  deviceById,
  locationById,
  onUpdate,
}: {
  title: string;
  alerts: AlertResponse[];
  deviceById: Map<string, Device>;
  locationById: Map<string, string>;
  onUpdate: (alertId: string, action: "ack" | "resolve") => Promise<void>;
}) {
  return (
    <div className="panel alert-column">
      <h2>{title}</h2>
      {alerts.length === 0 ? (
        <p className="muted-text">No alerts.</p>
      ) : (
        <div className="alert-list">
          {alerts.map((alert) => {
            const device = deviceById.get(alert.deviceId);
            const location = device ? locationById.get(device.locationId ?? "") : null;
            return (
              <article className={`alert-card severity-${alert.severity.toLowerCase()}`} key={alert.id}>
                <div className="alert-card-header">
                  <div>
                    <strong>{formatAlertTitle(alert)}</strong>
                    <span className="alert-context">
                      {device?.name ?? "Unknown device"} {location ? `· ${location}` : ""} · {formatShortDateTime(alert.createdAt)}
                    </span>
                  </div>
                  <span>{formatEnum(alert.severity)}</span>
                </div>
                <p>{formatAlertDescription(alert)}</p>
                {alert.status === "RESOLVED" ? (
                  <p className="resolution-reason">
                    Resolution reason: <strong>{alert.resolutionReason || "No reason recorded."}</strong>
                  </p>
                ) : null}
                <p className="recommended-action">Recommended action: check load and power conditions.</p>
                <div className="action-row">
                  <Link className="ghost-button button-link" to={`/devices/${alert.deviceId}`}>
                    View device
                  </Link>
                  {alert.status === "OPEN" ? (
                    <button className="primary-button" type="button" onClick={() => void onUpdate(alert.id, "ack")}>
                      Acknowledge
                    </button>
                  ) : null}
                  {alert.status === "ACKNOWLEDGED" ? (
                    <button className="primary-button" type="button" onClick={() => void onUpdate(alert.id, "resolve")}>
                      Resolve with reason
                    </button>
                  ) : null}
                </div>
              </article>
            );
          })}
        </div>
      )}
    </div>
  );
}

function formatAlertTitle(alert: AlertResponse) {
  return alert.title || formatEnum(alert.alertType);
}

function formatAlertDescription(alert: AlertResponse) {
  if (alert.description) {
    return alert.description;
  }
  if (alert.alertType.includes("POWER")) {
    return "Power was above the normal range.";
  }
  return "The device behavior moved outside its recent normal range.";
}

function severityRank(value: string) {
  const normalized = value.toUpperCase();
  if (normalized === "CRITICAL") {
    return 4;
  }
  if (normalized === "HIGH") {
    return 3;
  }
  if (normalized === "WARNING" || normalized === "MEDIUM") {
    return 2;
  }
  if (normalized === "LOW") {
    return 1;
  }
  return 0;
}

function formatEnum(value: string) {
  return value
    .toLowerCase()
    .split(/[_\s-]+/)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

function fixKnownTypos(value: string) {
  return value.replace(/\bKithen\b/gi, "Kitchen");
}

function formatShortDateTime(value: string) {
  return new Date(value).toLocaleString([], {
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
  });
}
