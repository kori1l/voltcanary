import { Link } from "react-router-dom";
import { AlertResponse, AlertRule, NotificationAttemptResponse } from "../../alerts/alertsApi";
import { Device } from "../../devices/devicesApi";
import { SectionFeedback } from "../../components/SectionFeedback";
import { initialResource, type SectionResource } from "../../components/useSectionResource";
import { useEvidenceClock } from "../../components/useEvidenceClock";
export function AlertInbox({
  alerts,
  deviceById,
  locationById,
  ruleById,
  attemptsByAlert,
  expandedAttempts,
  onRetryAttempts,
  onAttempts,
  onUpdate,
  isPending = () => false,
}: {
  alerts: AlertResponse[];
  deviceById: Map<string, Device>;
  locationById: Map<string, string>;
  ruleById: Map<string, AlertRule>;
  attemptsByAlert: Record<string, SectionResource<NotificationAttemptResponse[]>>;
  expandedAttempts: Record<string, boolean>;
  onRetryAttempts: (alertId: string) => Promise<void>;
  onAttempts: (alertId: string) => Promise<void>;
  onUpdate: (alertId: string, action: "ack" | "resolve") => Promise<void>;
  isPending?: (alertId: string, action: "ack" | "resolve") => boolean;
}) {
  useEvidenceClock();
  return (
    <section className="panel alert-inbox-panel">
      <div className="section-header">
        <div>
          <h2>Inbox</h2>
          <p>{alerts.length} alerts in the current filters</p>
        </div>
      </div>
      {alerts.length === 0 ? (
        <p className="muted-text">No alerts.</p>
      ) : (
        <div className="alert-inbox-list">
          <div className="alert-inbox-row alert-inbox-head">
            <span>Severity</span>
            <span>Status</span>
            <span>Alert</span>
            <span>Device</span>
            <span>Rule</span>
            <span>Age</span>
            <span>Actions</span>
          </div>
          {alerts.map((alert) => {
            const device = deviceById.get(alert.deviceId);
            const location = device ? locationById.get(device.locationId ?? "") : null;
            const rule = alert.ruleId ? ruleById.get(alert.ruleId) : null;
            const attempts = attemptsByAlert[alert.id] ?? initialResource<NotificationAttemptResponse[]>();
            return (
              <article className={`alert-inbox-item severity-${alert.severity.toLowerCase()}`} key={alert.id}>
                <div className="alert-inbox-row">
                  <span className={`status-pill severity-pill severity-${alert.severity.toLowerCase()}`}>{formatEnum(alert.severity)}</span>
                  <span className={`status-pill alert-status-${alert.status.toLowerCase()}`}>{formatEnum(alert.status)}</span>
                  <div className="alert-inbox-main">
                    <strong>{formatAlertTitle(alert)}</strong>
                    <span>{formatAlertDescription(alert)}</span>
                  </div>
                  <div className="alert-inbox-main">
                    <strong>{device?.name ?? "Unknown device"}</strong>
                    <span>{location ?? "No location"}</span>
                  </div>
                  <div className="alert-inbox-main">
                    <strong>{rule?.name ?? formatRuleLabel(alert.ruleId)}</strong>
                    <span>{rule ? formatEnum(rule.ruleType) : "Rule details unavailable"}</span>
                  </div>
                  <span>{formatRelativeAge(alert.createdAt)}</span>
                  <div className="action-row alert-row-actions">
                    <Link className="ghost-button button-link" to={`/devices/${alert.deviceId}`}>
                      Device
                    </Link>
                    <Link className="ghost-button button-link" to={telemetryLink(alert, rule)}>
                      Telemetry
                    </Link>
                    {alert.ruleId ? (
                      <Link className="ghost-button button-link" to={`/alerts?ruleId=${encodeURIComponent(alert.ruleId)}`}>
                        Rule
                      </Link>
                    ) : null}
                    <button className="ghost-button" type="button" aria-label={`${expandedAttempts[alert.id] ? "Hide" : "Review"} delivery attempts for ${alert.title}`} onClick={() => void onAttempts(alert.id)}>
                      {expandedAttempts[alert.id] ? "Hide" : "Details"}
                    </button>
                    {alert.status === "OPEN" ? (
                      <button className="primary-button" type="button" aria-label={`Acknowledge ${alert.title}`} disabled={isPending(alert.id, "ack")} onClick={() => void onUpdate(alert.id, "ack")}>
                        Acknowledge
                      </button>
                    ) : null}
                    {alert.status === "ACKNOWLEDGED" ? (
                      <button className="primary-button" type="button" aria-label={`Resolve ${alert.title}`} disabled={isPending(alert.id, "resolve")} onClick={() => void onUpdate(alert.id, "resolve")}>
                        Resolve
                      </button>
                    ) : null}
                  </div>
                </div>
                {alert.status === "RESOLVED" ? (
                  <p className="resolution-reason">
                    Resolution reason: <strong>{alert.resolutionReason || "No reason recorded."}</strong>
                  </p>
                ) : null}
                {expandedAttempts[alert.id] ? (
                  <div className="alert-inbox-details">
                    <p className="status-note">Assignee: {alert.assignedTo ? "Recorded by the server" : "Unassigned"}. Assignment changes are unavailable in the current API.</p>
                    <AlertTrace alert={alert} />
                    <NotificationAttempts resource={attempts} onRetry={() => void onRetryAttempts(alert.id)} />
                  </div>
                ) : null}
              </article>
            );
          })}
        </div>
      )}
    </section>
  );
}

