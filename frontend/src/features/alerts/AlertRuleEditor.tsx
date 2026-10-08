import { FormEvent, useState } from "react";
import { AlertRule, AlertRuleRequest, AlertRuleTestResponse, createAlertRule, NotificationChannelResponse, testAlertRule, updateAlertRule, listAlertRules } from "../../alerts/alertsApi";
import { Device, Location } from "../../devices/devicesApi";
import { EntitySelector } from "../../components/EntitySelector";
import { DEFAULT_METRIC_DEFINITIONS } from "../telemetry/model/telemetry.constants";
import { getMetricDefinition } from "../telemetry/model/telemetry.utils";
import { TimezoneNote } from "../../components/TimeDisplay";
import { decodeDraftFields, draftScope, sessionUserId } from "../../components/scopedDrafts";
import { useScopedDraftValue } from "../../components/useScopedDraftValue";
import { markWorkflowDraftSaved, useWorkflowDraft } from "../../components/workflowDraftContext";
import { CommandFeedback } from "../../components/CommandFeedback";
import { useEntityCommands } from "../../components/useEntityCommands";
const conditions = ["BASELINE_MULTIPLIER", "ABOVE_THRESHOLD", "BELOW_THRESHOLD", "GROWTH_RATE", "LONG_HIGH_RUN", "MISSING_TELEMETRY"];
export function AlertRuleEditor({ existing, devices, locations, channels, metric = "activePowerW", deviceId, from, to, onSaved, onCancel }: {
  existing?: AlertRule; devices: Device[]; locations: Location[]; channels: NotificationChannelResponse[]; metric?: string; deviceId?: string | null; from?: string | null; to?: string | null;
  onSaved: (rule: AlertRule) => void; onCancel?: () => void;
}) {
  const commands = useEntityCommands();
  const [matchingRecords, setMatchingRecords] = useState<AlertRule[]>([]);
  const commandKey = existing ? `rule:${existing.id}:update` : "rule:create";
  const initial: AlertRuleRequest = existing ? {
    name: existing.name, description: existing.description, ruleType: existing.ruleType, enabled: existing.enabled, scopeType: existing.scopeType, scopeId: existing.scopeId, deviceType: existing.deviceType,
    metric: existing.metric, conditionType: existing.conditionType, threshold: existing.threshold, thresholdUnit: existing.thresholdUnit, baselineMultiplier: existing.baselineMultiplier, minPoints: existing.minPoints, severity: existing.severity,
    cooldownSeconds: existing.cooldownSeconds, deduplicationWindowSeconds: existing.deduplicationWindowSeconds, notificationChannelIds: existing.notificationChannelIds, config: existing.config,
  } : { name: "Power spike rule", ruleType: "POWER_SPIKE", enabled: true, scopeType: deviceId ? "DEVICE" : "FLEET", scopeId: deviceId || null, metric, conditionType: "BASELINE_MULTIPLIER", threshold: null, baselineMultiplier: 1.8, severity: "WARNING", cooldownSeconds: 900, deduplicationWindowSeconds: 900, notificationChannelIds: [], config: {} };
  const scope = draftScope(existing?.accountId ?? sessionUserId(), existing?.id ?? "new-alert-rule", String(existing?.version ?? "new-v1"));
  const { config: initialConfig, ...persistedInitial } = initial;
  const allowedFields = [...Object.keys(persistedInitial), "description", "deviceType", "thresholdUnit", "minPoints"];
  const [draft, setDraft, clearDraft, recovered] = useScopedDraftValue(scope, initial, (value) => {
    const { threshold, baselineMultiplier, minPoints, ...nonNumeric } = value;
    const { threshold: initialThreshold, baselineMultiplier: initialMultiplier, minPoints: initialPoints, ...defaults } = persistedInitial;
    const fields = decodeDraftFields(nonNumeric, { ...defaults, description: defaults.description ?? null, deviceType: defaults.deviceType ?? null, thresholdUnit: defaults.thresholdUnit ?? null }) as Partial<AlertRuleRequest> | null;
    if (!fields || fields.conditionType && !conditions.includes(fields.conditionType) || fields.scopeType && !["FLEET", "LOCATION", "DEVICE_TYPE", "DEVICE"].includes(fields.scopeType)) return null;
    for (const [key, field] of Object.entries({ threshold, baselineMultiplier, minPoints })) {
      if (field === undefined) continue;
      if (field !== null && (typeof field !== "number" || !Number.isFinite(field))) return null;
      (fields as Record<string, unknown>)[key] = field;
    }
    return fields;
  }, undefined, allowedFields);
  useWorkflowDraft(recovered, clearDraft);
  const local = (value: string | null | undefined, fallback: number) => { const date = value && Number.isFinite(Date.parse(value)) ? new Date(value) : new Date(fallback); return new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 19); };
  const [testFrom, setTestFrom] = useState(local(from, Date.now() - 86_400_000));
  const [testTo, setTestTo] = useState(local(to, Date.now()));
  const [result, setResult] = useState<AlertRuleTestResponse | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const update = (values: Partial<AlertRuleRequest>) => { setDraft((current) => ({ ...current, ...values })); setResult(null); };
  const thresholdRequired = ["ABOVE_THRESHOLD", "BELOW_THRESHOLD"].includes(draft.conditionType);
  const multiplierRequired = draft.conditionType === "BASELINE_MULTIPLIER";
  const pointsRequired = ["GROWTH_RATE", "LONG_HIGH_RUN"].includes(draft.conditionType);
  const valid = Boolean(draft.name.trim() && draft.metric && (!thresholdRequired || (draft.threshold !== null && draft.threshold !== undefined && Number.isFinite(draft.threshold))) && (!multiplierRequired || (draft.baselineMultiplier && draft.baselineMultiplier > 0)) && (!pointsRequired || (draft.minPoints && draft.minPoints >= 1)) && (!["LOCATION", "DEVICE"].includes(draft.scopeType) || draft.scopeId) && (draft.scopeType !== "DEVICE_TYPE" || draft.deviceType));
  const validInterval = Number.isFinite(Date.parse(testFrom)) && Number.isFinite(Date.parse(testTo)) && Date.parse(testFrom) < Date.parse(testTo);
  const payload = (): AlertRuleRequest => ({ ...draft, name: draft.name.trim(), threshold: thresholdRequired ? draft.threshold : null, thresholdUnit: thresholdRequired ? getMetricDefinition(draft.metric).unit || draft.thresholdUnit : null, baselineMultiplier: multiplierRequired ? draft.baselineMultiplier : null, minPoints: pointsRequired ? draft.minPoints : null });
  async function execute(test: boolean) {
    if (busy || !valid || (test && !validInterval)) return;
    setBusy(true); setError(null);
    try {
      if (test) setResult(await testAlertRule({ deviceId: draft.scopeType === "DEVICE" ? draft.scopeId || undefined : undefined, rule: payload(), from: new Date(testFrom).toISOString(), to: new Date(testTo).toISOString() }));
      else { await commands.run(commandKey, { label: existing ? "Save rule" : "Create rule", nonIdempotent: !existing, mutate: () => existing ? updateAlertRule(existing.id, payload()) : createAlertRule(payload()), onCommitted: (saved) => { clearDraft(); markWorkflowDraftSaved("alert-rule"); onSaved(saved); }, refresh: async () => { const records = await listAlertRules(); if (!Array.isArray(records)) throw new Error("Unavailable rules"); setMatchingRecords(records.filter((rule) => rule.name === draft.name && rule.metric === draft.metric && rule.scopeType === draft.scopeType && rule.scopeId === draft.scopeId)); return true; } }); }
    } catch (error) { setError(error instanceof Error ? error.message : "Rule request failed"); } finally { setBusy(false); }
  }
  return <form className="form-stack" data-draft-id="alert-rule" onSubmit={(event: FormEvent) => { event.preventDefault(); void execute(false); }}>
    <CommandFeedback outcomes={commands.outcomes} onRetryRead={commands.retryRead} />
    {commands.outcomes[commandKey]?.phase === "unknown-result" ? <div><p>Review matching rules before creating another. Names do not establish request identity.</p>{matchingRecords.map((rule) => <button type="button" key={rule.id} onClick={() => { commands.reconcile(commandKey, rule); clearDraft(); onSaved(rule); }}>Use matching rule {rule.name} · {rule.id}</button>)}</div> : null}
    {recovered ? <p role="status">Recovered rule draft for this account and base version. Review before saving.</p> : null}
    <h3>{existing ? `Edit ${existing.name}` : "Create rule"}</h3><div className="form-grid-two">
    <label>Rule name<input required value={draft.name} onChange={(event) => update({ name: event.target.value })} /></label>
    <label>Rule type<select value={draft.ruleType} onChange={(event) => update({ ruleType: event.target.value })}>{["POWER_SPIKE", "CONSUMPTION_GROWTH", "LONG_CYCLE", "THRESHOLD", "OFFLINE"].map((value) => <option key={value}>{value}</option>)}</select></label>
    <label>Metric<select value={draft.metric} onChange={(event) => update({ metric: event.target.value })}>{!DEFAULT_METRIC_DEFINITIONS.some((metric) => metric.key === draft.metric) ? <option value={draft.metric}>{draft.metric} (saved custom metric)</option> : null}{DEFAULT_METRIC_DEFINITIONS.map((metric) => <option key={metric.key} value={metric.key}>{metric.name} {metric.unit ? `(${metric.unit})` : ""}</option>)}</select></label>
    <label>Condition<select value={draft.conditionType} onChange={(event) => update({ conditionType: event.target.value })}>{conditions.map((value) => <option key={value}>{value}</option>)}</select></label>
    {thresholdRequired ? <label>Threshold ({getMetricDefinition(draft.metric).unit || "metric units"})<input required type="number" step="any" value={draft.threshold ?? ""} onChange={(event) => update({ threshold: event.target.value ? Number(event.target.value) : null })} /></label> : null}
    {multiplierRequired ? <label>Baseline multiplier<input required type="number" step="0.1" min="0.1" value={draft.baselineMultiplier ?? ""} onChange={(event) => update({ baselineMultiplier: Number(event.target.value) })} /></label> : null}
    {pointsRequired ? <label>Minimum points<input required type="number" min="1" step="1" value={draft.minPoints ?? ""} onChange={(event) => update({ minPoints: Number(event.target.value) })} /></label> : null}
    {draft.conditionType === "MISSING_TELEMETRY" ? <p>Missing telemetry is evaluated using the server offline criteria; no multiplier is required.</p> : null}
    <label>Severity<select value={draft.severity} onChange={(event) => update({ severity: event.target.value })}>{["WARNING", "HIGH", "CRITICAL", "INFO"].map((value) => <option key={value}>{value}</option>)}</select></label>
    <label>Scope<select value={draft.scopeType} onChange={(event) => update({ scopeType: event.target.value, scopeId: null, deviceType: null })}>{["FLEET", "LOCATION", "DEVICE_TYPE", "DEVICE"].map((value) => <option key={value}>{value}</option>)}</select></label></div>
    {draft.scopeType === "DEVICE" || draft.scopeType === "LOCATION" ? <EntitySelector label={draft.scopeType === "DEVICE" ? "Rule device" : "Rule location"} value={draft.scopeId || ""} onChange={(scopeId) => update({ scopeId })} options={draft.scopeType === "DEVICE" ? devices.map((device) => ({ id: device.id, name: device.name })) : locations.map((location) => ({ id: location.id, name: location.name }))} /> : null}
    {draft.scopeType === "DEVICE_TYPE" ? <label>Device type<input required list="rule-device-types" value={draft.deviceType ?? ""} onChange={(event) => update({ deviceType: event.target.value })} /><datalist id="rule-device-types">{Array.from(new Set(devices.map((device) => device.type))).map((type) => <option key={type}>{type}</option>)}</datalist></label> : null}
    <fieldset><legend>Notification channels</legend>{channels.map((channel) => <label className="checkbox-row" key={channel.id}><input type="checkbox" checked={draft.notificationChannelIds?.includes(channel.id) ?? false} onChange={(event) => update({ notificationChannelIds: event.target.checked ? [...(draft.notificationChannelIds ?? []), channel.id] : draft.notificationChannelIds?.filter((id) => id !== channel.id) })} />{channel.name} · {channel.channelType} · {channel.enabled ? "Enabled" : "Disabled"}</label>)}{!channels.length ? <p>No channel context available. Rule can be saved without delivery channels.</p> : null}</fieldset>
    <p className="status-note">If {getMetricDefinition(draft.metric).name} satisfies {draft.conditionType.replace(/_/g, " ").toLowerCase()} {thresholdRequired ? draft.threshold : multiplierRequired ? `× ${draft.baselineMultiplier}` : pointsRequired ? `over at least ${draft.minPoints ?? "?"} points` : ""}, then create a {draft.severity.toLowerCase()} alert for {draft.scopeType.toLowerCase()} and route to {draft.notificationChannelIds?.length ?? 0} selected channels.</p>
    <fieldset><legend>Test interval</legend><TimezoneNote /><div className="form-grid-two"><label>Test from<input type="datetime-local" step="1" value={testFrom} onChange={(event) => { setTestFrom(event.target.value); setResult(null); }} /></label><label>Test to<input type="datetime-local" step="1" value={testTo} onChange={(event) => { setTestTo(event.target.value); setResult(null); }} /></label></div><button className="ghost-button" type="button" disabled={busy || !valid || !validInterval} onClick={() => void execute(true)}>Test rule</button></fieldset>
    {result ? <p className="status-note" role="status">Test only: {result.previewTitle}. {result.previewDescription} Evaluation: {result.evaluationStatus}; matches: {result.matchCount}. No rule was created by this test.</p> : null}
    {error ? <p className="error-message" role="alert">{error}</p> : null}<div className="action-row">{onCancel ? <button className="ghost-button" type="button" onClick={onCancel}>Cancel editing</button> : null}<button className="primary-button" disabled={busy || commands.isBlocked(commandKey) || !valid}>{busy ? "Saving..." : existing ? "Save rule" : "Create rule"}</button></div>
  </form>;
}
