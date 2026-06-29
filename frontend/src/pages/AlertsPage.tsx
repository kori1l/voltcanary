import { FormEvent, useEffect, useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import {
  acknowledgeAlert,
  AlertRule,
  AlertResponse,
  AlertSummaryResponse,
  createAlertRule,
  deleteAlertRule,
  disableAlertRule,
  enableAlertRule,
  getAlertSummary,
  listAlerts,
  listAlertRules,
  listNotificationAttempts,
  listNotificationChannels,
  NotificationAttemptResponse,
  NotificationChannelResponse,
  resolveAlert,
  testAlertRule,
} from "../alerts/alertsApi";
import { Device, listDevices, listLocations, Location } from "../devices/devicesApi";

const ALERT_LIMIT = 100;

function parseAlertView(value: string | null): "inbox" | "rules" {
  if (value === "rules") {
    return value;
  }
  return "inbox";
}

export function AlertsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [alerts, setAlerts] = useState<AlertResponse[]>([]);
  const [summary, setSummary] = useState<AlertSummaryResponse | null>(null);
  const [channels, setChannels] = useState<NotificationChannelResponse[]>([]);
  const [rules, setRules] = useState<AlertRule[]>([]);
  const [attemptsByAlert, setAttemptsByAlert] = useState<Record<string, NotificationAttemptResponse[]>>({});
  const [expandedAttempts, setExpandedAttempts] = useState<Record<string, boolean>>({});
  const [devices, setDevices] = useState<Device[]>([]);
  const [locations, setLocations] = useState<Location[]>([]);
  const [activeView, setActiveView] = useState<"inbox" | "rules">(() => parseAlertView(searchParams.get("view")));
  const [deviceFilter, setDeviceFilter] = useState("all");
  const [severityFilter, setSeverityFilter] = useState("all");
  const [statusFilter, setStatusFilter] = useState("all");
  const [ruleFilter, setRuleFilter] = useState(() => searchParams.get("ruleId") ?? "all");
  const [ruleName, setRuleName] = useState("Power spike rule");
  const [ruleType, setRuleType] = useState("POWER_SPIKE");
  const [ruleMetric, setRuleMetric] = useState("activePowerW");
  const [ruleCondition, setRuleCondition] = useState("BASELINE_MULTIPLIER");
  const [ruleThreshold, setRuleThreshold] = useState("");
  const [ruleMultiplier, setRuleMultiplier] = useState("1.8");
  const [ruleSeverity, setRuleSeverity] = useState("WARNING");
  const [ruleScopeType, setRuleScopeType] = useState("FLEET");
  const [ruleScopeId, setRuleScopeId] = useState("");
  const [ruleDeviceType, setRuleDeviceType] = useState("");
  const [ruleChannelIds, setRuleChannelIds] = useState<string[]>([]);
  const [ruleTestFrom, setRuleTestFrom] = useState<string | null>(searchParams.get("from"));
  const [ruleTestTo, setRuleTestTo] = useState<string | null>(searchParams.get("to"));
  const [ruleMessage, setRuleMessage] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [loadingAttempts, setLoadingAttempts] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const serverStatusFilter = statusFilter === "active" || statusFilter === "all" ? undefined : statusFilter;
  const requestFilters = {
    deviceId: deviceFilter === "all" ? undefined : deviceFilter,
    severity: severityFilter === "all" ? undefined : severityFilter,
    status: serverStatusFilter,
    ruleId: ruleFilter === "all" ? undefined : ruleFilter,
    limit: ALERT_LIMIT,
  };

  async function load() {
    setIsLoading(true);
    setError(null);
    try {
      const [alertsResponse, summaryResponse, channelsResponse, rulesResponse, devicesResponse, locationsResponse] = await Promise.all([
        listAlerts(requestFilters),
        getAlertSummary(requestFilters),
        listNotificationChannels().catch(() => []),
        listAlertRules().catch(() => []),
        listDevices().catch(() => []),
        listLocations().catch(() => []),
      ]);
      setAlerts(alertsResponse);
      setSummary(summaryResponse);
      setChannels(channelsResponse);
      setRules(rulesResponse);
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
  }, [deviceFilter, severityFilter, statusFilter, ruleFilter]);

  useEffect(() => {
    const urlRuleId = searchParams.get("ruleId") ?? "all";
    const urlView = searchParams.get("view");
    const urlMetric = searchParams.get("metric");
    const urlDeviceId = searchParams.get("deviceId");
    setRuleFilter(urlRuleId);
    setRuleTestFrom(searchParams.get("from"));
    setRuleTestTo(searchParams.get("to"));
    if (urlRuleId !== "all") {
      setActiveView("inbox");
    }
    if (urlView) {
      setActiveView(parseAlertView(urlView));
    }
    if (urlMetric) {
      setRuleMetric(urlMetric);
      setRuleName(`${formatEnum(urlMetric)} rule`);
    }
    if (urlDeviceId) {
      setRuleScopeType("DEVICE");
      setRuleScopeId(urlDeviceId);
    }
  }, [searchParams]);

  function updateRuleFilter(nextRuleId: string) {
    setRuleFilter(nextRuleId);
    const nextSearchParams = new URLSearchParams(searchParams);
    if (nextRuleId === "all") {
      nextSearchParams.delete("ruleId");
    } else {
      nextSearchParams.set("ruleId", nextRuleId);
    }
    setSearchParams(nextSearchParams);
  }

  function buildRuleRequest() {
    return {
      name: ruleName.trim(),
      description: null,
      ruleType,
      enabled: true,
      scopeType: ruleScopeType,
      scopeId: ruleScopeType === "LOCATION" || ruleScopeType === "DEVICE" ? ruleScopeId || null : null,
      deviceType: ruleScopeType === "DEVICE_TYPE" ? ruleDeviceType || null : null,
      metric: ruleMetric.trim(),
      conditionType: ruleCondition,
      threshold: ruleThreshold.trim() ? Number(ruleThreshold) : null,
      thresholdUnit: ruleMetric.toLowerCase().includes("power") ? "W" : null,
      baselineMultiplier: ruleMultiplier.trim() ? Number(ruleMultiplier) : null,
      severity: ruleSeverity,
      cooldownSeconds: 900,
      deduplicationWindowSeconds: 900,
      notificationChannelIds: ruleChannelIds,
      config: {},
    };
  }

  function setRuleChannel(channelId: string, selected: boolean) {
    setRuleChannelIds((current) => (selected ? [...new Set([...current, channelId])] : current.filter((item) => item !== channelId)));
  }

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

  async function addRule(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setRuleMessage(null);
    try {
      const created = await createAlertRule(buildRuleRequest());
      setRules((current) => [created, ...current]);
      setRuleMessage("Rule created");
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to create rule");
    }
  }

  async function previewRule() {
    setError(null);
    setRuleMessage(null);
    try {
      const result = await testAlertRule({
        deviceId: ruleScopeType === "DEVICE" ? ruleScopeId : undefined,
        rule: buildRuleRequest(),
        from: ruleTestFrom ?? undefined,
        to: ruleTestTo ?? undefined,
      });
      setRuleMessage(`${result.previewTitle}: ${result.previewDescription}`);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to test rule");
    }
  }

  async function setRuleEnabled(rule: AlertRule, enabled: boolean) {
    setError(null);
    try {
      const updated = enabled ? await enableAlertRule(rule.id) : await disableAlertRule(rule.id);
      setRules((current) => current.map((item) => (item.id === updated.id ? updated : item)));
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to update rule");
    }
  }

  async function removeRule(rule: AlertRule) {
    setError(null);
    try {
      await deleteAlertRule(rule.id);
      setRules((current) => current.filter((item) => item.id !== rule.id));
      if (ruleFilter === rule.id) {
        updateRuleFilter("all");
      }
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to delete rule");
    }
  }

  const deviceById = useMemo(() => new Map(devices.map((device) => [device.id, device])), [devices]);
  const locationById = useMemo(() => new Map(locations.map((location) => [location.id, fixKnownTypos(location.name)])), [locations]);
  const ruleById = useMemo(() => new Map(rules.map((rule) => [rule.id, rule])), [rules]);
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
  const deviceTypes = Array.from(new Set(devices.map((device) => device.type).filter(Boolean))).sort();

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
          <button className={activeView === "inbox" ? "primary-button" : "ghost-button"} type="button" onClick={() => setActiveView("inbox")}>
            Inbox
          </button>
          <button className={activeView === "rules" ? "primary-button" : "ghost-button"} type="button" onClick={() => setActiveView("rules")}>
            Rules
          </button>
        </div>
      </section>

      {activeView === "inbox" ? <section className="panel alerts-filter-panel">
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
          <select value={ruleFilter} onChange={(event) => updateRuleFilter(event.target.value)}>
            <option value="all">All rules</option>
            {rules.map((rule) => (
              <option value={rule.id} key={rule.id}>
                {rule.name}
              </option>
            ))}
          </select>
          {ruleFilter !== "all" ? (
            <button className="ghost-button" type="button" onClick={() => updateRuleFilter("all")}>
              Clear rule
            </button>
          ) : null}
        </div>
      </section> : null}

      {activeView === "rules" ? (
        <section className="panel">
          <div className="section-header">
            <div>
              <h2>Alert Rules</h2>
              <p>{rules.length} configured</p>
            </div>
          </div>
          <form className="notification-channel-form" onSubmit={addRule}>
            <input value={ruleName} onChange={(event) => setRuleName(event.target.value)} placeholder="Rule name" />
            <select value={ruleType} onChange={(event) => setRuleType(event.target.value)}>
              <option value="POWER_SPIKE">Power spike</option>
              <option value="CONSUMPTION_GROWTH">Consumption growth</option>
              <option value="LONG_CYCLE">Long cycle</option>
              <option value="THRESHOLD">Threshold</option>
              <option value="OFFLINE">Offline</option>
            </select>
            <input value={ruleMetric} onChange={(event) => setRuleMetric(event.target.value)} placeholder="Metric" />
            <select value={ruleCondition} onChange={(event) => setRuleCondition(event.target.value)}>
              <option value="BASELINE_MULTIPLIER">Baseline multiplier</option>
              <option value="ABOVE_THRESHOLD">Above threshold</option>
              <option value="BELOW_THRESHOLD">Below threshold</option>
              <option value="GROWTH_RATE">Growth rate</option>
              <option value="LONG_HIGH_RUN">Long high run</option>
              <option value="MISSING_TELEMETRY">Missing telemetry</option>
            </select>
            {ruleCondition === "ABOVE_THRESHOLD" || ruleCondition === "BELOW_THRESHOLD" ? (
              <input value={ruleThreshold} onChange={(event) => setRuleThreshold(event.target.value)} placeholder="Threshold" type="number" step="0.1" />
            ) : (
              <input value={ruleMultiplier} onChange={(event) => setRuleMultiplier(event.target.value)} placeholder="Multiplier" type="number" step="0.1" />
            )}
            <select value={ruleSeverity} onChange={(event) => setRuleSeverity(event.target.value)}>
              <option value="WARNING">Warning</option>
              <option value="HIGH">High</option>
              <option value="CRITICAL">Critical</option>
              <option value="INFO">Info</option>
            </select>
            <select
              value={ruleScopeType}
              onChange={(event) => {
                setRuleScopeType(event.target.value);
                setRuleScopeId("");
                setRuleDeviceType("");
              }}
            >
              <option value="FLEET">Whole fleet</option>
              <option value="LOCATION">Location</option>
              <option value="DEVICE_TYPE">Device type</option>
              <option value="DEVICE">Single device</option>
            </select>
            {ruleScopeType === "LOCATION" ? (
              <select value={ruleScopeId} onChange={(event) => setRuleScopeId(event.target.value)} required>
                <option value="">Choose location</option>
                {locations.map((location) => (
                  <option value={location.id} key={location.id}>
                    {fixKnownTypos(location.name)}
                  </option>
                ))}
              </select>
            ) : null}
            {ruleScopeType === "DEVICE_TYPE" ? (
              <select value={ruleDeviceType} onChange={(event) => setRuleDeviceType(event.target.value)} required>
                <option value="">Choose device type</option>
                {deviceTypes.map((deviceType) => (
                  <option value={deviceType} key={deviceType}>
                    {formatEnum(deviceType)}
                  </option>
                ))}
              </select>
            ) : null}
            {ruleScopeType === "DEVICE" ? (
              <select value={ruleScopeId} onChange={(event) => setRuleScopeId(event.target.value)} required>
                <option value="">Choose device</option>
                {devices.map((device) => (
                  <option value={device.id} key={device.id}>
                    {device.name}
                  </option>
                ))}
              </select>
            ) : null}
            <button className="ghost-button" type="button" onClick={() => void previewRule()}>
              Test rule
            </button>
            <button className="primary-button" type="submit">
              Create rule
            </button>
          </form>
          {channels.length > 0 ? (
            <div className="notification-channel-list">
              {channels.map((channel) => (
                <label className="notification-attempt-row" key={channel.id}>
                  <input
                    type="checkbox"
                    checked={ruleChannelIds.includes(channel.id)}
                    onChange={(event) => setRuleChannel(channel.id, event.target.checked)}
                  />
                  <span>{channel.name}</span>
                  <strong>{formatEnum(channel.channelType)}</strong>
                </label>
              ))}
            </div>
          ) : null}
          {ruleMessage ? <div className="success-message">{ruleMessage}</div> : null}
          <div className="notification-channel-list">
            {rules.map((rule) => (
              <article className="notification-channel-row" key={rule.id}>
                <div>
                  <strong>{rule.name}</strong>
                  <span>
                    {formatEnum(rule.ruleType)} - {formatEnum(rule.metric)} - {formatRuleScope(rule)} - {formatEnum(rule.severity)} - {rule.enabled ? "Enabled" : "Disabled"}
                  </span>
                </div>
                <div className="action-row">
                  <button className="ghost-button" type="button" onClick={() => void setRuleEnabled(rule, !rule.enabled)}>
                    {rule.enabled ? "Disable" : "Enable"}
                  </button>
                  <Link className="ghost-button button-link" to={`/alerts?ruleId=${encodeURIComponent(rule.id)}`}>
                    View alerts
                  </Link>
                  <button className="ghost-button" type="button" onClick={() => void removeRule(rule)}>
                    Delete
                  </button>
                </div>
              </article>
            ))}
          </div>
        </section>
      ) : null}

      {error ? <div className="error-message">{error}</div> : null}
      {activeView === "inbox" && isLoading ? <section className="panel">Loading alerts...</section> : null}

      {activeView === "inbox" && !isLoading ? (
        <AlertInbox
          alerts={visibleAlerts}
          deviceById={deviceById}
          locationById={locationById}
          ruleById={ruleById}
          attemptsByAlert={attemptsByAlert}
          expandedAttempts={expandedAttempts}
          loadingAttempts={loadingAttempts}
          onAttempts={toggleAttempts}
          onUpdate={updateStatus}
        />
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

function AlertInbox({
  alerts,
  deviceById,
  locationById,
  ruleById,
  attemptsByAlert,
  expandedAttempts,
  loadingAttempts,
  onAttempts,
  onUpdate,
}: {
  alerts: AlertResponse[];
  deviceById: Map<string, Device>;
  locationById: Map<string, string>;
  ruleById: Map<string, AlertRule>;
  attemptsByAlert: Record<string, NotificationAttemptResponse[]>;
  expandedAttempts: Record<string, boolean>;
  loadingAttempts: string | null;
  onAttempts: (alertId: string) => Promise<void>;
  onUpdate: (alertId: string, action: "ack" | "resolve") => Promise<void>;
}) {
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
            const attempts = attemptsByAlert[alert.id] ?? [];
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
                    <button className="ghost-button" type="button" onClick={() => void onAttempts(alert.id)}>
                      {expandedAttempts[alert.id] ? "Hide" : "Details"}
                    </button>
                    {alert.status === "OPEN" ? (
                      <button className="primary-button" type="button" onClick={() => void onUpdate(alert.id, "ack")}>
                        Ack
                      </button>
                    ) : null}
                    {alert.status === "ACKNOWLEDGED" ? (
                      <button className="primary-button" type="button" onClick={() => void onUpdate(alert.id, "resolve")}>
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
                    <AlertTrace alert={alert} />
                    <NotificationAttempts attempts={attempts} isLoading={loadingAttempts === alert.id} />
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

function formatRuleScope(rule: AlertRule) {
  if (rule.scopeType === "DEVICE_TYPE" && rule.deviceType) {
    return `Device Type ${formatEnum(rule.deviceType)}`;
  }
  if ((rule.scopeType === "LOCATION" || rule.scopeType === "DEVICE") && rule.scopeId) {
    return `${formatEnum(rule.scopeType)} ${shortId(rule.scopeId)}`;
  }
  return formatEnum(rule.scopeType);
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
