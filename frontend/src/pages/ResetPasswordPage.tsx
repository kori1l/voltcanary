import { FormEvent, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { resetPassword } from "../auth/authApi";
import { BrandLogo } from "../components/BrandLogo";

export function ResetPasswordPage() {
  const navigate = useNavigate();
  const [token, setToken] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setIsSubmitting(true);
    try {
      await resetPassword(token, newPassword);
      navigate("/login", { replace: true });
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Password reset failed");
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <main className="auth-page">
      <section className="auth-panel">
        <BrandLogo size="large" />
        <p>Set new password</p>
        <form className="form-stack" onSubmit={onSubmit}>
          <label>
            Reset token
            <input value={token} onChange={(event) => setToken(event.target.value)} autoComplete="one-time-code" required />
          </label>
          <label>
            New password
            <input
              value={newPassword}
              onChange={(event) => setNewPassword(event.target.value)}
              type="password"
              autoComplete="new-password"
              minLength={8}
              required
            />
          </label>
          {error ? <div className="error-message">{error}</div> : null}
          <button className="primary-button" type="submit" disabled={isSubmitting}>
            {isSubmitting ? "Saving..." : "Change password"}
          </button>
        </form>
        <Link className="auth-link" to="/login">
          Back to sign in
        </Link>
      </section>
    </main>
  );
}
