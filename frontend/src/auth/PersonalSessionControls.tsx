import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { clearTokens } from "../api/client";
import { logoutAll } from "./authApi";
import { useActionConfirmation } from "../components/useActionConfirmation";

export function PersonalSessionControls() {
  const navigate = useNavigate();
  const confirmation = useActionConfirmation();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  async function signOut() {
    const accepted = await confirmation.confirm({ title: "Sign out all refresh sessions", object: "Your account", impact: "All of your refresh sessions will be revoked and this browser will sign out. Existing access tokens expire according to their lifetime." });
    if (accepted === null) return;
    setBusy(true); setError(null);
    try { await logoutAll(); clearTokens(); navigate("/login", { replace: true }); }
    catch (error) { setError(error instanceof Error ? error.message : "Could not sign out sessions"); }
    finally { setBusy(false); }
  }
  return <div className="personal-sessions">{confirmation.dialog}<h3>Sessions</h3><p>Revoke all refresh sessions belonging to your account.</p>{error ? <p className="error-message" role="alert">{error}</p> : null}<button type="button" className="ghost-button" onClick={() => void signOut()} disabled={busy}>{busy ? "Signing out…" : "Sign out all sessions"}</button></div>;
}
