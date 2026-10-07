import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { listAdminUsers, me, updateAdminUserRole, updateAdminUserStatus, type AdminUserResponse, type MeResponse } from "../../auth/authApi";
import { useSectionResource } from "../../components/useSectionResource";
import { SectionFeedback } from "../../components/SectionFeedback";
import { PageHeader } from "../../components/PageHeader";
import { useActionConfirmation } from "../../components/useActionConfirmation";
import { AdminSessions } from "./AdminSessions";

const roles: AdminUserResponse["role"][] = ["USER", "SUPPORT", "ADMIN"];
const statuses: AdminUserResponse["status"][] = ["ACTIVE", "DISABLED", "LOCKED", "PENDING_VERIFICATION"];

export function AdminWorkspace() {
  const profile = useSectionResource<MeResponse>();
  useEffect(() => { void profile.load(me); }, []);
  return <>
    <PageHeader title="Admin" description="User access and refresh sessions. Personal security is available in Settings." actions={<Link className="ghost-button button-link" to="/settings">Personal settings</Link>} />
    <SectionFeedback resource={profile} label="administrator profile" onRetry={() => void profile.load(me)} />
    {profile.data?.role === "ADMIN" ? <AdminUsers currentUserId={profile.data.id} onCurrentUserChanged={() => profile.load(me)} /> : profile.data ? <section className="panel"><h2>Administrator access required</h2><p>Your role is {profile.data.role}. User administration requires ADMIN access.</p></section> : null}
  </>;
}

function AdminUsers({ currentUserId, onCurrentUserChanged }: { currentUserId: string; onCurrentUserChanged: () => Promise<unknown> }) {
  const users = useSectionResource<AdminUserResponse[]>();
  const [search, setSearch] = useState("");
  const [selected, setSelected] = useState<AdminUserResponse | null>(null);
  const confirmation = useActionConfirmation();
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const load = () => users.load(listAdminUsers, (data) => !data.length);
  useEffect(() => { void load(); }, []);
  async function apply(user: AdminUserResponse, kind: "role" | "status", value: string) {
    if (busy) return;
    const accepted = await confirmation.confirm({ title: `Apply ${kind} change`, object: user.email, impact: `${kind === "role" ? "Role" : "Status"} will change from ${user[kind]} to ${value}. ${kind === "status" && value !== "ACTIVE" ? "Login and refresh will be blocked. Existing access tokens are still subject to downstream lifecycle checks." : "Future authorized actions will use the resulting access."}` });
    if (accepted === null) return;
    setBusy(true); setMessage(null); setError(null);
    try {
      const updated = kind === "role" ? await updateAdminUserRole(user.id, value as AdminUserResponse["role"]) : await updateAdminUserStatus(user.id, value as AdminUserResponse["status"]);
      users.update((current) => current.map((item) => item.id === updated.id ? updated : item));
      setMessage(`${user.email}: ${kind} changed to ${updated[kind]}.`);
      if (updated.id === currentUserId) await onCurrentUserChanged();
    } catch (error) { setError(error instanceof Error ? error.message : "Access change failed"); }
    finally { setBusy(false); }
  }
  const visible = users.data?.filter((user) => `${user.email} ${user.role} ${user.status}`.toLowerCase().includes(search.toLowerCase())) ?? [];
  return <>
    {confirmation.dialog}
    <section className="panel"><div className="section-header"><h2>User access</h2><button type="button" className="ghost-button" onClick={() => void load()} disabled={busy || users.refreshing}>Refresh users</button></div>
      <label className="console-field">Search users<input type="search" value={search} onChange={(event) => setSearch(event.target.value)} /></label>
      <SectionFeedback resource={users} label="users" onRetry={() => void load()} emptyMessage="No user records were returned." />
      {message ? <div className="success-message" role="status">{message}</div> : null}
      {error ? <div className="error-message" role="alert">{error}</div> : null}
      <div className="admin-user-list">{visible.map((user) => <AdminUserRow key={user.id} user={user} busy={busy} onApply={apply} onSessions={() => setSelected(user)} />)}</div>
      {users.data?.length && !visible.length ? <p>No users match this search. <button type="button" className="ghost-button" onClick={() => setSearch("")}>Clear search</button></p> : null}
    </section>
    {selected ? <AdminSessions key={selected.id} user={selected} onClose={() => setSelected(null)} /> : null}
  </>;
}

function AdminUserRow({ user, busy, onApply, onSessions }: {
  user: AdminUserResponse; busy: boolean; onApply: (user: AdminUserResponse, kind: "role" | "status", value: string) => Promise<void>; onSessions: () => void;
}) {
  const [role, setRole] = useState(user.role);
  const [status, setStatus] = useState(user.status);
  useEffect(() => { setRole(user.role); }, [user.role]);
  useEffect(() => { setStatus(user.status); }, [user.status]);
  return <article className="admin-access-row">
    <div><strong>{user.email}</strong><p>{user.emailVerified ? "Email verified" : "Email not verified"} · Saved: {user.role} / {user.status}</p><details><summary>Identity details</summary><code>{user.id}</code></details></div>
    <div className="console-field"><label>Role for {user.email}<select value={role} onChange={(event) => setRole(event.target.value as AdminUserResponse["role"])} disabled={busy}>{roles.map((value) => <option key={value}>{value}</option>)}</select></label><button type="button" className="ghost-button" disabled={busy || role === user.role} onClick={() => void onApply(user, "role", role)}>Apply role</button></div>
    <div className="console-field"><label>Status for {user.email}<select value={status} onChange={(event) => setStatus(event.target.value as AdminUserResponse["status"])} disabled={busy}>{!statuses.includes(user.status) ? <option value={user.status}>{user.status}</option> : null}{statuses.map((value) => <option key={value}>{value}</option>)}</select></label><button type="button" className="ghost-button" disabled={busy || status === user.status || user.status === "DELETED"} onClick={() => void onApply(user, "status", status)}>Apply status</button></div>
    <button className="ghost-button" type="button" onClick={onSessions} disabled={busy}>View sessions</button>
  </article>;
}
