import { FormEvent, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import {
  acknowledgeAlert,
  AlertResponse,
  AlertSummaryResponse,
  createNotificationChannel,
  deleteNotificationChannel,
  disableNotificationChannel,
  enableNotificationChannel,
  getAlertSummary,
  listAlerts,
  listNotificationAttempts,
  listNotificationChannels,
  NotificationAttemptResponse,
  NotificationChannelResponse,
  resolveAlert,
} from "../alerts/alertsApi";
import { Device, listDevices, listLocations, Location } from "../devices/devicesApi";

const ALERT_LIMIT = 100;

export function AlertsPage() {
  const [alerts, setAlerts] = useState<AlertResponse[]>([]);
  const [summary, setSummary] = useState<AlertSummaryResponse | null>(null);
  const [channels, setChannels] = useState<NotificationChannelResponse[]>([]);
  const [attemptsByAlert, setAttemptsByAlert] = useState<Record<string, NotificationAttemptResponse[]>>({});
  const [expandedAttempts, setExpandedAttempts] = useState<Record<string, boolean>>({});
  const [devices, setDevices] = useState<Device[]>([]);
  const [locations, setLocations] = useState<Location[]>([]);
  const [deviceFilter, setDeviceFilter] = useState("all");
  const [severityFilter, setSeverityFilter] = useState("all");
  const [statusFilter, setStatusFilter] = useState("active");
  const [channelName, setChannelName] = useState("Ops webhook");
  const [webhookUrl, setWebhookUrl] = useState("");
  const [credentialsRef, setCredentialsRef] = useState("");
  const [isLoading, setIsLoading] = useState(true);
  const [isSavingChannel, setIsSavingChannel] = useState(false);
  const [loadingAttempts, setLoadingAttempts] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const serverStatusFilter = statusFilter === "active" || statusFilter === "all" ? undefined : statusFilter;
  const requestFilters = {
    deviceId: deviceFilter === "all" ? undefined : deviceFilter,
    severity: severityFilter === "all" ? undefined : severityFilter,
    status: serverStatusFilter,
    limit: ALERT_LIMIT,
  };

  async function load() {
    setIsLoading(true);
    setError(null);
    try {
      const [alertsResponse, summaryResponse, channelsResponse, devicesResponse, locationsResponse] = await Promise.all([
        listAlerts(requestFilters),
        getAlertSummary(requestFilters),
        listNotificationChannels().catch(() => []),
        listDevices().catch(() => []),
        listLocations().catch(() => []),
      ]);
      setAlerts(alertsResponse);
      setSummary(summaryResponse);
      setChannels(channelsResponse);
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
  }, [deviceFilter, severityFilter, statusFilter]);

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

  async function toggleAttempts(alertId: string) {
    const nextExpanded = !expandedAttempts[alertId];
    setExpandedAttempts((current) => ({ ...current, [alertId]: nextExpanded }));
    if (!nextExpanded || attemptsByAlert[alertId]) {
      return;
    }
    setLoadingAttempts(alertId);
    setError(null);
    try {
      const attempts = await listNotificationAttempts(alertId);
      setAttemptsByAlert((current) => ({ ...current, [alertId]: attempts }));
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to load notification attempts");
    } finally {
      setLoadingAttempts(null);
    }
  }

  async function addWebhookChannel(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!webhookUrl.trim()) {
      setError("Webhook URL is required");
      return;
    }
    setIsSavingChannel(true);
    setError(null);
    try {
      await createNotificationChannel({
        name: channelName.trim() || "Ops webhook",
        channelType: "WEBHOOK",
        config: { url: webhookUrl.trim() },
        credentialsRef: credentialsRef.trim() || null,
        enabled: true,
      });
      setWebhookUrl("");
      setCredentialsRef("");
      setChannels(await listNotificationChannels());
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to create notification channel");
    } finally {
      setIsSavingChannel(false);
    }
  }

  async function setChannelEnabled(channel: NotificationChannelResponse, enabled: boolean) {
    setError(null);
    try {
      const updated = enabled ? await enableNotificationChannel(channel.id) : await disableNotificationChannel(channel.id);
      setChannels((current) => current.map((item) => (item.id === updated.id ? updated : item)));
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to update notification channel");
    }
  }

  async function removeChannel(channel: NotificationChannelResponse) {
    setError(null);
    try {
      await deleteNotificationChannel(channel.id);
      setChannels((current) => current.filter((item) => item.id !== channel.id));
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to delete notification channel");
    }
  }

  const deviceById = useMemo(() => new Map(devices.map((device) => [device.id, device])), [devices]);
  const locationById = useMemo(() => new Map(locations.map((location) => [location.id, fixKnownTypos(location.name)])), [locations]);
  const visibleAlerts = useMemo(() => {
    return alerts
      .filter((alert) => (statusFilter === "active" ? alert.status !== "RESOLVED" : true))
      .sort((left, right) => severityRank(right.severity) - severityRank(left.severity) || new Date(right.createdAt).getTime() - new Date(left.createdAt).getTime());
  }, [alerts, statusFilter]);
  const grouped = useMemo(
    () => ({
      new: visibleAlerts.filter((alert) => alert.status === "OPEN"),
      acknowledged: visibleAlerts.filter((alert) => alert.status === "ACKNOWLEDGED"),
      resolved: visibleAlerts.filter((alert) => alert.status === "RESOLVED"),
    }),
    [visibleAlerts],
  );
  const mostImportant = grouped.new[0] ?? grouped.acknowledged[0] ?? null;
  const severities = ["CRITICAL", "HIGH", "WARNING", "INFO"];

  return (
    <>
      <header className="page-header page-header-row">
        <div>
          <h1>Alerts</h1>
          <p>Review active issues, delivery status, and source context from analytics.</p>
        </div>
        <button className="ghost-button" type="button" onClick={() => void load()} disabled={isLoading}>
          Refresh
        </button>
      </header>

      <section className="alert-summary-panel">
        <div className="alert-summary-counts">
          <SummaryMetric label="Open" value={summary?.byStatus.OPEN ?? grouped.new.length} />
          <SummaryMetric label="Acknowledged" value={summary?.byStatus.ACKNOWLEDGED ?? grouped.acknowledged.length} />
          <SummaryMetric label="Resolved" value={summary?.byStatus.RESOLVED ?? grouped.resolved.length} />
          <SummaryMetric label="Critical" value={summary?.bySeverity.CRITICAL ?? 0} />
        </div>
        <div className="most-important-alert">
          <span className="status-pill">Most important</span>
          {mostImportant ? (
            <>
              <strong>{mostImportant.title}</strong>
              <span>
                {deviceById.get(mostImportant.deviceId)?.name ?? "Unknown device"} - {formatShortDateTime(mostImportant.createdAt)}
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
            <option value="active">Active only</option>
            <option value="all">All statuses</option>
            <option value="OPEN">Open</option>
            <option value="ACKNOWLEDGED">Acknowledged</option>
            <option value="RESOLVED">Resolved</option>
          </select>
        </div>
      </section>

      <section className="panel notification-channel-panel">
        <div className="section-header">
          <div>
            <h2>Notification Channels</h2>
            <p>{channels.length} configured</p>
          </div>
        </div>
        <form className="notification-channel-form" onSubmit={addWebhookChannel}>
          <input value={channelName} onChange={(event) => setChannelName(event.target.value)} placeholder="Channel name" />
          <input value={webhookUrl} onChange={(event) => setWebhookUrl(event.target.value)} placeholder="Webhook URL" />
          <input value={credentialsRef} onChange={(event) => setCredentialsRef(event.target.value)} placeholder="Credentials ref" />
          <button className="primary-button" type="submit" disabled={isSavingChannel}>
            Add webhook
          </button>
        </form>
        {channels.length > 0 ? (
          <div className="notification-channel-list">
            {channels.map((channel) => (
              <article className="notification-channel-row" key={channel.id}>
                <div>
                  <strong>{channel.name}</strong>
                  <span>
                    {formatEnum(channel.channelType)} - {channel.enabled ? "Enabled" : "Disabled"}
                  </span>
                </div>
                <div className="action-row">
                  <button className="ghost-button" type="button" onClick={() => void setChannelEnabled(channel, !channel.enabled)}>
                    {channel.enabled ? "Disable" : "Enable"}
                  </button>
                  <button className="ghost-button" type="button" onClick={() => void removeChannel(channel)}>
                    Delete
                  </button>
                </div>
              </article>
            ))}
          </div>
        ) : null}
      </section>

      {error ? <div className="error-message">{error}</div> : null}
      {isLoading ? <section className="panel">Loading alerts...</section> : null}

      {!isLoading ? (
        <section className="content-grid alerts-grid">
          <AlertColumn title="Open" alerts={grouped.new} deviceById={deviceById} locationById={locationById} attemptsByAlert={attemptsByAlert} expandedAttempts={expandedAttempts} loadingAttempts={loadingAttempts} onAttempts={toggleAttempts} onUpdate={updateStatus} />
          <AlertColumn title="Acknowledged" alerts={grouped.acknowledged} deviceById={deviceById} locationById={locationById} attemptsByAlert={attemptsByAlert} expandedAttempts={expandedAttempts} loadingAttempts={loadingAttempts} onAttempts={toggleAttempts} onUpdate={updateStatus} />
          <AlertColumn title="Resolved" alerts={grouped.resolved} deviceById={deviceById} locationById={locationById} attemptsByAlert={attemptsByAlert} expandedAttempts={expandedAttempts} loadingAttempts={loadingAttempts} onAttempts={toggleAttempts} onUpdate={updateStatus} />
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
  attemptsByAlert,
  expandedAttempts,
  loadingAttempts,
  onAttempts,
  onUpdate,
}: {
  title: string;
  alerts: AlertResponse[];
  deviceById: Map<string, Device>;
  locationById: Map<string, string>;
  attemptsByAlert: Record<string, NotificationAttemptResponse[]>;
  expandedAttempts: Record<string, boolean>;
  loadingAttempts: string | null;
  onAttempts: (alertId: string) => Promise<void>;
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
            const attempts = attemptsByAlert[alert.id] ?? [];
            return (
              <article className={`alert-card severity-${alert.severity.toLowerCase()}`} key={alert.id}>
                <div className="alert-card-header">
                  <div>
                    <strong>{formatAlertTitle(alert)}</strong>
                    <span className="alert-context">
                      {device?.name ?? "Unknown device"} {location ? `- ${location}` : ""} - {formatShortDateTime(alert.createdAt)}
                    </span>
                  </div>
                  <span>{formatEnum(alert.severity)}</span>
                </div>
                <p>{formatAlertDescription(alert)}</p>
                <AlertTrace alert={alert} />
                {alert.status === "RESOLVED" ? (
                  <p className="resolution-reason">
                    Resolution reason: <strong>{alert.resolutionReason || "No reason recorded."}</strong>
                  </p>
                ) : null}
                <div className="action-row">
                  <Link className="ghost-button button-link" to={`/devices/${alert.deviceId}`}>
                    View device
                  </Link>
                  <button className="ghost-button" type="button" onClick={() => void onAttempts(alert.id)}>
                    {expandedAttempts[alert.id] ? "Hide attempts" : "Delivery"}
                  </button>
                  {alert.status === "OPEN" ? (
                    <button className="primary-button" type="button" onClick={() => void onUpdate(alert.id, "ack")}>
                      Acknowledge
                    </button>
                  ) : null}
                  {alert.status === "ACKNOWLEDGED" ? (
                    <button className="primary-button" type="button" onClick={() => void onUpdate(alert.id, "resolve")}>
                      Resolve
                    </button>
                  ) : null}
                </div>
                {expandedAttempts[alert.id] ? (
                  <NotificationAttempts attempts={attempts} isLoading={loadingAttempts === alert.id} />
                ) : null}
              </article>
            );
          })}
        </div>
      )}
    </div>
  );
}

