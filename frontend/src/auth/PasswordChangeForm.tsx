import { useRef, useState, type FormEvent } from "react";
import { changePassword } from "./authApi";
import { PasswordField } from "./PasswordField";

export function PasswordChangeForm() {
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [busy, setBusy] = useState(false);
  const submitting = useRef(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (submitting.current) return;
    submitting.current = true;
    setBusy(true); setMessage(null); setError(null);
    try {
      const response = await changePassword(current, next);
      setCurrent(""); setNext(""); setMessage(response.message);
    } catch (error) { setError(error instanceof Error ? error.message : "Password change failed"); }
    finally { submitting.current = false; setBusy(false); }
  }
  return <form className="form-stack settings-password-form" onSubmit={submit} aria-busy={busy}>
    <PasswordField label="Current password" value={current} onChange={setCurrent} disabled={busy} />
    <PasswordField label="New password" value={next} onChange={setNext} newPassword disabled={busy} />
    {message ? <div className="success-message" role="status">{message}</div> : null}
    {error ? <div className="error-message" role="alert">{error}</div> : null}
    <button className="primary-button" type="submit" disabled={busy}>{busy ? "Changing password…" : "Change password"}</button>
  </form>;
}
