import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { PageHeader } from "../../components/PageHeader";
import * as api from "./workspacesApi";
const actions = [
  "device.read",
  "telemetry.read",
  "analytics.read",
  "incident.read",
  "incident.ack",
  "incident.resolve",
  "maintenance.read",
  "maintenance.write",
  "ml.read",
  "ml.write",
  "tariff.read",
  "tariff.write",
  "operational.read",
  "operational.write",
];
export function WorkspaceManagement() {
  const [workspaces, setWorkspaces] = useState<api.Workspace[]>([]),
    [selected, setSelected] = useState("");
  const [members, setMembers] = useState<api.Member[]>([]),
    [teams, setTeams] = useState<api.Team[]>([]),
    [grants, setGrants] = useState<api.ResourceGrant[]>([]),
    [audit, setAudit] = useState<Record<string, unknown>[]>([]);
  const [name, setName] = useState(""),
    [user, setUser] = useState(""),
    [role, setRole] = useState("MEMBER"),
    [teamName, setTeamName] = useState(""),
    [teamUsers, setTeamUsers] = useState<string[]>([]);
  const [account, setAccount] = useState(""),
    [subject, setSubject] = useState(""),
    [subjectKind, setSubjectKind] = useState("USER"),
    [scopeKind, setScopeKind] = useState("DEVICE"),
    [resource, setResource] = useState(""),
    [action, setAction] = useState("device.read");
  const [reviewed, setReviewed] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState<string | null>(null),
    [message, setMessage] = useState(""),
    [reload, setReload] = useState(0);
  const lock = useRef(false);
  useEffect(() => {
    const c = new AbortController();
    void api
      .listWorkspaces(c.signal)
      .then((items) => {
        if (!c.signal.aborted) setWorkspaces(items);
      })
      .catch((e) => {
        if (!c.signal.aborted)
          setError(e instanceof Error ? e.message : "Workspace list unavailable");
      });
    return () => c.abort();
  }, [reload]);
  useEffect(() => {
    const c = new AbortController();
    setMembers([]);
    setTeams([]);
    setGrants([]);
    setAudit([]);
    setReviewed(false);
    if (selected)
      void Promise.all([
        api.listMembers(selected, c.signal),
        api.listTeams(selected, c.signal),
        api.workspaceAudit(selected, c.signal),
      ])
        .then(([m, t, a]) => {
          if (!c.signal.aborted) {
            setMembers(m);
            setTeams(t);
            setAudit(a);
          }
        })
        .catch((e) => {
          if (!c.signal.aborted)
            setError(e instanceof Error ? e.message : "Membership unavailable");
        });
    return () => c.abort();
  }, [selected, reload]);
  useEffect(() => {
    const c = new AbortController();
    setGrants([]);
    if (selected && account)
      void api
        .listResourceGrants(selected, account, c.signal)
        .then((items) => {
          if (!c.signal.aborted) setGrants(items);
        })
        .catch((e) => {
          if (!c.signal.aborted)
            setError(e instanceof Error ? e.message : "Resource grants unavailable");
        });
    return () => c.abort();
  }, [selected, account, reload]);
  async function command(task: () => Promise<unknown>) {
    if (lock.current || !reviewed) return;
    lock.current = true;
    setBusy(true);
    setError(null);
    setMessage("");
    try {
      await task();
      setMessage("Reviewed change saved");
      setReload((n) => n + 1);
      setReviewed(false);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Workspace command failed");
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }
  return (
    <>
      <PageHeader title="Workspaces" />
      <Link to="/dashboards">Dashboards</Link>
      <p>
        Membership preserves personal ownership. Resource capabilities are independent from
        dashboard grants.
      </p>
      <fieldset disabled={busy}>
        <label>
          Workspace
          <select value={selected} onChange={(e) => setSelected(e.target.value)}>
            <option value="">Select workspace</option>
            {workspaces.map((w) => (
              <option key={w.id} value={w.id}>
                {w.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          New workspace name
          <input maxLength={80} value={name} onChange={(e) => setName(e.target.value)} />
        </label>
        <label>
          <input
            type="checkbox"
            checked={reviewed}
            onChange={(e) => setReviewed(e.target.checked)}
          />
          I reviewed the selected change, identities and capabilities
        </label>
        <button
          type="button"
          disabled={!reviewed || !name.trim()}
          onClick={() => void command(() => api.createWorkspace(name))}
        >
          Create workspace
        </button>
        {selected ? (
          <>
            <section className="panel">
              <h2>Members</h2>
              <ul>
                {members.map((m) => (
                  <li key={m.userId}>
                    {m.userId} · {m.role} · {m.status}
                    {m.role !== "OWNER" && m.status === "ACTIVE" ? (
                      <button
                        type="button"
                        disabled={!reviewed}
                        onClick={() => void command(() => api.removeMember(selected, m.userId))}
                      >
                        Remove member
                      </button>
                    ) : null}
                  </li>
                ))}
              </ul>
              <label>
                Existing user ID
                <input value={user} onChange={(e) => setUser(e.target.value)} />
              </label>
              <label>
                Role
                <select value={role} onChange={(e) => setRole(e.target.value)}>
                  <option>MEMBER</option>
                  <option>MANAGER</option>
                </select>
              </label>
              <button
                type="button"
                disabled={!reviewed || !user}
                onClick={() => void command(() => api.saveMember(selected, user, role))}
              >
                Save reviewed membership
              </button>
              <button
                type="button"
                disabled={
                  !reviewed || !members.some((m) => m.userId === user && m.status === "ACTIVE")
                }
                onClick={() => void command(() => api.transferWorkspace(selected, user))}
              >
                Transfer ownership and remove former owner
              </button>
            </section>
            <section className="panel">
              <h2>Teams</h2>
              {teams.map((t) => (
                <p key={t.id}>
                  {t.name} · {t.userIds.length} active assignments
                </p>
              ))}
              <label>
                Team name
                <input
                  maxLength={80}
                  value={teamName}
                  onChange={(e) => setTeamName(e.target.value)}
                />
              </label>
              {members
                .filter((m) => m.status === "ACTIVE")
                .map((m) => (
                  <label key={m.userId}>
                    <input
                      type="checkbox"
                      checked={teamUsers.includes(m.userId)}
                      onChange={(e) =>
                        setTeamUsers((prior) =>
                          e.target.checked
                            ? [...prior, m.userId]
                            : prior.filter((id) => id !== m.userId),
                        )
                      }
                    />
                    {m.userId}
                  </label>
                ))}
              <button
                type="button"
                disabled={!reviewed || !teamName.trim()}
                onClick={() => void command(() => api.createTeam(selected, teamName, teamUsers))}
              >
                Create reviewed team
              </button>
            </section>
            <section className="panel">
              <h2>Resources</h2>
              <label>
                Personal account ID
                <input value={account} onChange={(e) => setAccount(e.target.value)} />
              </label>
              <p>Association requires consent from the signed-in personal owner.</p>
              <button
                type="button"
                disabled={!reviewed || !account}
                onClick={() => void command(() => api.associateAccount(selected, account, true))}
              >
                Associate my account
              </button>
              <button
                type="button"
                disabled={!reviewed || !account}
                onClick={() => void command(() => api.associateAccount(selected, account, false))}
              >
                Detach my account
              </button>
              <label>
                Recipient kind
                <select value={subjectKind} onChange={(e) => setSubjectKind(e.target.value)}>
                  <option>USER</option>
                  <option>TEAM</option>
                </select>
              </label>
              <label>
                Recipient
                <select value={subject} onChange={(e) => setSubject(e.target.value)}>
                  <option value="">Choose recipient</option>
                  {(subjectKind === "USER"
                    ? members
                        .filter((m) => m.status === "ACTIVE")
                        .map((m) => ({ id: m.userId, name: m.userId }))
                    : teams
                  ).map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.name}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                Capability
                <select value={action} onChange={(e) => setAction(e.target.value)}>
                  {actions.map((a) => (
                    <option key={a}>{a}</option>
                  ))}
                </select>
              </label>
              <label>
                Scope
                <select value={scopeKind} onChange={(e) => setScopeKind(e.target.value)}>
                  <option>DEVICE</option>
                  <option>LOCATION</option>
                </select>
              </label>
              <label>
                Owned resource ID
                <input value={resource} onChange={(e) => setResource(e.target.value)} />
              </label>
              <button
                type="button"
                disabled={!reviewed || !account || !subject || !resource}
                onClick={() =>
                  void command(() =>
                    api.saveResourceGrant(selected, {
                      accountId: account,
                      subjectId: subject,
                      subjectKind,
                      action,
                      scopeKind,
                      resourceId: resource,
                    }),
                  )
                }
              >
                Save explicit resource grant
              </button>
              <ul>
                {grants.map((g) => (
                  <li key={g.id}>
                    {g.subjectKind} {g.subjectId} · {g.action} · {g.scopeKind} {g.resourceId}
                    <button
                      type="button"
                      disabled={!reviewed}
                      onClick={() =>
                        void command(() => api.revokeResourceGrant(selected, account, g.id))
                      }
                    >
                      Revoke
                    </button>
                  </li>
                ))}
              </ul>
            </section>
            <section className="panel">
              <h2>Membership audit</h2>
              {audit.map((entry, i) => (
                <p key={i}>
                  {String(entry.action)} · {String(entry.actor_id)} · {String(entry.occurred_at)}
                </p>
              ))}
            </section>
          </>
        ) : null}
      </fieldset>
      {error ? (
        <p role="alert">
          {error}
          <button type="button" onClick={() => setReload((n) => n + 1)}>
            Reload current access
          </button>
        </p>
      ) : null}
      {message ? <p role="status">{message}</p> : null}
    </>
  );
}
