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
import { NotificationChannelOperations } from "./NotificationChannelOperations";
import { useActionConfirmation } from "../../components/useActionConfirmation";

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
  const [loaded, setLoaded] = useState(false);
  const confirmation = useActionConfirmation();

  useEffect(() => {
    void loadChannels();
  }, []);

  async function loadChannels() {
    setError(null);
    try {
      setChannels(await listNotificationChannels());
      setLoaded(true);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to load notification channels");
    }
  }

  async function run(action: () => Promise<void>) {
    if (isBusy) return;
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
    if (await confirmation.confirm({ title: "Delete notification channel", object: channel.name, impact: "Removes this delivery destination. Rules or preferences referencing it will no longer deliver through it." }) === null) return;
    await run(async () => {
      await deleteNotificationChannel(channel.id);
      setChannels((current) => current.filter((item) => item.id !== channel.id));
      setMessage("Notification channel deleted");
    });
  }

  return (
    <section className="panel notification-channel-panel">
      {confirmation.dialog}
      <div className="section-header">
        <div>
          <h2>Notification Channels</h2>
          <p>{loaded ? `${channels.length} configured` : "Channel inventory unavailable"}</p>
        </div>
        <button className="ghost-button" type="button" onClick={() => void loadChannels()} disabled={isBusy}>
          Refresh
        </button>
      </div>
      {message ? <div className="success-message account-message">{message}</div> : null}
      {error ? <div className="error-message account-message">{error}</div> : null}
      <form className="notification-channel-form notification-channel-form-wide" onSubmit={addChannel}>
        <label>Channel name<input value={draft.name} onChange={(event) => updateDraft({ name: event.target.value })} placeholder="Channel name" /></label>
        <label>Channel type<select
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
        </select></label>
        <label>Credentials ref<input value={draft.credentialsRef} onChange={(event) => updateDraft({ credentialsRef: event.target.value })} placeholder="Credentials ref" /></label>
        <p className="field-hint">Reference the server's configured credential. Enter a reference, never a provider password or bot token. Creating or enabling a channel does not establish adapter availability; check health and send a test afterwards.</p>
        <label className="notification-attempt-row notification-enabled-toggle">
          <input type="checkbox" checked={draft.enabled} onChange={(event) => updateDraft({ enabled: event.target.checked })} />
          <span>Enabled</span>
        </label>
        {draft.channelType === "WEBHOOK" ? (
          <label>Webhook URL<input
            className="notification-provider-field"
            value={draft.webhookUrl}
            onChange={(event) => updateDraft({ webhookUrl: event.target.value })}
            placeholder="Webhook URL"
          /></label>
        ) : null}
        {draft.channelType === "EMAIL" ? (
          <>
            <label>Recipients<input
              className="notification-provider-field"
              value={draft.emailRecipients}
              onChange={(event) => updateDraft({ emailRecipients: event.target.value })}
              placeholder="Recipients"
            /></label>
            <details className="notification-advanced"><summary>Advanced email options</summary><div className="form-grid-two"><label>Cc<input value={draft.emailCc} onChange={(event) => updateDraft({ emailCc: event.target.value })} placeholder="Cc" /></label>
            <label>Bcc<input value={draft.emailBcc} onChange={(event) => updateDraft({ emailBcc: event.target.value })} placeholder="Bcc" /></label>
            <label>From alias<input value={draft.emailFromAlias} onChange={(event) => updateDraft({ emailFromAlias: event.target.value })} placeholder="From alias" /></label>
            <label>Subject prefix<input value={draft.emailSubjectPrefix} onChange={(event) => updateDraft({ emailSubjectPrefix: event.target.value })} placeholder="Subject prefix" /></label></div></details>
          </>
        ) : null}
        {draft.channelType === "TELEGRAM" ? (
          <>
            <label>Telegram chat id<input
              className="notification-provider-field"
              value={draft.telegramChatId}
              onChange={(event) => updateDraft({ telegramChatId: event.target.value })}
              placeholder="Telegram chat id"
            /></label>
            <details className="notification-advanced"><summary>Advanced Telegram options</summary><label>Telegram parse mode<select
              aria-label="Telegram parse mode"
              value={draft.telegramParseMode}
              onChange={(event) => updateDraft({ telegramParseMode: event.target.value as NotificationChannelDraft["telegramParseMode"] })}
            >
              {parseModes.map((parseMode) => (
                <option key={parseMode || "none"} value={parseMode}>
                  {parseMode || "Plain text"}
                </option>
              ))}
            </select></label>
            <label className="notification-attempt-row notification-enabled-toggle">
              <input
                type="checkbox"
                checked={draft.telegramDisableWebPagePreview}
                onChange={(event) => updateDraft({ telegramDisableWebPagePreview: event.target.checked })}
              />
              <span>Disable web preview</span>
            </label></details>
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
              <div className="notification-channel-summary">
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
              </div>
              <NotificationChannelOperations channel={channel} disabled={isBusy} />
            </article>
          ))}
        </div>
      ) : loaded ? (
        <div className="empty-state">No notification channels configured.</div>
      ) : null}
    </section>
  );
}
