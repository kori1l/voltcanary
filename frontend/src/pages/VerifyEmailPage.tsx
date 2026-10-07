import { FormEvent, useState } from "react";
import { Link } from "react-router-dom";
import { confirmEmailVerification, requestEmailVerification } from "../auth/authApi";
import { AuthShell } from "../auth/AuthShell";
import { useIncomingToken } from "../auth/useIncomingToken";
import { tokenErrorMessage } from "../auth/tokenErrorMessage";

export function VerifyEmailPage() {
  const { token, setToken, fromLink } = useIncomingToken();
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
      const response = await confirmEmailVerification(token);
      setMessage(response.message);
    } catch (requestError) {
      setError(tokenErrorMessage(requestError, "verify your email"));
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <AuthShell title="Verify email" description="Use a verification link or enter its token. Token requests follow the configured delivery channel; this screen cannot confirm delivery.">
        <form className="form-stack" onSubmit={onSubmit}>
          <details open={!fromLink}><summary>{fromLink ? "Verification token received from link · manual override" : "Enter verification token manually"}</summary><label>
            Verification token
            <input value={token} onChange={(event) => setToken(event.target.value)} autoComplete="one-time-code" required />
          </label></details>
          {message ? <div className="success-message" role="status">{message}</div> : null}
          {error ? <div className="error-message" role="alert">{error}</div> : null}
          <button className="primary-button" type="submit" disabled={isSubmitting}>
            {isSubmitting ? "Verifying..." : "Verify email"}
          </button>
        </form>
        <details className="auth-recovery"><summary>Request a new verification token</summary><form className="form-stack" onSubmit={async (event) => {
          event.preventDefault(); if (isSubmitting) return;
          setIsSubmitting(true); setError(null); setMessage(null);
          try { await requestEmailVerification(email); setMessage("If eligible, a new verification token has been requested. Follow the configured delivery instructions."); }
          catch { setError("Could not request verification. Please try again later."); }
          finally { setIsSubmitting(false); }
        }}><label>Email<input type="email" autoComplete="email" required value={email} onChange={(event) => setEmail(event.target.value)} /></label><button className="ghost-button" disabled={isSubmitting}>Request token</button></form></details>
        <Link className="auth-link" to="/login">
          Back to sign in
        </Link>
    </AuthShell>
  );
}
