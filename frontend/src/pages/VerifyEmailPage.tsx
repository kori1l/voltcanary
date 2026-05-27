import { FormEvent, useState } from "react";
import { Link } from "react-router-dom";
import { confirmEmailVerification } from "../auth/authApi";
import { BrandLogo } from "../components/BrandLogo";

export function VerifyEmailPage() {
  const [token, setToken] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setMessage(null);
    setError(null);
    setIsSubmitting(true);
    try {
      const response = await confirmEmailVerification(token);
      setMessage(response.message);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Email verification failed");
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <main className="auth-page">
      <section className="auth-panel">
        <BrandLogo size="large" />
        <p>Verify email</p>
        <form className="form-stack" onSubmit={onSubmit}>
          <label>
            Verification token
            <input value={token} onChange={(event) => setToken(event.target.value)} autoComplete="one-time-code" required />
          </label>
          {message ? <div className="success-message">{message}</div> : null}
          {error ? <div className="error-message">{error}</div> : null}
          <button className="primary-button" type="submit" disabled={isSubmitting}>
            {isSubmitting ? "Verifying..." : "Verify email"}
          </button>
        </form>
        <Link className="auth-link" to="/login">
          Back to sign in
        </Link>
      </section>
    </main>
  );
}
