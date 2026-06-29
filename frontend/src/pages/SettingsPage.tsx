import { FormEvent, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import {
  createNotificationChannel,
  deleteNotificationChannel,
  disableNotificationChannel,
  enableNotificationChannel,
  listNotificationChannels,
  NotificationChannelResponse,
} from "../alerts/alertsApi";
import { changePassword, me, MeResponse, requestEmailVerification } from "../auth/authApi";

export function SettingsPage() {
  const [profile, setProfile] = useState<MeResponse | null>(null);
  const [channels, setChannels] = useState<NotificationChannelResponse[]>([]);
  const [channelName, setChannelName] = useState("Ops webhook");
  const [webhookUrl, setWebhookUrl] = useState("");
  const [credentialsRef, setCredentialsRef] = useState("");
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [isBusy, setIsBusy] = useState(false);

  useEffect(() => {
    void load();
  }, []);

  async function load() {
    setError(null);
    try {
      const [profileResponse, channelsResponse] = await Promise.all([
        me().catch(() => null),
        listNotificationChannels().catch(() => []),
      ]);
      setProfile(profileResponse);
      setChannels(channelsResponse);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to load settings");
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

  async function addWebhookChannel(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!webhookUrl.trim()) {
      setError("Webhook URL is required");
      return;
    }
    await run(async () => {
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

  async function submitPassword(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await run(async () => {
      const response = await changePassword(currentPassword, newPassword);
      setCurrentPassword("");
      setNewPassword("");
      setMessage(response.message);
    });
  }

  async function verifyEmail() {
    if (!profile) {
      return;
    }
    await run(async () => {
      const response = await requestEmailVerification(profile.email);
      setMessage(response.message);
    });
  }

  return (
    <>
      <header className="page-header page-header-row">
        <div>
          <h1>Settings</h1>
          <p>Account, security, and notification routing.</p>
        </div>
        <button className="ghost-button" type="button" onClick={() => void load()} disabled={isBusy}>
          Refresh
        </button>
      </header>

      {message ? <div className="success-message account-message">{message}</div> : null}
      {error ? <div className="error-message account-message">{error}</div> : null}

      <section className="settings-grid">
        <section className="panel">
          <div className="section-header">
            <div>
              <h2>Profile</h2>
              <p>{profile ? `${profile.email} / ${profile.role}` : "Loading profile"}</p>
            </div>
          </div>
          <dl className="detail-list">
            <div>
              <dt>Email</dt>
              <dd>{profile?.email ?? "loading"}</dd>
            </div>
            <div>
              <dt>Role</dt>
              <dd>{profile?.role ?? "unknown"}</dd>
            </div>
            <div>
              <dt>Status</dt>
              <dd>{profile?.status ?? "unknown"}</dd>
            </div>
            <div>
              <dt>Email verification</dt>
              <dd>{profile?.emailVerified ? "Verified" : "Not verified"}</dd>
            </div>
          </dl>
          <div className="action-row action-row-start">
            <button className="ghost-button" type="button" onClick={() => void verifyEmail()} disabled={isBusy || !profile || profile.emailVerified}>
              Verify email
            </button>
            {profile?.role === "ADMIN" ? (
              <Link className="ghost-button button-link" to="/ai-agent/settings">
                AI model settings
              </Link>
            ) : null}
          </div>
        </section>

        <section className="panel">
          <div className="section-header">
            <div>
              <h2>Security</h2>
              <p>Change your password.</p>
            </div>
          </div>
          <form className="notification-channel-form settings-password-form" onSubmit={submitPassword}>
            <input value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} type="password" placeholder="Current password" required />
            <input value={newPassword} onChange={(event) => setNewPassword(event.target.value)} type="password" placeholder="New password" minLength={8} required />
            <button className="primary-button" type="submit" disabled={isBusy}>
              Change password
            </button>
          </form>
        </section>
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
          <button className="primary-button" type="submit" disabled={isBusy}>
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
    </>
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
