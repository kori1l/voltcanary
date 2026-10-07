import { FormEvent, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { resetPassword } from "../auth/authApi";
import { AuthShell } from "../auth/AuthShell";
import { PasswordField } from "../auth/PasswordField";
import { useIncomingToken } from "../auth/useIncomingToken";
import { tokenErrorMessage } from "../auth/tokenErrorMessage";

export function ResetPasswordPage() {
  const navigate = useNavigate();
  const { token, setToken, fromLink } = useIncomingToken();
  const [newPassword, setNewPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (isSubmitting) return;
    setError(null);
    setIsSubmitting(true);
    try {
      await resetPassword(token, newPassword);
      navigate("/login", { replace: true });
    } catch (requestError) {
      setError(tokenErrorMessage(requestError, "reset your password"));
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <AuthShell title="Set new password" description="Review the password requirements, then apply your reset token. Invalid or expired tokens require a new recovery request.">
        <form className="form-stack" onSubmit={onSubmit}>
          <details open={!fromLink}><summary>{fromLink ? "Reset token received from link · manual override" : "Enter reset token manually"}</summary><label>
            Reset token
            <input value={token} onChange={(event) => setToken(event.target.value)} autoComplete="one-time-code" required />
          </label></details>
          <PasswordField label="New password" value={newPassword} onChange={setNewPassword} newPassword disabled={isSubmitting} />
          {error ? <div className="error-message" role="alert">{error}</div> : null}
          <button className="primary-button" type="submit" disabled={isSubmitting}>
            {isSubmitting ? "Saving..." : "Change password"}
          </button>
        </form>
        <Link className="auth-link" to="/forgot-password">Request a new token</Link>
        <Link className="auth-link" to="/login">
          Back to sign in
        </Link>
    </AuthShell>
  );
}
