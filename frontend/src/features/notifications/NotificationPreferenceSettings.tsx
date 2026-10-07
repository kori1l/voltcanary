import { FormEvent, useEffect, useState } from "react";
import {
  getNotificationPreference,
  NotificationPreferenceResponse,
  updateNotificationPreference,
  listNotificationChannels,
  NotificationChannelResponse,
} from "../../alerts/alertsApi";

const severityOptions = ["WARNING", "HIGH", "CRITICAL"];
const channelTypeOptions = ["WEBHOOK", "EMAIL", "TELEGRAM", "SLACK", "SMS", "PUSH"];

export function NotificationPreferenceSettings() {
  const [preference, setPreference] = useState<NotificationPreferenceResponse | null>(null);
  const [enabled, setEnabled] = useState(true);
  const [severities, setSeverities] = useState<string[]>([]);
  const [channelTypes, setChannelTypes] = useState<string[]>([]);
  const [quietHoursStart, setQuietHoursStart] = useState("");
  const [quietHoursEnd, setQuietHoursEnd] = useState("");
  const [timezone, setTimezone] = useState("UTC");
  const [criticalBypassQuietHours, setCriticalBypassQuietHours] = useState(true);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [isBusy, setIsBusy] = useState(false);
  const [channels, setChannels] = useState<NotificationChannelResponse[] | null>(null);
  const timezoneOptions = [...new Set([timezone, "UTC", Intl.DateTimeFormat().resolvedOptions().timeZone, "Europe/Belgrade", "Europe/London", "America/New_York", "Asia/Tokyo"])];

  useEffect(() => {
    void loadPreference();
    listNotificationChannels().then(setChannels).catch(() => setChannels(null));
  }, []);

  async function loadPreference() {
    setError(null);
    try {
      const response = await getNotificationPreference();
      applyPreference(response);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to load notification preferences");
    }
  }

  function applyPreference(response: NotificationPreferenceResponse) {
    setPreference(response);
    setEnabled(response.enabled);
    setSeverities(response.severities ?? []);
    setChannelTypes(response.channelTypes ?? []);
    setQuietHoursStart(response.quietHoursStart ?? "");
    setQuietHoursEnd(response.quietHoursEnd ?? "");
    setTimezone(response.timezone || "UTC");
    setCriticalBypassQuietHours(response.criticalBypassQuietHours);
  }

  async function savePreference(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (isBusy || !preference) return;
    if (Boolean(quietHoursStart) !== Boolean(quietHoursEnd)) { setError("Set both quiet-hours times, or clear both."); return; }
    try { new Intl.DateTimeFormat(undefined, { timeZone: timezone }); } catch { setError("Choose a valid IANA timezone."); return; }
    setIsBusy(true);
    setMessage(null);
    setError(null);
    try {
      const response = await updateNotificationPreference({
        enabled,
        severities,
        channelTypes,
        quietHoursStart: quietHoursStart || null,
        quietHoursEnd: quietHoursEnd || null,
        timezone: timezone.trim() || "UTC",
        criticalBypassQuietHours,
      });
      applyPreference(response);
      setMessage("Notification preferences saved");
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to save notification preferences");
    } finally {
      setIsBusy(false);
    }
  }

  function toggleSelected(value: string, selected: boolean, current: string[], update: (next: string[]) => void) {
    update(selected ? [...new Set([...current, value])] : current.filter((item) => item !== value));
  }

  return (
    <section className="panel notification-channel-panel">
      <div className="section-header">
        <div>
          <h2>Notification Preferences</h2>
          <p>{preference ? `${preference.timezone} routing policy` : "Loading routing policy"}</p>
        </div>
        <button className="ghost-button" type="button" onClick={() => void loadPreference()} disabled={isBusy}>
          Refresh
        </button>
      </div>
      {message ? <div className="success-message account-message">{message}</div> : null}
      {error ? <div className="error-message account-message">{error}</div> : null}
      <form className="notification-channel-form" onSubmit={savePreference}>
        <label className="notification-attempt-row">
          <input type="checkbox" checked={enabled} onChange={(event) => setEnabled(event.target.checked)} />
          <span>Delivery enabled</span>
        </label>
        <fieldset className="notification-channel-list">
          <legend>Severities</legend>
          {severityOptions.map((severity) => (
            <label className="notification-attempt-row" key={severity}>
              <input
                type="checkbox"
                checked={severities.includes(severity)}
                onChange={(event) => toggleSelected(severity, event.target.checked, severities, setSeverities)}
              />
              <span>{formatEnum(severity)}</span>
            </label>
          ))}
        </fieldset>
        <fieldset className="notification-channel-list">
          <legend>Channel types</legend>
          {channelTypeOptions.map((channelType) => (
            <label className="notification-attempt-row" key={channelType}>
              <input
                type="checkbox"
                checked={channelTypes.includes(channelType)}
                onChange={(event) => toggleSelected(channelType, event.target.checked, channelTypes, setChannelTypes)}
              />
              <span>{formatEnum(channelType)}</span>
              <small>{!["WEBHOOK", "EMAIL", "TELEGRAM"].includes(channelType) ? "No current delivery adapter" : channels === null ? "Availability unknown" : channels.some((channel) => channel.channelType === channelType && channel.enabled) ? "Enabled channel configured; check its health" : "No enabled channel configured"}</small>
            </label>
          ))}
        </fieldset>
        <fieldset><legend>Quiet hours</legend><div className="form-grid-two"><label>Quiet hours start<input value={quietHoursStart} onChange={(event) => setQuietHoursStart(event.target.value)} type="time" /></label><label>Quiet hours end<input value={quietHoursEnd} onChange={(event) => setQuietHoursEnd(event.target.value)} type="time" /></label></div><label>Timezone<input list="routing-timezones" value={timezone} onChange={(event) => setTimezone(event.target.value)} required /><datalist id="routing-timezones">{timezoneOptions.map((zone) => <option key={zone} value={zone} />)}</datalist></label><p>Times follow this timezone, including overnight intervals. Leave both times blank to disable quiet hours.</p></fieldset>
        <label className="notification-attempt-row">
          <input type="checkbox" checked={criticalBypassQuietHours} onChange={(event) => setCriticalBypassQuietHours(event.target.checked)} />
          <span>Critical bypass quiet hours</span>
        </label>
        <p className="status-note">{enabled ? `Deliver ${severities.join(", ") || "no selected severities"} through ${channelTypes.join(", ") || "no selected channel types"}.` : "Personal delivery disabled."} {quietHoursStart && quietHoursEnd ? `Quiet ${quietHoursStart}–${quietHoursEnd} in ${timezone}.` : "No quiet hours."} {criticalBypassQuietHours ? "Critical alerts can bypass quiet hours." : "Quiet hours also apply to critical alerts."}</p>
        <button className="primary-button" type="submit" disabled={isBusy || !preference}>
          Save preferences
        </button>
      </form>
    </section>
  );
}

function formatEnum(value: string) {
  return value
    .toLowerCase()
    .split(/[_\s-]+/)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}
