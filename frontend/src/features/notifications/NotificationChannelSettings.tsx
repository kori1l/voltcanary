import { FormEvent, useEffect, useState } from "react";
import {
  createNotificationChannel,
  deleteNotificationChannel,
  disableNotificationChannel,
  enableNotificationChannel,
  listNotificationChannels,
  NotificationChannelResponse,
} from "../../alerts/alertsApi";
import {
  createNotificationChannelRequestFromDraft,
  formatEnum,
  NotificationChannelDraft,
  NotificationChannelType,
} from "./notificationChannelForm";

const channelTypes: NotificationChannelType[] = ["WEBHOOK", "EMAIL", "TELEGRAM"];
const parseModes = ["", "Markdown", "MarkdownV2", "HTML"] as const;

const initialDraft: NotificationChannelDraft = {
  name: "Ops webhook",
  channelType: "WEBHOOK",
  enabled: true,
  credentialsRef: "",
  webhookUrl: "",
  emailRecipients: "",
  emailCc: "",
  emailBcc: "",
  emailFromAlias: "VoltCanary",
  emailSubjectPrefix: "[VoltCanary]",
  telegramChatId: "",
  telegramParseMode: "",
  telegramDisableWebPagePreview: true,
};

export function NotificationChannelSettings() {
  const [channels, setChannels] = useState<NotificationChannelResponse[]>([]);
  const [draft, setDraft] = useState<NotificationChannelDraft>(initialDraft);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [isBusy, setIsBusy] = useState(false);

  useEffect(() => {
    void loadChannels();
  }, []);

  async function loadChannels() {
    setError(null);
    try {
      setChannels(await listNotificationChannels());
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to load notification channels");
    }
  }

  async function run(action: () => Promise<void>) {
    setIsBusy(true);
    setMessage(null);
    setError(null);
    try {
      await action();
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Request failed");
    } finally {
      setIsBusy(false);
    }
  }

  function updateDraft(update: Partial<NotificationChannelDraft>) {
    setDraft((current) => ({ ...current, ...update }));
  }

  async function addChannel(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await run(async () => {
      await createNotificationChannel(createNotificationChannelRequestFromDraft(draft));
      setDraft((current) => ({
        ...initialDraft,
        channelType: current.channelType,
        name: `${formatEnum(current.channelType)} channel`,
      }));
      setChannels(await listNotificationChannels());
      setMessage("Notification channel added");
    });
  }

  async function setChannelEnabled(channel: NotificationChannelResponse, enabled: boolean) {
    await run(async () => {
      const updated = enabled ? await enableNotificationChannel(channel.id) : await disableNotificationChannel(channel.id);
      setChannels((current) => current.map((item) => (item.id === updated.id ? updated : item)));
      setMessage(`Notification channel ${enabled ? "enabled" : "disabled"}`);
    });
  }

  async function removeChannel(channel: NotificationChannelResponse) {
    await run(async () => {
      await deleteNotificationChannel(channel.id);
      setChannels((current) => current.filter((item) => item.id !== channel.id));
      setMessage("Notification channel deleted");
    });
  }

  return (
    <section className="panel notification-channel-panel">
      <div className="section-header">
        <div>
          <h2>Notification Channels</h2>
          <p>{channels.length} configured</p>
        </div>
        <button className="ghost-button" type="button" onClick={() => void loadChannels()} disabled={isBusy}>
          Refresh
        </button>
      </div>
      {message ? <div className="success-message account-message">{message}</div> : null}
      {error ? <div className="error-message account-message">{error}</div> : null}
      <form className="notification-channel-form notification-channel-form-wide" onSubmit={addChannel}>
        <input value={draft.name} onChange={(event) => updateDraft({ name: event.target.value })} placeholder="Channel name" />
        <select
          aria-label="Channel type"
          value={draft.channelType}
          onChange={(event) => updateDraft({
            channelType: event.target.value as NotificationChannelType,
            name: `${formatEnum(event.target.value)} channel`,
          })}
        >
          {channelTypes.map((channelType) => (
            <option key={channelType} value={channelType}>
              {formatEnum(channelType)}
            </option>
          ))}
        </select>
        <input value={draft.credentialsRef} onChange={(event) => updateDraft({ credentialsRef: event.target.value })} placeholder="Credentials ref" />
        <label className="notification-attempt-row notification-enabled-toggle">
          <input type="checkbox" checked={draft.enabled} onChange={(event) => updateDraft({ enabled: event.target.checked })} />
          <span>Enabled</span>
        </label>
        {draft.channelType === "WEBHOOK" ? (
          <input
            className="notification-provider-field"
            value={draft.webhookUrl}
            onChange={(event) => updateDraft({ webhookUrl: event.target.value })}
            placeholder="Webhook URL"
          />
        ) : null}
        {draft.channelType === "EMAIL" ? (
          <>
            <input
              className="notification-provider-field"
              value={draft.emailRecipients}
              onChange={(event) => updateDraft({ emailRecipients: event.target.value })}
              placeholder="Recipients"
            />
            <input value={draft.emailCc} onChange={(event) => updateDraft({ emailCc: event.target.value })} placeholder="Cc" />
            <input value={draft.emailBcc} onChange={(event) => updateDraft({ emailBcc: event.target.value })} placeholder="Bcc" />
            <input value={draft.emailFromAlias} onChange={(event) => updateDraft({ emailFromAlias: event.target.value })} placeholder="From alias" />
            <input value={draft.emailSubjectPrefix} onChange={(event) => updateDraft({ emailSubjectPrefix: event.target.value })} placeholder="Subject prefix" />
          </>
        ) : null}
        {draft.channelType === "TELEGRAM" ? (
          <>
            <input
              className="notification-provider-field"
              value={draft.telegramChatId}
              onChange={(event) => updateDraft({ telegramChatId: event.target.value })}
              placeholder="Telegram chat id"
            />
            <select
              aria-label="Telegram parse mode"
              value={draft.telegramParseMode}
              onChange={(event) => updateDraft({ telegramParseMode: event.target.value as NotificationChannelDraft["telegramParseMode"] })}
            >
              {parseModes.map((parseMode) => (
                <option key={parseMode || "none"} value={parseMode}>
                  {parseMode || "Plain text"}
                </option>
              ))}
            </select>
            <label className="notification-attempt-row notification-enabled-toggle">
              <input
                type="checkbox"
                checked={draft.telegramDisableWebPagePreview}
                onChange={(event) => updateDraft({ telegramDisableWebPagePreview: event.target.checked })}
              />
              <span>Disable web preview</span>
            </label>
          </>
        ) : null}
        <button className="primary-button" type="submit" disabled={isBusy}>
          Add channel
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
                <button className="ghost-button" type="button" onClick={() => void setChannelEnabled(channel, !channel.enabled)} disabled={isBusy}>
                  {channel.enabled ? "Disable" : "Enable"}
                </button>
                <button className="ghost-button" type="button" onClick={() => void removeChannel(channel)} disabled={isBusy}>
                  Delete
                </button>
              </div>
            </article>
          ))}
        </div>
      ) : (
        <div className="empty-state">No notification channels configured.</div>
      )}
    </section>
  );
}
