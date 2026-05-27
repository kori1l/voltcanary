import { FormEvent, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { setTokens } from "../api/client";
import { login } from "../auth/authApi";
import { BrandLogo } from "../components/BrandLogo";

export function LoginPage() {
  const navigate = useNavigate();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
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
    <main className="auth-page">
      <section className="auth-panel">
        <BrandLogo size="large" />
        <p>Sign in</p>
        <form className="form-stack" onSubmit={onSubmit}>
          <label>
            Email
            <input value={email} onChange={(event) => setEmail(event.target.value)} type="email" autoComplete="email" required />
          </label>
          <label>
            Password
            <input
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              type="password"
              autoComplete="current-password"
              required
            />
          </label>
          {error ? <div className="error-message">{error}</div> : null}
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
      </section>
    </main>
  );
}
