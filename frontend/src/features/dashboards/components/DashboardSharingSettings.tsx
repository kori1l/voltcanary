import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { ApiError } from "../../../api/client";
import { listWorkspaces, type Workspace } from "../../workspaces/workspacesApi";
import * as api from "../model/dashboardSharingApi";
const capabilities = [
  "VIEW",
  "EDIT",
  "PUBLISH",
  "MANAGE_SHARING",
  "ARCHIVE",
  "COPY",
  "HISTORY",
  "EXPORT",
];
export function DashboardSharingSettings({ id }: { id: string }) {
  const [audience, setAudience] = useState<api.DashboardAudience | null>(null),
    [workspaces, setWorkspaces] = useState<Workspace[]>([]),
    [workspace, setWorkspace] = useState("");
  const [subject, setSubject] = useState(""),
    [subjectKind, setSubjectKind] = useState<"USER" | "TEAM">("USER"),
    [selected, setSelected] = useState<string[]>(["VIEW"]),
    [reviewed, setReviewed] = useState(false);
  const [preview, setPreview] = useState<Awaited<
      ReturnType<typeof api.previewDashboardAudience>
    > | null>(null),
    [error, setError] = useState<string | null>(null),
    [busy, setBusy] = useState(false),
    [reload, setReload] = useState(0);
  const lock = useRef(false);
  useEffect(() => {
    const c = new AbortController();
    setAudience(null);
    setPreview(null);
    void api
      .dashboardAudience(id, c.signal)
      .then((v) => {
        if (!c.signal.aborted) {
          setAudience(v);
          setWorkspace(v.workspaceId);
        }
      })
      .catch((e) => {
        if (!c.signal.aborted && !(e instanceof ApiError && e.status === 404))
          setError(e instanceof Error ? e.message : "Sharing unavailable");
      });
    void listWorkspaces(c.signal)
      .then((v) => {
        if (!c.signal.aborted) setWorkspaces(v);
      })
      .catch(() => {});
    return () => c.abort();
  }, [id, reload]);
  async function command(task: () => Promise<unknown>, refresh = true) {
    if (lock.current) return;
    lock.current = true;
    setBusy(true);
    setError(null);
    try {
      await task();
      if (refresh) {
        setReload((n) => n + 1);
        setReviewed(false);
      }
    } catch (e) {
      setPreview(null);
      setError(e instanceof Error ? e.message : "Sharing command failed");
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }
  return (
    <section className="dashboard-settings-section">
      <h3>Team sharing</h3>
      <Link to="/workspaces">Manage workspace resources</Link>
      <p>
        Sharing grants a dashboard capability. It never grants access to equipment or incident
        actions.
      </p>
      <fieldset disabled={busy}>
        <label>
          Workspace
          <select
            value={workspace}
            disabled={!!audience}
            onChange={(e) => setWorkspace(e.target.value)}
          >
            <option value="">Choose workspace</option>
            {workspaces.map((w) => (
              <option key={w.id} value={w.id}>
                {w.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          <input
            type="checkbox"
            checked={reviewed}
            onChange={(e) => setReviewed(e.target.checked)}
          />
          I reviewed the workspace, recipient and explicit capabilities
        </label>
        {!audience ? (
          <button
            type="button"
            disabled={!reviewed || !workspace}
            onClick={() => void command(() => api.associateDashboardWorkspace(id, workspace))}
          >
            Associate dashboard with workspace
          </button>
        ) : (
          <>
            <label>
              Recipient kind
              <select
                value={subjectKind}
                onChange={(e) => {
                  setSubjectKind(e.target.value as "USER" | "TEAM");
                  setPreview(null);
                  setReviewed(false);
                }}
              >
                <option>USER</option>
                <option>TEAM</option>
              </select>
            </label>
            <label>
              Active workspace recipient ID
              <input
                value={subject}
                onChange={(e) => {
                  setSubject(e.target.value);
                  setPreview(null);
                  setReviewed(false);
                }}
              />
            </label>
            {capabilities.map((capability) => (
              <label key={capability}>
                <input
                  type="checkbox"
                  disabled={capability === "VIEW"}
                  checked={selected.includes(capability)}
                  onChange={(e) => {
                    setSelected((prior) =>
                      e.target.checked
                        ? [...prior, capability]
                        : prior.filter((c) => c !== capability),
                    );
                    setReviewed(false);
                  }}
                />
                {capability.toLowerCase().replace(/_/g, " ")}
              </label>
            ))}
            {subjectKind === "USER" ? (
              <button
                type="button"
                disabled={!subject}
                onClick={() =>
                  void command(async () => {
                    setPreview(await api.previewDashboardAudience(id, [subject]));
                  }, false)
                }
              >
                Preview resource usability
              </button>
            ) : null}
            {preview ? (
              <ul>
                {preview.recipients.flatMap((r) =>
                  r.widgets.map((w) => (
                    <li key={r.userId + w.widgetId}>
                      {w.widgetId}:{" "}
                      {w.usable ? "Has some authorized evidence" : "Resource evidence unavailable"}.
                      No resource access granted.
                    </li>
                  )),
                )}
              </ul>
            ) : null}
            <button
              type="button"
              disabled={!reviewed || !subject}
              onClick={() =>
                void command(() =>
                  api.saveDashboardAudience(id, {
                    ...audience,
                    audience: [
                      ...audience.audience.filter(
                        (a) => a.subjectId !== subject || a.subjectKind !== subjectKind,
                      ),
                      { subjectId: subject, subjectKind, capabilities: selected },
                    ],
                  }),
                )
              }
            >
              Save reviewed dashboard grant
            </button>
            <ul>
              {audience.audience.map((a) => (
                <li key={a.subjectKind + a.subjectId}>
                  {a.subjectKind} {a.subjectId}: {a.capabilities.join(", ")}
                  <button
                    type="button"
                    disabled={!reviewed}
                    onClick={() =>
                      void command(() =>
                        api.saveDashboardAudience(id, {
                          ...audience,
                          audience: audience.audience.filter((entry) => entry !== a),
                        }),
                      )
                    }
                  >
                    Revoke dashboard grant
                  </button>
                </li>
              ))}
            </ul>
            <button
              type="button"
              disabled={!reviewed || subjectKind !== "USER" || !subject}
              onClick={() => void command(() => api.transferDashboardOwner(id, subject))}
            >
              Transfer dashboard ownership
            </button>
          </>
        )}
      </fieldset>
      {error ? (
        <p role="alert">
          {error}
          <button type="button" onClick={() => setReload((n) => n + 1)}>
            Reload sharing before retry
          </button>
        </p>
      ) : null}
    </section>
  );
}
