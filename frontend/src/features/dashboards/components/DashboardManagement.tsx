import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Dialog } from "../../../components/Dialog";
import { DashboardDeletion } from "./DashboardDeletion";
import {
  commandKey,
  copyDashboard,
  getDashboardPreferences,
  saveDashboardPreferences,
  type DashboardPreferences,
} from "../../../dashboards/dashboardsApi";
import { exportDashboardTemplate } from "../model/dashboardSharingApi";
import { sessionIdentityRevision } from "../../../api/client";
export function DashboardManagement({
  id,
  name,
  published,
  disabled = false,
  permissions,
  onDeleted,
}: {
  id: string;
  name: string;
  published: boolean;
  disabled?: boolean;
  permissions?: string[];
  onDeleted?: () => void;
}) {
  const navigate = useNavigate(),
    [preferences, setPreferences] = useState<DashboardPreferences | null>(null),
    [copyOpen, setCopyOpen] = useState(false),
    [copyName, setCopyName] = useState(`${name.slice(0, 75)} copy`),
    [busy, setBusy] = useState(false),
    [error, setError] = useState<string | null>(null),
    [message, setMessage] = useState("");
  const lock = useRef(false),
    key = useRef<{ name: string; key: string } | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    void getDashboardPreferences(controller.signal)
      .then(setPreferences)
      .catch(() => {});
    return () => controller.abort();
  }, [id]);
  async function preference(favorite: boolean) {
    if (lock.current) return;
    lock.current = true;
    setBusy(true);
    setError(null);
    const identity = sessionIdentityRevision();
    try {
      const current = await getDashboardPreferences();
      const next = await saveDashboardPreferences(
        favorite
          ? {
              ...current,
              favorites: current.favorites.includes(id)
                ? current.favorites.filter((value) => value !== id)
                : [...current.favorites, id],
            }
          : { ...current, defaultDashboardId: id },
      );
      if (identity === sessionIdentityRevision()) {
        setPreferences(next);
        setMessage(favorite ? "Favorite updated" : "Personal default saved");
      }
    } catch (e) {
      if (identity === sessionIdentityRevision())
        setError(e instanceof Error ? e.message : "Preference failed");
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }
  async function copy() {
    if (lock.current || !copyName.trim()) return;
    lock.current = true;
    setBusy(true);
    setError(null);
    const identity = sessionIdentityRevision();
    const name = copyName.trim();
    if (key.current?.name !== name) key.current = { name, key: commandKey() };
    try {
      const copied = await copyDashboard(id, name, key.current.key);
      if (identity === sessionIdentityRevision()) navigate(`/dashboards/${copied.id}/edit`);
    } catch (e) {
      if (identity === sessionIdentityRevision())
        setError(e instanceof Error ? e.message : "Copy failed");
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }
  async function exportTemplate() {
    if (lock.current) return;
    lock.current = true;
    setBusy(true);
    try {
      const template = await exportDashboardTemplate(id);
      const url = URL.createObjectURL(
        new Blob([JSON.stringify(template, null, 2)], { type: "application/json" }),
      );
      const link = document.createElement("a");
      link.href = url;
      link.download = "dashboard-template.json";
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Export failed");
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }
  return (
    <div className="dashboard-management">
      {permissions?.includes("delete") ? (
        <DashboardDeletion id={id} name={name} disabled={disabled || busy} onDeleted={onDeleted} />
      ) : null}
      {published && (!permissions || permissions.includes("export")) ? (
        <button
          type="button"
          className="ghost-button"
          disabled={disabled || busy}
          onClick={() => void exportTemplate()}
        >
          Export template
        </button>
      ) : null}
      {published ? (
        <section className="dashboard-settings-section">
          <h3>Personal preferences</h3>
          <div className="action-row">
            <button
              className="ghost-button"
              disabled={disabled || busy}
              onClick={() => void preference(false)}
            >
              Set as personal default
            </button>
            <button
              className="ghost-button"
              disabled={disabled || busy || !preferences}
              onClick={() => void preference(true)}
            >
              {preferences?.favorites.includes(id) ? "Remove from favorites" : "Add to favorites"}
            </button>
          </div>
        </section>
      ) : null}
      {!permissions || permissions.includes("copy") ? (
        <section className="dashboard-settings-section">
          <h3>Copy</h3>
          <button
            className="ghost-button"
            disabled={disabled || busy}
            onClick={() => setCopyOpen(true)}
          >
            Copy dashboard
          </button>
        </section>
      ) : null}
      {message ? <p role="status">{message}</p> : null}
      {error && !copyOpen ? <p role="alert">{error}</p> : null}
      {copyOpen ? (
        <Dialog title="Copy dashboard" busy={busy} onClose={() => setCopyOpen(false)}>
          <p>
            {published
              ? "Copies the latest published revision. Unpublished edits are excluded."
              : "Copies the saved private draft."}
          </p>
          {error ? <p role="alert">{error}</p> : null}
          <form
            onSubmit={(e) => {
              e.preventDefault();
              void copy();
            }}
          >
            <label>
              Copy name
              <input
                required
                maxLength={80}
                value={copyName}
                onChange={(e) => setCopyName(e.target.value)}
              />
            </label>
            <button className="primary-button" disabled={busy || !copyName.trim()}>
              Create copy
            </button>
          </form>
        </Dialog>
      ) : null}
    </div>
  );
}
