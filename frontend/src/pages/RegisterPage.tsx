import { FormEvent, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { login, register } from "../auth/authApi";
import { setTokens } from "../api/client";
import { AuthShell } from "../auth/AuthShell";
import { PasswordField } from "../auth/PasswordField";

export function RegisterPage() {
  const navigate = useNavigate();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [registered, setRegistered] = useState(false);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (isSubmitting) return;
    setError(null);
    setIsSubmitting(true);
    try {
      if (!registered) {
        await register({ email, password });
        setRegistered(true);
      }
      const tokens = await login({ email, password });
      setTokens(tokens.accessToken, tokens.refreshToken);
      navigate("/devices", { replace: true });
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Registration failed");
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <AuthShell title="Create an account" description="Register to connect and monitor your devices.">
        <form className="form-stack" onSubmit={onSubmit}>
          <label>
            Email
            <input value={email} onChange={(event) => setEmail(event.target.value)} type="email" autoComplete="email" required disabled={isSubmitting || registered} />
          </label>
          <PasswordField label="Password" value={password} onChange={setPassword} newPassword disabled={isSubmitting} />
          {registered && error ? <p role="status">Account created. Retry sign in or use the sign-in link below.</p> : null}
          {error ? <div className="error-message" role="alert">{error}</div> : null}
          <button className="primary-button" type="submit" disabled={isSubmitting}>
            {isSubmitting ? "Working..." : registered ? "Retry sign in" : "Register"}
          </button>
        </form>
        <Link className="auth-link" to="/login">
          Sign in
        </Link>
        <Link className="auth-link auth-link-secondary" to="/verify-email">
          Verify email
        </Link>
    </AuthShell>
  );
}
