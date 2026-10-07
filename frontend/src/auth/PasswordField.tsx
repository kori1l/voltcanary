import { useState } from "react";
import { Field } from "../components/Field";

export function PasswordField({ label, value, onChange, newPassword = false, disabled = false }: {
  label: string; value: string; onChange: (value: string) => void; newPassword?: boolean; disabled?: boolean;
}) {
  const [visible, setVisible] = useState(false);
  return <Field label={label} required hint={newPassword ? "At least 8 characters." : undefined}>{(props) => <div className="password-field">
    <input {...props} type={visible ? "text" : "password"} value={value} onChange={(event) => onChange(event.target.value)} autoComplete={newPassword ? "new-password" : "current-password"} minLength={newPassword ? 8 : undefined} required disabled={disabled} />
    <button type="button" className="ghost-button" aria-label={`${visible ? "Hide" : "Show"} ${label.toLowerCase()}`} aria-pressed={visible} onClick={() => setVisible((current) => !current)} disabled={disabled}>{visible ? "Hide" : "Show"}</button>
  </div>}</Field>;
}
