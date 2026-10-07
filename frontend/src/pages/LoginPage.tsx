import { FormEvent, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { setTokens } from "../api/client";
import { login } from "../auth/authApi";
import { AuthShell } from "../auth/AuthShell";
import { PasswordField } from "../auth/PasswordField";

export function LoginPage() {
  const navigate = useNavigate();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (isSubmitting) return;
    setError(null);
    setIsSubmitting(true);
    try {
      const tokens = await login({ email, password });
      setTokens(tokens.accessToken, tokens.refreshToken);
      navigate("/devices", { replace: true });
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Login failed");
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <AuthShell title="Sign in" description="Access your devices and operational console.">
        <form className="form-stack" onSubmit={onSubmit}>
          <label>
            Email
            <input value={email} onChange={(event) => setEmail(event.target.value)} type="email" autoComplete="email" required />
          </label>
          <PasswordField label="Password" value={password} onChange={setPassword} disabled={isSubmitting} />
          {error ? <div className="error-message" role="alert">{error}</div> : null}
          <button className="primary-button" type="submit" disabled={isSubmitting}>
            {isSubmitting ? "Signing in..." : "Sign in"}
          </button>
        </form>
        <Link className="auth-link" to="/register">
          Register
        </Link>
        <Link className="auth-link auth-link-secondary" to="/forgot-password">
          Forgot password
        </Link>
    </AuthShell>
  );
}