function AlertTrace({ alert }: { alert: AlertResponse }) {
  const items = [
    ["Alert", alert.id],
    ["Device", alert.deviceId],
    ["Assignee", alert.assignedTo],
    ["Source", alert.sourceEventId],
    ["Anomaly", alert.anomalyId],
    ["Risk", alert.riskScoreId],
    ["Rule", alert.ruleId],
    ["Window", formatWindow(alert.calculationWindowStart, alert.calculationWindowEnd)],
    ["Input", alert.calculationInputHash],
  ].filter(([, value]) => value);
  if (items.length === 0) {
    return null;
  }
  return (
    <details><summary>Evidence identifiers and interval</summary><dl className="alert-trace-list">
      {items.map(([label, value]) => (
        <div key={label}>
          <dt>{label}</dt>
          <dd>{value}</dd>
        </div>
      ))}
    </dl></details>
  );
}

function NotificationAttempts({ resource, onRetry }: { resource: SectionResource<NotificationAttemptResponse[]>; onRetry: () => void }) {
  return (
    <div className="notification-attempts">
      <SectionFeedback resource={resource} label="delivery attempts" onRetry={onRetry} emptyMessage="No delivery attempts recorded." />
      {resource.data?.map((attempt) => (
        <div className="notification-attempt-row" key={attempt.id}>
          <span>{formatEnum(attempt.channelType)}</span>
          <strong>{formatEnum(attempt.status)}</strong>
          <span>{attempt.lastErrorCode || attempt.providerResponseSummary || formatShortDateTime(attempt.createdAt)}</span>
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

function telemetryLink(alert: AlertResponse, rule?: AlertRule | null) {
  const params = new URLSearchParams();
  params.set("tab", "telemetry");
  params.set("metric", rule?.metric || inferAlertMetric(alert));
  params.set("alertId", alert.id);
  const windowStart = alert.calculationWindowStart ?? offsetIso(alert.createdAt, -15);
  const windowEnd = alert.calculationWindowEnd ?? offsetIso(alert.createdAt, 15);
  params.set("from", windowStart);
  params.set("to", windowEnd);
  return `/devices/${alert.deviceId}?${params.toString()}`;
}

function inferAlertMetric(alert: AlertResponse) {
  const value = `${alert.alertType} ${alert.title} ${alert.description}`.toLowerCase();
  if (value.includes("voltage")) {
    return "voltageV";
  }
  if (value.includes("current")) {
    return "currentA";
  }
  if (value.includes("energy") || value.includes("consumption")) {
    return "energyWh";
  }
  return "activePowerW";
}

function offsetIso(value: string, minutes: number) {
  return new Date(new Date(value).getTime() + minutes * 60 * 1000).toISOString();
}

function formatRuleLabel(ruleId?: string | null) {
  return ruleId ? shortId(ruleId) : "No rule";
}

export function formatRuleScope(rule: AlertRule) {
  if (rule.scopeType === "DEVICE_TYPE" && rule.deviceType) {
    return `Device Type ${formatEnum(rule.deviceType)}`;
  }
  if ((rule.scopeType === "LOCATION" || rule.scopeType === "DEVICE") && rule.scopeId) {
    return `${formatEnum(rule.scopeType)} ${shortId(rule.scopeId)}`;
  }
  return formatEnum(rule.scopeType);
}

export function severityRank(value: string) {
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

export function formatEnum(value: string) {
  return value
    .toLowerCase()
    .split(/[_\s-]+/)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

export function fixKnownTypos(value: string) {
  return value.replace(/\bKithen\b/gi, "Kitchen");
}

export function formatShortDateTime(value: string) {
  return new Date(value).toLocaleString([], {
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
  });
}

function formatRelativeAge(value: string) {
  const deltaSeconds = Math.max(0, Math.floor((Date.now() - new Date(value).getTime()) / 1000));
  if (deltaSeconds < 60) {
    return `${deltaSeconds}s`;
  }
  const deltaMinutes = Math.floor(deltaSeconds / 60);
  if (deltaMinutes < 60) {
    return `${deltaMinutes}m`;
  }
  const deltaHours = Math.floor(deltaMinutes / 60);
  if (deltaHours < 48) {
    return `${deltaHours}h`;
  }
  return `${Math.floor(deltaHours / 24)}d`;
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
