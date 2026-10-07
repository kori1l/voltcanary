import { AlertInbox, formatEnum, severityRank, fixKnownTypos, formatShortDateTime, formatRuleScope } from "../features/alerts/AlertInbox";
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
import { useAlertConsoleData } from "../features/alerts/useAlertConsoleData";
import { SectionFeedback } from "../components/SectionFeedback";
import { useActionConfirmation } from "../components/useActionConfirmation";
import { AlertRuleEditor } from "../features/alerts/AlertRuleEditor";

const ALERT_LIMIT = 100;

function parseAlertView(value: string | null): "inbox" | "rules" {
  if (value === "rules") {
    return value;
  }
  return "inbox";
}

export function AlertsPage() {
  const confirmation = useActionConfirmation();
  const [searchParams, setSearchParams] = useSearchParams();
  const [attemptsByAlert, setAttemptsByAlert] = useState<Record<string, NotificationAttemptResponse[]>>({});
  const [expandedAttempts, setExpandedAttempts] = useState<Record<string, boolean>>({});
  const [activeView, setActiveView] = useState<"inbox" | "rules">(() => parseAlertView(searchParams.get("view")));
  const [deviceFilter, setDeviceFilter] = useState("all");
  const [severityFilter, setSeverityFilter] = useState("all");
  const [statusFilter, setStatusFilter] = useState("all");
  const [ruleFilter, setRuleFilter] = useState(() => searchParams.get("ruleId") ?? "all");
  const [ruleMessage, setRuleMessage] = useState<string | null>(null);
  const [editingRule, setEditingRule] = useState<AlertRule | null>(null);
  const [sortOrder, setSortOrder] = useState("severity");
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

  const data = useAlertConsoleData(requestFilters);
  const alerts = data.alerts.data ?? [];
  const summary = data.summary.data;
  const channels = data.channels.data ?? [];
  const rules = data.rules.data ?? [];
  const devices = data.devices.data ?? [];
  const locations = data.locations.data ?? [];
  const isLoading = data.alerts.refreshing || data.summary.refreshing;
  const load = data.load;
  const setRules = data.rules.update;

  useEffect(() => {
    void load();
  }, [deviceFilter, severityFilter, statusFilter, ruleFilter]);

  useEffect(() => {
    const urlRuleId = searchParams.get("ruleId") ?? "all";
    const urlView = searchParams.get("view");
    const urlMetric = searchParams.get("metric");
    const urlDeviceId = searchParams.get("deviceId");
    setRuleFilter(urlRuleId);
    if (urlRuleId !== "all") {
      setActiveView("inbox");
    }
    if (urlView) {
      setActiveView(parseAlertView(urlView));
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

  async function updateStatus(alertId: string, action: "ack" | "resolve") {
    setError(null);
    try {
      if (action === "ack") {
        await acknowledgeAlert(alertId);
      } else {
        const alert = alerts.find((item) => item.id === alertId);
        const reason = await confirmation.confirm({ title: "Resolve alert", object: alert?.title ?? alertId, impact: "This marks the incident resolved and records your reason in its history.", reasonRequired: true });
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
    if (await confirmation.confirm({ title: "Delete rule", object: rule.name, impact: "Removes this rule from future evaluation. Existing alert history remains." }) === null) return;
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
      .sort((left, right) => (sortOrder === "severity" ? severityRank(right.severity) - severityRank(left.severity) : sortOrder === "status" ? left.status.localeCompare(right.status) : 0) || new Date(right.createdAt).getTime() - new Date(left.createdAt).getTime());
  }, [alerts, statusFilter, sortOrder]);
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
      {confirmation.dialog}
      <header className="page-header page-header-row">
        <div>
          <h1>Alerts</h1>
          <p>Review active issues, delivery status, and source context from analytics.</p>
        </div>
        <button className="ghost-button" type="button" onClick={() => void load()} disabled={isLoading}>
          Refresh
        </button>
      </header>
      {activeView === "inbox" ? <label>Sort alerts<select value={sortOrder} onChange={(event) => setSortOrder(event.target.value)}><option value="severity">Severity, then newest</option><option value="time">Newest first</option><option value="status">Status, then newest</option></select></label> : null}

      <section className="alert-summary-panel">
        <div className="alert-summary-counts">
          <SummaryMetric label="Open" value={summary ? summary.byStatus.OPEN ?? 0 : undefined} />
          <SummaryMetric label="Acknowledged" value={summary ? summary.byStatus.ACKNOWLEDGED ?? 0 : undefined} />
          <SummaryMetric label="Resolved" value={summary ? summary.byStatus.RESOLVED ?? 0 : undefined} />
          <SummaryMetric label="Critical" value={summary ? summary.bySeverity.CRITICAL ?? 0 : undefined} />
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
              <strong>{data.alerts.data ? "No active alerts in loaded results" : "Alert evidence unavailable"}</strong>
              <span>{data.alerts.data ? "There are no open or acknowledged issues in the current filters." : "Load alerts before drawing conclusions."}</span>
            </>
          )}
        </div>
      </section>

      <SectionFeedback resource={data.summary} label="alert summary" onRetry={() => void data.summary.load(() => getAlertSummary(requestFilters))} />
      <SectionFeedback resource={data.alerts} label="alert inbox" onRetry={() => void data.alerts.load(() => listAlerts(requestFilters), (items) => !items.length)} />
      <SectionFeedback resource={data.devices} label="device names" onRetry={() => void data.devices.load(listDevices)} />
      <SectionFeedback resource={data.locations} label="location names" onRetry={() => void data.locations.load(listLocations)} />
      <SectionFeedback resource={data.rules} label="alert rules" onRetry={() => void data.rules.load(listAlertRules)} />
      <SectionFeedback resource={data.channels} label="notification channels" onRetry={() => void data.channels.load(listNotificationChannels)} />

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
          <label>Device<select value={deviceFilter} onChange={(event) => setDeviceFilter(event.target.value)}>
            <option value="all">All devices</option>
            {devices.map((device) => (
              <option value={device.id} key={device.id}>
                {device.name}
              </option>
            ))}
          </select></label>
          <label>Severity<select value={severityFilter} onChange={(event) => setSeverityFilter(event.target.value)}>
            <option value="all">All severities</option>
            {severities.map((severity) => (
              <option value={severity} key={severity}>
                {formatEnum(severity)}
              </option>
            ))}
          </select></label>
          <label>Status<select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)}>
            <option value="active">Active only</option>
            <option value="all">All statuses</option>
            <option value="OPEN">Open</option>
            <option value="ACKNOWLEDGED">Acknowledged</option>
            <option value="RESOLVED">Resolved</option>
          </select></label>
          <label>Rule<select value={ruleFilter} onChange={(event) => updateRuleFilter(event.target.value)}>
            <option value="all">All rules</option>
            {rules.map((rule) => (
              <option value={rule.id} key={rule.id}>
                {rule.name}
              </option>
            ))}
          </select></label>
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
          <AlertRuleEditor key={editingRule?.id ?? `${searchParams.get("metric")}:${searchParams.get("deviceId")}`} existing={editingRule ?? undefined} devices={devices} locations={locations} channels={channels} metric={searchParams.get("metric") ?? undefined} deviceId={searchParams.get("deviceId")} from={searchParams.get("from")} to={searchParams.get("to")} onCancel={editingRule ? () => setEditingRule(null) : undefined} onSaved={(rule) => { setRules((current) => [rule, ...current.filter((item) => item.id !== rule.id)]); setEditingRule(null); setRuleMessage("Rule saved"); }} />
          {ruleMessage ? <div className="status-note" role="status">{ruleMessage}</div> : null}          <div className="notification-channel-list">
            {rules.map((rule) => (
              <article className="notification-channel-row" key={rule.id}>
                <div>
                  <strong>{rule.name}</strong>
                  <span>
                    {formatEnum(rule.ruleType)} - {formatEnum(rule.metric)} - {formatRuleScope(rule)} - {formatEnum(rule.severity)} - {rule.enabled ? "Enabled" : "Disabled"}
                  </span>
                </div>
                <div className="action-row">
                  <button type="button" className="ghost-button" onClick={() => setEditingRule(rule)}>Edit rule</button>
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

      {activeView === "inbox" && data.alerts.data !== null ? (
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

function SummaryMetric({ label, value }: { label: string; value: number | undefined }) {
  return (
    <article>
      <strong>{value ?? "Unavailable"}</strong>
      <span>{label}</span>
    </article>
  );
}
