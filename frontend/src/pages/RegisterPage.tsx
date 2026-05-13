import { FormEvent, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { login, register } from "../auth/authApi";
import { setTokens } from "../api/client";
import { BrandLogo } from "../components/BrandLogo";

export function RegisterPage() {
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
      await register({ email, password });
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
    <main className="auth-page">
      <section className="auth-panel">
        <BrandLogo size="large" />
        <p>Create an account</p>
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
              autoComplete="new-password"
              required
              minLength={8}
            />
          </label>
          {error ? <div className="error-message">{error}</div> : null}
          <button className="primary-button" type="submit" disabled={isSubmitting}>
            {isSubmitting ? "Creating account..." : "Register"}
          </button>
        </form>
        <Link className="auth-link" to="/login">
          Sign in
        </Link>
      </section>
    </main>
  );
}
