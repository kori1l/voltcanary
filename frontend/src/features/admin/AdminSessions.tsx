import { useEffect, useState } from "react";
import { listAdminUserSessions, revokeAdminUserSessions, type AdminRefreshSessionResponse, type AdminUserResponse } from "../../auth/authApi";
import { Dialog } from "../../components/Dialog";
import { useActionConfirmation } from "../../components/useActionConfirmation";
import { useSectionResource } from "../../components/useSectionResource";
import { SectionFeedback } from "../../components/SectionFeedback";
import { TimeDisplay } from "../../components/TimeDisplay";

export function AdminSessions({ user, onClose }: { user: AdminUserResponse; onClose: () => void }) {
  const sessions = useSectionResource<AdminRefreshSessionResponse[]>(user.id);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const confirmation = useActionConfirmation();
  const load = () => sessions.load(() => listAdminUserSessions(user.id), (data) => !data.length);
  useEffect(() => { void load(); }, [user.id]);
  async function revoke() {
    const accepted = await confirmation.confirm({ title: "Revoke refresh sessions", object: user.email, impact: "All active refresh sessions for this user will be revoked. Existing access tokens remain valid until expiry, subject to downstream lifecycle checks." });
    if (accepted === null) return;
    setBusy(true); setError(null);
    try { const response = await revokeAdminUserSessions(user.id); setMessage(`${response.revokedCount} refresh sessions revoked.`); await load(); }
    catch (error) { setError(error instanceof Error ? error.message : "Could not revoke sessions"); }
    finally { setBusy(false); }
  }
  return <>
    <Dialog title={`Sessions · ${user.email}`} onClose={onClose} busy={busy} wide footer={<button className="danger-button" type="button" disabled={busy || !sessions.data?.some((session) => session.active)} onClick={() => void revoke()}>Revoke all refresh sessions</button>}>
      <SectionFeedback resource={sessions} label="refresh sessions" onRetry={() => void load()} emptyMessage="No refresh sessions for this user." />
      {message ? <p className="success-message" role="status">{message}</p> : null}{error ? <p className="error-message" role="alert">{error}</p> : null}
      {sessions.data?.map((session) => <article className="panel" key={session.id}><strong>{session.active ? "Active refresh session" : session.revokedAt ? "Revoked refresh session" : "Expired refresh session"}</strong><dl className="detail-list"><div><dt>Created</dt><dd><TimeDisplay value={session.createdAt} /></dd></div><div><dt>Expires</dt><dd><TimeDisplay value={session.expiresAt} /></dd></div>{session.revokedAt ? <div><dt>Revoked</dt><dd><TimeDisplay value={session.revokedAt} /></dd></div> : null}</dl><details><summary>Session identifier</summary><code>{session.id}</code></details></article>)}
    </Dialog>{confirmation.dialog}
  </>;
}
