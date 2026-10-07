import { useState } from "react";
import { ModelProvider, rotateModelProviderSecret } from "./aiAgentApi";
import { useActionConfirmation } from "../components/useActionConfirmation";
export function ProviderSecretRotation({ provider, onChanged }: { provider: ModelProvider; onChanged: () => Promise<void> }) {
  const [key,setKey] = useState(""); const [busy,setBusy] = useState(false); const [error,setError] = useState<string | null>(null); const [saved,setSaved] = useState(false); const confirmation = useActionConfirmation();
  if (provider.authType === "NONE") return <p className="status-note">This provider does not require an API credential.</p>;
  return <section className="form-stack">{confirmation.dialog}<h3>Provider credential</h3><p>{provider.maskedSecretHint ? "Credential configured" : "Credential missing"}. Replacement is held only in this form until submission.</p><label>Replacement API key<input type="password" autoComplete="off" value={key} disabled={busy} onChange={(event) => { setKey(event.target.value); setSaved(false); }} /></label><button className="ghost-button" type="button" disabled={busy || !key.trim()} onClick={async () => {
    if (await confirmation.confirm({ title: "Rotate provider credential", object: provider.name, impact: "Replaces the stored provider credential for subsequent requests. Existing model configuration is retained. Test the connection after rotation." }) === null) return;
    setBusy(true); setError(null);
    try { await rotateModelProviderSecret(provider.id,key); setKey(""); setSaved(true); await onChanged(); }
    catch (error) { setError(error instanceof Error ? error.message : "Rotation failed"); } finally { setBusy(false); }
  }}>{busy ? "Rotating..." : "Review credential rotation"}</button>{saved ? <p role="status">Credential rotated. Run a provider connection test.</p> : null}{error ? <p className="error-message" role="alert">{error}</p> : null}</section>;
}
