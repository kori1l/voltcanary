import { FormEvent, useEffect, useState } from "react";
import {
  AdminRefreshSessionResponse,
  AdminUserResponse,
  changePassword,
  listAdminUserSessions,
  listAdminUsers,
  logoutAll,
  me,
  requestEmailVerification,
  revokeAdminUserSessions,
  updateAdminUserRole,
  updateAdminUserStatus,
} from "../auth/authApi";
import { clearTokens } from "../api/client";
import { DeviceAccount, getCurrentDeviceAccount } from "../devices/devicesApi";

export function AccountPage() {
  const [profile, setProfile] = useState<Awaited<ReturnType<typeof me>> | null>(null);
  const [deviceAccount, setDeviceAccount] = useState<DeviceAccount | null>(null);
  const [deviceAccountMessage, setDeviceAccountMessage] = useState<string | null>(null);
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [users, setUsers] = useState<AdminUserResponse[]>([]);
  const [sessions, setSessions] = useState<AdminRefreshSessionResponse[]>([]);
  const [selectedUserId, setSelectedUserId] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    loadProfile();
  }, []);

  async function loadProfile() {
    const loaded = await me();
    setProfile(loaded);
    try {
      setDeviceAccount(await getCurrentDeviceAccount());
      setDeviceAccountMessage(null);
    } catch (requestError) {
      const message = requestError instanceof Error ? requestError.message : "Device account unavailable";
      setDeviceAccount(null);
      setDeviceAccountMessage(message.includes("account_inactive") ? "Device account is inactive." : message);
    }
    if (loaded.role === "ADMIN") {
      setUsers(await listAdminUsers());
    }
  }

  async function onChangePassword(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await run(async () => {
      const response = await changePassword(currentPassword, newPassword);
      setCurrentPassword("");
      setNewPassword("");
      setMessage(response.message);
    });
  }

  async function onRequestVerification() {
    if (!profile) return;
    await run(async () => {
      const response = await requestEmailVerification(profile.email);
      setMessage(response.message);
    });
  }

  async function onLogoutAll() {
    await run(async () => {
      await logoutAll();
      clearTokens();
      window.location.assign("/login");
    });
  }

  async function onUpdateRole(userId: string, role: AdminUserResponse["role"]) {
    await run(async () => {
      await updateAdminUserRole(userId, role);
      setUsers(await listAdminUsers());
    });
  }

  async function onUpdateStatus(userId: string, status: AdminUserResponse["status"]) {
    await run(async () => {
      await updateAdminUserStatus(userId, status);
      setUsers(await listAdminUsers());
    });
  }

  async function onShowSessions(userId: string) {
    await run(async () => {
      setSelectedUserId(userId);
      setSessions(await listAdminUserSessions(userId));
    });
  }

  async function onRevokeSessions(userId: string) {
    await run(async () => {
      const response = await revokeAdminUserSessions(userId);
      setMessage(`Revoked sessions: ${response.revokedCount}`);
      setSessions(await listAdminUserSessions(userId));
    });
  }

  async function run(action: () => Promise<void>) {
    setMessage(null);
    setError(null);
    try {
      await action();
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Request failed");
    }
  }

  return (
    <div>
      <header className="page-header">
        <h1>Account</h1>
        {profile ? <p>{profile.email} · {profile.role} · {profile.status}</p> : null}
      </header>

      {message ? <div className="success-message account-message">{message}</div> : null}
      {error ? <div className="error-message account-message">{error}</div> : null}
      {deviceAccountMessage ? <div className="error-message account-message">{deviceAccountMessage}</div> : null}

      <div className="content-grid account-grid">
        <section className="panel">
          <h2>Security</h2>
          <p className="muted-text">Email: {profile?.emailVerified ? "verified" : "not verified"}</p>
          <p className="muted-text">Device account: {deviceAccount?.status ?? "unavailable"}</p>
          <div className="action-row action-row-start">
            <button className="ghost-button" type="button" onClick={onRequestVerification} disabled={!profile || profile.emailVerified}>
              Request verification
            </button>
            <button className="ghost-button" type="button" onClick={onLogoutAll}>
              Logout all sessions
            </button>
          </div>
          <form className="form-stack account-form" onSubmit={onChangePassword}>
            <label>
              Current password
              <input value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} type="password" required />
            </label>
            <label>
              New password
              <input value={newPassword} onChange={(event) => setNewPassword(event.target.value)} type="password" minLength={8} required />
            </label>
            <button className="primary-button" type="submit">Change password</button>
          </form>
        </section>

        {profile?.role === "ADMIN" ? (
          <section className="panel">
            <h2>Users</h2>
            <div className="admin-user-list">
              {users.map((user) => (
                <article className="admin-user-row" key={user.id}>
                  <div>
                    <strong>{user.email}</strong>
                    <span>{user.emailVerified ? "verified" : "unverified"}</span>
                  </div>
                  <select value={user.role} onChange={(event) => onUpdateRole(user.id, event.target.value as AdminUserResponse["role"])}>
                    <option value="USER">USER</option>
                    <option value="SUPPORT">SUPPORT</option>
                    <option value="ADMIN">ADMIN</option>
                  </select>
                  <select value={user.status} onChange={(event) => onUpdateStatus(user.id, event.target.value as AdminUserResponse["status"])}>
                    <option value="ACTIVE">ACTIVE</option>
                    <option value="DISABLED">DISABLED</option>
                    <option value="LOCKED">LOCKED</option>
                    <option value="PENDING_VERIFICATION">PENDING_VERIFICATION</option>
                  </select>
                  <button className="ghost-button" type="button" onClick={() => onShowSessions(user.id)}>Sessions</button>
                  <button className="ghost-button" type="button" onClick={() => onRevokeSessions(user.id)}>Revoke</button>
                </article>
              ))}
            </div>
            {selectedUserId ? (
              <div className="session-list">
                <h3>Sessions</h3>
                {sessions.map((session) => (
                  <div className="session-row" key={session.id}>
                    <span>{session.active ? "active" : "inactive"}</span>
                    <span>{new Date(session.createdAt).toLocaleString()}</span>
                  </div>
                ))}
              </div>
            ) : null}
          </section>
        ) : null}
      </div>
    </div>
  );
}
