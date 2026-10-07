import { FormEvent, useState } from "react";
import { Link } from "react-router-dom";
import { forgotPassword } from "../auth/authApi";
import { AuthShell } from "../auth/AuthShell";

export function ForgotPasswordPage() {
  const [email, setEmail] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (isSubmitting) return;
    setMessage(null);
    setError(null);
    setIsSubmitting(true);
    try {
      await forgotPassword(email);
      setMessage("If this email is eligible for recovery, a reset token has been requested. Follow the configured delivery instructions; this screen cannot verify email delivery.");
    } catch (requestError) {
      setError("Could not request recovery. Please try again later.");
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <AuthShell title="Reset password" description="Request a recovery token using your account email.">
        <form className="form-stack" onSubmit={onSubmit}>
          <label>
            Email
            <input value={email} onChange={(event) => setEmail(event.target.value)} type="email" autoComplete="email" required />
          </label>
          {message ? <div className="success-message" role="status">{message}</div> : null}
          {error ? <div className="error-message" role="alert">{error}</div> : null}
          <button className="primary-button" type="submit" disabled={isSubmitting}>
            {isSubmitting ? "Requesting..." : "Request reset token"}
          </button>
        </form>
        <Link className="auth-link" to="/reset-password">
          I have a token
        </Link>
        <Link className="auth-link auth-link-secondary" to="/login">
          Back to sign in
        </Link>
    </AuthShell>
  );
}
