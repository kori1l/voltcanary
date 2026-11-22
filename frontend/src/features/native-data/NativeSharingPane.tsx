import { useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import {
  listMembers,
  listTeams,
  listWorkspaces,
  type Member,
  type Team,
  type Workspace,
} from "../workspaces/workspacesApi";
import {
  nativeDataApi,
  type Dataset,
  type SharingGrant,
  type SharingReview,
} from "./nativeDataApi";

export function NativeSharingPane({ dataset, onClose }: { dataset: Dataset; onClose: () => void }) {
  const [params, setParams] = useSearchParams();
  const savedId = params.get("sharingReview");
  const [grants, setGrants] = useState<SharingGrant[]>([]);
  const [review, setReview] = useState<SharingReview | null>(null);
  const [workspace, setWorkspace] = useState("");
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [members, setMembers] = useState<Member[] | null>(null);
  const [teams, setTeams] = useState<Team[] | null>(null);
  const [directoryLoading, setDirectoryLoading] = useState(false);
  const [kind, setKind] = useState<"USER" | "TEAM">("USER");
  const [subject, setSubject] = useState("");
  const [read, setRead] = useState<string[]>([]);
  const [write, setWrite] = useState<string[]>([]);
  const [actions, setActions] = useState<string[]>(["READ"]);
  const [allRows, setAllRows] = useState(false);
  const [rows, setRows] = useState("");
  const [pendingRevoke, setPendingRevoke] = useState<{ grant: SharingGrant; key: string } | null>(
    null,
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [refresh, setRefresh] = useState(0);
  const visible = review?.reviewId === savedId ? review : null;

  useEffect(() => {
    const controller = new AbortController();
    void listWorkspaces(controller.signal)
      .then((result) => {
        if (!controller.signal.aborted) setWorkspaces(result);
      })
      .catch(() => {});
    return () => controller.abort();
  }, []);

  useEffect(() => {
    setMembers(null);
    setTeams(null);
    if (!workspace) {
      setDirectoryLoading(false);
      return;
    }
    const controller = new AbortController();
    setDirectoryLoading(true);
    void Promise.allSettled([
      listMembers(workspace, controller.signal),
      listTeams(workspace, controller.signal),
    ]).then(([users, groups]) => {
      if (controller.signal.aborted) return;
      if (users.status === "fulfilled")
        setMembers(users.value.filter((member) => member.status === "ACTIVE"));
      if (groups.status === "fulfilled") setTeams(groups.value);
      setDirectoryLoading(false);
    });
    return () => controller.abort();
  }, [workspace]);

  const recipients =
    kind === "USER"
      ? members?.map((member) => ({ id: member.userId, label: member.userId }))
      : teams?.map((team) => ({ id: team.id, label: team.name }));

  useEffect(() => {
    const controller = new AbortController();
    void nativeDataApi
      .grants(dataset.id, controller.signal)
      .then((result) => {
        if (!controller.signal.aborted) setGrants(result);
      })
      .catch((failure: Error) => {
        if (!controller.signal.aborted) setError(failure.message);
      });
    return () => controller.abort();
  }, [dataset.id, refresh]);

  useEffect(() => {
    if (!savedId || busy) return;
    const controller = new AbortController();
    void nativeDataApi
      .inspectSharing(dataset.id, savedId, controller.signal)
      .then((result) => {
        if (!controller.signal.aborted) setReview(result);
      })
      .catch((failure: Error) => {
        if (!controller.signal.aborted) setError(failure.message);
      });
    return () => controller.abort();
  }, [dataset.id, savedId, busy]);

  function remember(id: string | null) {
    setParams(
      (current) => {
        const next = new URLSearchParams(current);
        if (id) next.set("sharingReview", id);
        else next.delete("sharingReview");
        return next;
      },
      { replace: true },
    );
  }

  async function run(action: "prepare" | "apply" | "revoke") {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      if (action === "prepare") {
        const result = await nativeDataApi.prepareSharing(
          dataset.id,
          {
            workspaceId: workspace.trim(),
            subject: { kind, id: subject.trim() },
            policy: {
              readFieldIds: read,
              writeFieldIds: write,
              actions,
              rows: {
                mode: allRows ? "ALL" : "IDS",
                recordIds: allRows ? [] : [...new Set(rows.split(/[,\s]+/).filter(Boolean))],
              },
            },
          },
          crypto.randomUUID(),
        );
        setReview(result);
        remember(result.reviewId);
      }
      if (action === "apply" && visible) {
        setReview(await nativeDataApi.applySharing(dataset.id, visible));
        setRefresh((value) => value + 1);
      }
      if (action === "revoke" && pendingRevoke) {
        await nativeDataApi.revokeSharing(dataset.id, pendingRevoke.grant, pendingRevoke.key);
        setPendingRevoke(null);
        setRefresh((value) => value + 1);
      }
    } catch (failure) {
      setError((failure as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel form-stack" aria-label="Workspace access">
      <h2>Workspace access</h2>
      {error ? <p role="alert">{error}</p> : null}
      {grants.length ? (
        <ul>
          {grants.map((grant) => (
            <li key={grant.grantId}>
              {grant.subjectKind} {grant.subjectId} · {grant.policy.actions.join(", ")} ·{" "}
              {grant.policy.readFieldIds.length} readable fields
              <button
                type="button"
                className="ghost-button"
                disabled={busy || Boolean(pendingRevoke)}
                onClick={() => setPendingRevoke({ grant, key: crypto.randomUUID() })}
              >
                Revoke
              </button>
            </li>
          ))}
        </ul>
      ) : null}
      {pendingRevoke ? (
        <div className="action-row">
          <span>Revoke access for {pendingRevoke.grant.subjectId}?</span>
          <button
            type="button"
            className="primary-button"
            disabled={busy}
            onClick={() => void run("revoke")}
          >
            Confirm revoke
          </button>
          <button
            type="button"
            className="ghost-button"
            disabled={busy}
            onClick={() => setPendingRevoke(null)}
          >
            Cancel
          </button>
        </div>
      ) : null}
      {!savedId ? (
        <form
          className="form-stack"
          onSubmit={(event) => {
            event.preventDefault();
            void run("prepare");
          }}
        >
          <label>
            Workspace
            <select
              required
              value={workspace}
              disabled={busy}
              onChange={(event) => {
                setWorkspace(event.target.value);
                setSubject("");
              }}
            >
              <option value="">Select workspace</option>
              {workspaces.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            Recipient type
            <select
              value={kind}
              disabled={busy}
              onChange={(event) => {
                setKind(event.target.value as typeof kind);
                setSubject("");
              }}
            >
              <option value="USER">User</option>
              <option value="TEAM">Team</option>
            </select>
          </label>
          {recipients ? (
            <label>
              {kind === "USER" ? "Member" : "Team"}
              <select
                required
                value={subject}
                disabled={busy || directoryLoading}
                onChange={(event) => setSubject(event.target.value)}
              >
                <option value="">Select recipient</option>
                {recipients.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.label}
                  </option>
                ))}
              </select>
            </label>
          ) : (
            <label>
              {kind === "USER" ? "User ID" : "Team ID"}
              <input
                required
                value={subject}
                disabled={busy || !workspace || directoryLoading}
                onChange={(event) => setSubject(event.target.value)}
              />
            </label>
          )}
          <fieldset disabled={busy}>
            <legend>Fields</legend>
            {dataset.definition.fields.map((field) => (
              <div className="action-row" key={field.id}>
                <label>
                  <input
                    type="checkbox"
                    checked={read.includes(field.id)}
                    onChange={(event) => {
                      setRead((values) =>
                        event.target.checked
                          ? [...values, field.id]
                          : values.filter((id) => id !== field.id),
                      );
                      if (!event.target.checked)
                        setWrite((values) => values.filter((id) => id !== field.id));
                    }}
                  />
                  Read {field.label}
                </label>
                <label>
                  <input
                    type="checkbox"
                    disabled={!read.includes(field.id)}
                    checked={write.includes(field.id)}
                    onChange={(event) =>
                      setWrite((values) =>
                        event.target.checked
                          ? [...values, field.id]
                          : values.filter((id) => id !== field.id),
                      )
                    }
                  />
                  Write {field.label}
                </label>
              </div>
            ))}
          </fieldset>
          <fieldset disabled={busy}>
            <legend>Rows and actions</legend>
            <label>
              <input
                type="checkbox"
                checked={allRows}
                onChange={(event) => setAllRows(event.target.checked)}
              />
              All rows
            </label>
            {!allRows ? (
              <label>
                Record IDs
                <textarea value={rows} onChange={(event) => setRows(event.target.value)} />
              </label>
            ) : null}
            {["HISTORY", "INSERT", "UPDATE", "DELETE"].map((action) => (
              <label key={action}>
                <input
                  type="checkbox"
                  checked={actions.includes(action)}
                  onChange={(event) =>
                    setActions((values) =>
                      event.target.checked
                        ? [...values, action]
                        : values.filter((value) => value !== action),
                    )
                  }
                />
                {action}
              </label>
            ))}
          </fieldset>
          <button
            type="submit"
            className="primary-button"
            disabled={busy || directoryLoading || !workspace || !subject || !read.length}
          >
            Review access
          </button>
        </form>
      ) : visible ? (
        <>
          <p role="status">{visible.status}</p>
          <p>
            {visible.definition.subject.kind} {visible.definition.subject.id} in workspace{" "}
            {visible.definition.workspaceId}
          </p>
          <p>
            {visible.definition.policy.actions.join(", ")} ·{" "}
            {visible.definition.policy.rows.mode === "ALL"
              ? "All rows"
              : visible.definition.policy.rows.recordIds.length + " selected rows"}
          </p>
          <details>
            <summary>Field and row IDs</summary>
            <pre>{JSON.stringify(visible.definition.policy, null, 2)}</pre>
          </details>
          {visible.status === "PREPARED" ? (
            <button
              type="button"
              className="primary-button"
              disabled={busy || Date.parse(visible.expiresAt) <= Date.now()}
              onClick={() => void run("apply")}
            >
              Grant access
            </button>
          ) : null}
        </>
      ) : (
        <p role="status">Loading saved access review…</p>
      )}
      <button
        type="button"
        className="ghost-button"
        disabled={busy}
        onClick={() => {
          remember(null);
          onClose();
        }}
      >
        Back to records
      </button>
    </section>
  );
}
