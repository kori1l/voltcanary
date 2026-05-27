import { FormEvent, useState } from "react";
import { Link } from "react-router-dom";
import { forgotPassword } from "../auth/authApi";
import { BrandLogo } from "../components/BrandLogo";

export function ForgotPasswordPage() {
  const [email, setEmail] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setMessage(null);
    setError(null);
    setIsSubmitting(true);
    try {
      const response = await forgotPassword(email);
      setMessage(response.message);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Password reset request failed");
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <main className="auth-page">
      <section className="auth-panel">
        <BrandLogo size="large" />
        <p>Reset password</p>
        <form className="form-stack" onSubmit={onSubmit}>
          <label>
            Email
            <input value={email} onChange={(event) => setEmail(event.target.value)} type="email" autoComplete="email" required />
          </label>
          {message ? <div className="success-message">{message}</div> : null}
          {error ? <div className="error-message">{error}</div> : null}
          <button className="primary-button" type="submit" disabled={isSubmitting}>
            {isSubmitting ? "Sending..." : "Send reset token"}
          </button>
        </form>
        <Link className="auth-link" to="/reset-password">
          I have a token
        </Link>
        <Link className="auth-link auth-link-secondary" to="/login">
          Back to sign in
        </Link>
      </section>
    </main>
  );
}