function AlertTrace({ alert }: { alert: AlertResponse }) {
  const items = [
    ["Source", shortId(alert.sourceEventId)],
    ["Anomaly", shortId(alert.anomalyId)],
    ["Risk", shortId(alert.riskScoreId)],
    ["Rule", alert.ruleId],
    ["Window", formatWindow(alert.calculationWindowStart, alert.calculationWindowEnd)],
    ["Input", shortHash(alert.calculationInputHash)],
  ].filter(([, value]) => value);
  if (items.length === 0) {
    return null;
  }
  return (
    <dl className="alert-trace-list">
      {items.map(([label, value]) => (
        <div key={label}>
          <dt>{label}</dt>
          <dd>{value}</dd>
        </div>
      ))}
    </dl>
  );
}

function NotificationAttempts({ attempts, isLoading }: { attempts: NotificationAttemptResponse[]; isLoading: boolean }) {
  if (isLoading) {
    return <p className="notification-attempts">Loading delivery attempts...</p>;
  }
  if (attempts.length === 0) {
    return <p className="notification-attempts">No delivery attempts recorded.</p>;
  }
  return (
    <div className="notification-attempts">
      {attempts.map((attempt) => (
        <div className="notification-attempt-row" key={attempt.id}>
          <span>{formatEnum(attempt.channelType)}</span>
          <strong>{formatEnum(attempt.status)}</strong>
          <span>{attempt.lastErrorCode || attempt.providerResponse || formatShortDateTime(attempt.createdAt)}</span>
        </div>
      ))}
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
  if (normalized === "INFO" || normalized === "LOW") {
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

function shortId(value?: string | null) {
  return value ? value.slice(0, 8) : null;
}

function shortHash(value?: string | null) {
  return value ? value.slice(0, 10) : null;
}

function formatWindow(start?: string | null, end?: string | null) {
  if (!start || !end) {
    return null;
  }
  return `${formatShortDateTime(start)} - ${formatShortDateTime(end)}`;
}
