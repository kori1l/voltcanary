import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { me, MeResponse, requestEmailVerification } from "../auth/authApi";
import { NotificationChannelSettings } from "../features/notifications/NotificationChannelSettings";
import { NotificationPreferenceSettings } from "../features/notifications/NotificationPreferenceSettings";
import { PasswordChangeForm } from "../auth/PasswordChangeForm";
import { useSectionResource } from "../components/useSectionResource";
import { SectionFeedback } from "../components/SectionFeedback";
import { PersonalSessionControls } from "../auth/PersonalSessionControls";

export function SettingsPage() {
  const profileSection = useSectionResource<MeResponse>();
  const profile = profileSection.data;
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [isBusy, setIsBusy] = useState(false);

  useEffect(() => {
    void load();
  }, []);

  async function load() {
    await profileSection.load(me);
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
      <SectionFeedback resource={profileSection} label="profile" onRetry={() => void load()} />

      <section className="settings-grid">
        <section className="panel">
          <div className="section-header">
            <div>
              <h2>Profile</h2>
              <p>{profile ? `${profile.email} / ${profile.role}` : profileSection.status === "loading" ? "Loading profile" : "Profile unavailable"}</p>
            </div>
          </div>
          <dl className="detail-list">
            <div>
              <dt>Email</dt>
              <dd>{profile?.email ?? "Unavailable"}</dd>
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
              <dd>{profile ? profile.emailVerified ? "Verified" : "Not verified" : "Unavailable"}</dd>
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
          <PasswordChangeForm />
          <PersonalSessionControls />
        </section>

        <section className="panel">
          <div className="section-header">
            <div>
              <h2>Machine Learning</h2>
              <p>Datasets, imports, labels, training runs, model registry, deployment policies, and monitoring.</p>
            </div>
          </div>
          <div className="detail-list">
            <div>
              <dt>Workspace</dt>
              <dd>Production ML module</dd>
            </div>
            <div>
              <dt>Sections</dt>
              <dd>Settings, Datasets, Import, Labels, Training, Models, Policies, Monitoring</dd>
            </div>
          </div>
          <div className="action-row action-row-start">
            <Link className="primary-button button-link" to="/settings/ml">
              Open ML settings
            </Link>
          </div>
        </section>
      </section>

      <NotificationChannelSettings />
      <NotificationPreferenceSettings />
    </>
  );
}
