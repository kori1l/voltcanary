import { FormEvent, useEffect, useState } from "react";
import { clearTokens } from "../api/client";
import { AlertResponse, listAlerts } from "../alerts/alertsApi";
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
import { Device, DeviceAccount, getCurrentDeviceAccount, listDevices, listLocations, Location } from "../devices/devicesApi";

const ROLE_OPTIONS: AdminUserResponse["role"][] = ["USER", "SUPPORT", "ADMIN"];
const STATUS_OPTIONS: AdminUserResponse["status"][] = ["ACTIVE", "DISABLED", "LOCKED", "PENDING_VERIFICATION"];

export function AccountPage() {
  const [profile, setProfile] = useState<Awaited<ReturnType<typeof me>> | null>(null);
  const [deviceAccount, setDeviceAccount] = useState<DeviceAccount | null>(null);
  const [deviceAccountMessage, setDeviceAccountMessage] = useState<string | null>(null);
  const [devices, setDevices] = useState<Device[]>([]);
  const [locations, setLocations] = useState<Location[]>([]);
  const [alerts, setAlerts] = useState<AlertResponse[]>([]);
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [users, setUsers] = useState<AdminUserResponse[]>([]);
  const [sessions, setSessions] = useState<AdminRefreshSessionResponse[]>([]);
  const [selectedUserId, setSelectedUserId] = useState<string | null>(null);
  const [userSearch, setUserSearch] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    loadProfile();
  }, []);

  async function loadProfile() {
    const loaded = await me();
    setProfile(loaded);
    const [deviceResponse, locationResponse, alertResponse] = await Promise.all([
      listDevices({ limit: 200, offset: 0 }).catch(() => []),
      listLocations({ limit: 200, offset: 0 }).catch(() => []),
      listAlerts().catch(() => []),
    ]);
    setDevices(deviceResponse);
    setLocations(locationResponse);
    setAlerts(alertResponse);
    try {
      setDeviceAccount(await getCurrentDeviceAccount());
      setDeviceAccountMessage(null);
    } catch (requestError) {
      const requestMessage = requestError instanceof Error ? requestError.message : "Device account unavailable";
      setDeviceAccount(null);
      setDeviceAccountMessage(requestMessage.includes("account_inactive") ? "Device account is inactive." : requestMessage);
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
      const refreshedUsers = await listAdminUsers();
      setUsers(refreshedUsers);
      refreshProfileIfNeeded(refreshedUsers);
    });
  }

  async function onUpdateStatus(userId: string, status: AdminUserResponse["status"]) {
    await run(async () => {
      await updateAdminUserStatus(userId, status);
      const refreshedUsers = await listAdminUsers();
      setUsers(refreshedUsers);
      refreshProfileIfNeeded(refreshedUsers);
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

  function refreshProfileIfNeeded(refreshedUsers: AdminUserResponse[]) {
    if (!profile) return;
    const currentUser = refreshedUsers.find((user) => user.id === profile.id);
    if (currentUser) {
      setProfile({
        id: currentUser.id,
        email: currentUser.email,
        role: currentUser.role,
        status: currentUser.status,
        emailVerified: currentUser.emailVerified,
      });
    }
  }

  const selectedUser = users.find((user) => user.id === selectedUserId) ?? null;
  const filteredUsers = users.filter((user) => {
    const value = userSearch.trim().toLowerCase();
    if (!value) return true;
    return (
      user.email.toLowerCase().includes(value) ||
      user.role.toLowerCase().includes(value) ||
      user.status.toLowerCase().includes(value) ||
      user.id.toLowerCase().includes(value)
    );
  });
  const activeSessions = sessions.filter((session) => session.active).length;
  const inactiveSessions = selectedUserId ? sessions.length - activeSessions : 0;
  const activeUsers = users.filter((user) => user.status === "ACTIVE").length;
  const adminUsers = users.filter((user) => user.role === "ADMIN").length;
  const unverifiedUsers = users.filter((user) => !user.emailVerified).length;
  const activeDevices = devices.filter((device) => device.status === "ACTIVE").length;
  const openAlerts = alerts.filter((alert) => alert.status !== "RESOLVED").length;

  return (
    <div>
      <header className="page-header-row">
        <div>
          <h1>Account</h1>
          {profile ? <p>{profile.email} / {profile.role} / {profile.status}</p> : null}
        </div>
        <div className="header-actions">
          <button className="ghost-button" type="button" onClick={loadProfile}>
            Refresh
          </button>
        </div>
      </header>

      {message ? <div className="success-message account-message">{message}</div> : null}
      {error ? <div className="error-message account-message">{error}</div> : null}
      {deviceAccountMessage ? <div className="error-message account-message">{deviceAccountMessage}</div> : null}

      <div className="account-layout">
        <section className="panel">
          <div className="section-header">
            <div>
              <h2>Profile</h2>
              <p>Identity and device account data attached to this user.</p>
            </div>
          </div>
          <dl className="detail-list">
            <div>
              <dt>User ID</dt>
              <dd>{profile?.id ?? "loading"}</dd>
            </div>
            <div>
              <dt>Email</dt>
              <dd>{profile?.email ?? "loading"}</dd>
            </div>
            <div>
              <dt>Role</dt>
              <dd><span className={`status-pill status-pill-${profile?.role === "ADMIN" ? "high" : "neutral"}`}>{profile?.role ?? "unknown"}</span></dd>
            </div>
            <div>
              <dt>Status</dt>
              <dd><span className={`status-pill ${profile?.status === "ACTIVE" ? "status-pill-low" : "status-pill-critical"}`}>{profile?.status ?? "unknown"}</span></dd>
            </div>
            <div>
              <dt>Email verification</dt>
              <dd>{profile?.emailVerified ? "Verified" : "Not verified"}</dd>
            </div>
            <div>
              <dt>Device account</dt>
              <dd>{deviceAccount ? `${deviceAccount.name} / ${deviceAccount.status}` : "Unavailable"}</dd>
            </div>
            <div>
              <dt>Device account ID</dt>
              <dd>{deviceAccount?.id ?? "unavailable"}</dd>
            </div>
          </dl>
        </section>

        <section className="panel">
          <div className="section-header">
            <div>
              <h2>Workspace</h2>
              <p>Operational totals visible to the current account.</p>
            </div>
          </div>
          <div className="account-stat-grid">
            <article>
              <span>Total devices</span>
              <strong>{devices.length}</strong>
              <small>{activeDevices} active</small>
            </article>
            <article>
              <span>Locations</span>
              <strong>{locations.length}</strong>
              <small>available places</small>
            </article>
            <article>
              <span>Alerts</span>
              <strong>{alerts.length}</strong>
              <small>{openAlerts} open</small>
            </article>
            <article>
              <span>Account status</span>
              <strong>{deviceAccount?.status ?? "n/a"}</strong>
              <small>{deviceAccountMessage ?? "device service connected"}</small>
            </article>
          </div>
        </section>

        <section className="panel">
          <div className="section-header">
            <div>
              <h2>Security</h2>
              <p>Password and session-wide access controls.</p>
            </div>
          </div>
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
          <section className="panel account-admin-panel">
            <div className="section-header">
              <div>
                <h2>Admin</h2>
                <p>User access, roles and refresh sessions.</p>
              </div>
              <div className="account-admin-search">
                <input
                  aria-label="Search users"
                  placeholder="Search users"
                  value={userSearch}
                  onChange={(event) => setUserSearch(event.target.value)}
                />
              </div>
            </div>
            <div className="account-stat-grid account-admin-stats">
              <article>
                <span>Users</span>
                <strong>{users.length}</strong>
                <small>{activeUsers} active</small>
              </article>
              <article>
                <span>Admins</span>
                <strong>{adminUsers}</strong>
                <small>privileged accounts</small>
              </article>
              <article>
                <span>Unverified</span>
                <strong>{unverifiedUsers}</strong>
                <small>email pending</small>
              </article>
              <article>
                <span>Selected sessions</span>
                <strong>{selectedUserId ? sessions.length : 0}</strong>
                <small>{selectedUserId ? `${activeSessions} active / ${inactiveSessions} inactive` : "select a user"}</small>
              </article>
            </div>
            <div className="admin-user-list">
              {filteredUsers.map((user) => (
                <article className="admin-user-row" key={user.id}>
                  <div>
                    <strong>{user.email}</strong>
                    <span>{user.id}</span>
                    <span>Created {new Date(user.createdAt).toLocaleString()}</span>
                    <span>Updated {new Date(user.updatedAt).toLocaleString()}</span>
                    <span>{user.emailVerified ? "email verified" : "email not verified"}</span>
                  </div>
                  <select value={user.role} onChange={(event) => onUpdateRole(user.id, event.target.value as AdminUserResponse["role"])}>
                    {ROLE_OPTIONS.map((role) => <option value={role} key={role}>{role}</option>)}
                  </select>
                  <select value={user.status} onChange={(event) => onUpdateStatus(user.id, event.target.value as AdminUserResponse["status"])}>
                    {STATUS_OPTIONS.map((status) => <option value={status} key={status}>{status}</option>)}
                  </select>
                  <button className="ghost-button" type="button" onClick={() => onShowSessions(user.id)}>Sessions</button>
                  <button className="danger-button" type="button" onClick={() => onRevokeSessions(user.id)}>Revoke</button>
                </article>
              ))}
              {filteredUsers.length === 0 ? <div className="empty-state">No users match this filter.</div> : null}
            </div>
            {selectedUserId ? (
              <div className="session-list">
                <div className="section-header">
                  <div>
                    <h3>Sessions</h3>
                    <p>{selectedUser?.email ?? selectedUserId}</p>
                  </div>
                  <button className="danger-button" type="button" onClick={() => onRevokeSessions(selectedUserId)}>
                    Revoke selected user
                  </button>
                </div>
                {sessions.map((session) => (
                  <div className="session-row" key={session.id}>
                    <span className={session.active ? "session-active" : "session-inactive"}>{session.active ? "active" : "inactive"}</span>
                    <span>{session.id}</span>
                    <span>Created {new Date(session.createdAt).toLocaleString()}</span>
                    <span>Expires {new Date(session.expiresAt).toLocaleString()}</span>
                    <span>{session.revokedAt ? `Revoked ${new Date(session.revokedAt).toLocaleString()}` : "Not revoked"}</span>
                  </div>
                ))}
                {sessions.length === 0 ? <div className="empty-state">No refresh sessions for this user.</div> : null}
              </div>
            ) : null}
          </section>
        ) : null}
      </div>
    </div>
  );
}
