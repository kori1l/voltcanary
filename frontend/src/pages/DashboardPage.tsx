import { Link } from "react-router-dom";
import { PageHeader } from "../components/PageHeader";
import { SectionFeedback } from "../components/SectionFeedback";
import { TimeDisplay } from "../components/TimeDisplay";
import { isFreshSignal, useFleetDashboard } from "../features/dashboard/useFleetDashboard";

export function DashboardPage() {
  const fleet = useFleetDashboard();
  const locationById = new Map((fleet.locations.data ?? []).map((location) => [location.id, location.name]));
  const freshSignals = fleet.signals.data?.some((signal) => signal.error) ? undefined : fleet.signals.data?.filter((signal) => isFreshSignal(signal.telemetry)).length;
  return <>
    <PageHeader title="Dashboard" description="Fleet inventory and evidence requiring attention." actions={<button className="ghost-button" onClick={() => void fleet.reload()} disabled={fleet.inventory.refreshing || fleet.alerts.refreshing}>Refresh dashboard</button>} />
    <section className="metric-grid">
      <MetricCard label="Registered devices (up to 200)" value={fleet.inventory.data?.length} stale={fleet.inventory.status === "stale"} />
      <MetricCard label={`Fresh signals (${fleet.signals.data?.length ?? "unknown"} checked)`} value={freshSignals} stale={fleet.signals.status === "stale"} />
      <MetricCard label="Active alerts (up to 100)" value={fleet.alerts.data ? fleet.attention.length : undefined} stale={fleet.alerts.status === "stale"} />
      <MetricCard label="High priority alerts" value={fleet.alerts.data ? fleet.attention.filter((alert) => ["CRITICAL", "HIGH"].includes(alert.severity)).length : undefined} stale={fleet.alerts.status === "stale"} />
    </section>
    <SectionFeedback resource={fleet.inventory} label="inventory" onRetry={() => void fleet.reloadInventory()} emptyMessage="No devices registered. Add a device to begin." />
    <section className="content-grid dashboard-grid">
      <section className="panel"><div className="section-header"><h2>Current attention</h2><Link className="ghost-button button-link" to="/alerts">Open alerts</Link></div>
        <SectionFeedback resource={fleet.alerts} label="alerts" onRetry={() => void fleet.reloadAlerts()} emptyMessage="No alerts reported in the loaded cohort." />
        {fleet.attention.slice(0, 5).map((alert) => <article key={alert.id} className={`alert-card severity-${alert.severity.toLowerCase()}`}><strong>{alert.title || alert.alertType.replace(/_/g, " ")}</strong><p>{alert.severity} · {alert.status} · <TimeDisplay value={alert.createdAt} /></p><p>{alert.description}</p><Link className="ghost-button button-link" to={`/devices/${alert.deviceId}?tab=alerts`}>Review device alert</Link></article>)}
        {fleet.alerts.data && fleet.alerts.data.length > 0 && !fleet.attention.length ? <p>No open alerts in the loaded cohort. This does not establish device health.</p> : null}
      </section>
      <section className="panel"><h2>Latest signals</h2><p>First six registered devices. Fresh means a reading within five minutes; registration status is separate.</p>
        <SectionFeedback resource={fleet.signals} label="signals" onRetry={() => void fleet.reloadSignals()} emptyMessage="No devices to check. Register a device first." />
        <SectionFeedback resource={fleet.locations} label="locations" onRetry={() => void fleet.reloadLocations()} />
        <div className="dashboard-signal-list">{fleet.signals.data?.map(({ device, telemetry, error }) => <Link className="dashboard-signal-row" to={`/devices/${device.id}`} key={device.id}>
          <div><strong>{device.name}</strong><span>{device.locationId ? locationById.get(device.locationId) ?? "Location unavailable" : "No location"}</span><small>Registered: {device.status}</small></div>
          <div className="dashboard-signal-values">{error ? <span>Telemetry refresh failed: {error}</span> : null}{telemetry ? <><span>{error ? "Retained stale signal" : isFreshSignal(telemetry) ? "Fresh signal" : "Stale signal"}</span><TimeDisplay value={telemetry.timestamp} /><span>{Object.entries(telemetry.readings).slice(0, 3).map(([name, value]) => `${name}: ${value.toLocaleString()}`).join(" / ") || "No readings"}</span></> : "No telemetry evidence"}</div>
        </Link>)}</div>
      </section>
    </section>
  </>;
}

function MetricCard({ label, value, stale }: { label: string; value: number | undefined; stale: boolean }) {
  return <article className="metric-card"><span>{label}</span><strong>{value === undefined ? "Unavailable" : value.toLocaleString()}</strong>{stale ? <small>Stale · refresh failed</small> : null}</article>;
}
