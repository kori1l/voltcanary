import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { AlertResponse, listAlerts } from "../alerts/alertsApi";
import { Device, listDevices, listLocations, Location } from "../devices/devicesApi";
import { getLatestTelemetry, LatestTelemetry } from "../telemetry/telemetryApi";

interface DeviceSnapshot {
  device: Device;
  telemetry: LatestTelemetry | null;
}

export function DashboardPage() {
  const [alerts, setAlerts] = useState<AlertResponse[]>([]);
  const [devices, setDevices] = useState<Device[]>([]);
  const [locations, setLocations] = useState<Location[]>([]);
  const [snapshots, setSnapshots] = useState<DeviceSnapshot[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  async function load() {
    setIsLoading(true);
    setError(null);
    try {
      const [alertsResponse, devicesResponse, locationsResponse] = await Promise.all([
        listAlerts(),
        listDevices(),
        listLocations().catch(() => []),
      ]);
      const telemetryResponses = await Promise.all(
        devicesResponse.slice(0, 6).map(async (device) => ({
          device,
          telemetry: await getLatestTelemetry(device.id).catch(() => null),
        })),
      );

      setAlerts(alertsResponse);
      setDevices(devicesResponse);
      setLocations(locationsResponse);
      setSnapshots(telemetryResponses);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to load dashboard");
    } finally {
      setIsLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  const locationById = useMemo(() => new Map(locations.map((location) => [location.id, fixKnownTypos(location.name)])), [locations]);
  const activeAlerts = alerts.filter((alert) => alert.status !== "RESOLVED");
  const resolvedAlerts = alerts.filter((alert) => alert.status === "RESOLVED");
  const criticalAlerts = activeAlerts.filter((alert) => alert.severity === "CRITICAL" || alert.severity === "HIGH");
  const onlineDevices = devices.filter((device) => device.status === "ACTIVE" || device.status === "ONLINE");
  const mostRecentAlert = activeAlerts[0] ?? alerts[0] ?? null;

  return (
    <>
      <header className="page-header page-header-row">
        <div>
          <h1>Dashboard</h1>
          <p>Fleet health, alert pressure, and the latest telemetry signals.</p>
        </div>
        <button className="ghost-button" type="button" onClick={() => void load()} disabled={isLoading}>
          Refresh dashboard
        </button>
      </header>

      {error ? <div className="error-message">{error}</div> : null}
      {isLoading ? <section className="panel">Loading dashboard...</section> : null}

      {!isLoading ? (
        <>
          <section className="metric-grid">
            <MetricCard label="Devices" value={devices.length.toString()} />
            <MetricCard label="Online devices" value={onlineDevices.length.toString()} />
            <MetricCard label="Active alerts" value={activeAlerts.length.toString()} />
            <MetricCard label="Resolved alerts" value={resolvedAlerts.length.toString()} />
          </section>

          <section className="content-grid dashboard-grid">
            <div className="panel">
              <div className="section-header">
                <div>
                  <h2>Current Attention</h2>
                  <p>{criticalAlerts.length} high priority alerts need review.</p>
                </div>
                <Link className="ghost-button button-link" to="/alerts">
                  Open alerts
                </Link>
              </div>
              {mostRecentAlert ? (
                <article className={`alert-card severity-${mostRecentAlert.severity.toLowerCase()}`}>
                  <div className="alert-card-header">
                    <div>
                      <strong>{mostRecentAlert.title || formatEnum(mostRecentAlert.alertType)}</strong>
                      <span className="alert-context">
                        {formatEnum(mostRecentAlert.status)} - {formatShortDateTime(mostRecentAlert.createdAt)}
                      </span>
                    </div>
                    <span>{formatEnum(mostRecentAlert.severity)}</span>
                  </div>
                  <p>{mostRecentAlert.description || "The device behavior moved outside its recent normal range."}</p>
                </article>
              ) : (
                <div className="empty-state">
                  <strong>No alerts yet</strong>
                  <span>Incoming anomalies and resolved items will appear here.</span>
                </div>
              )}
            </div>

            <div className="panel">
              <div className="section-header">
                <div>
                  <h2>Latest Signals</h2>
                  <p>Recent telemetry from the first devices with data.</p>
                </div>
              </div>
              {snapshots.length === 0 ? (
                <div className="empty-state">
                  <strong>No devices found</strong>
                  <span>Create a device or ingest telemetry to populate this view.</span>
                </div>
              ) : (
                <div className="dashboard-signal-list">
                  {snapshots.map(({ device, telemetry }) => (
                    <Link className="dashboard-signal-row" to={`/devices/${device.id}`} key={device.id}>
                      <div>
                        <strong>{device.name}</strong>
                        <span>{device.locationId ? locationById.get(device.locationId) ?? "Unknown location" : "No location"}</span>
                      </div>
                      <div className="dashboard-signal-values">
                        {telemetry ? formatReadings(telemetry.readings) : "No telemetry"}
                      </div>
                    </Link>
                  ))}
                </div>
              )}
            </div>
          </section>
        </>
      ) : null}
    </>
  );
}

function MetricCard({ label, value }: { label: string; value: string }) {
  return (
    <article className="metric-card">
      <span>{label}</span>
      <strong>{value}</strong>
    </article>
  );
}

function formatReadings(readings: Record<string, number>) {
  const entries = Object.entries(readings).slice(0, 3);
  if (entries.length === 0) {
    return "No metrics";
  }
  return entries.map(([key, value]) => `${formatEnum(key)} ${formatNumber(value)}`).join(" / ");
}

function formatNumber(value: number) {
  return Number.isInteger(value) ? value.toString() : value.toFixed(1);
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
