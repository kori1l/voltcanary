import { useId, type ReactNode } from "react";

export function Field({ label, required, hint, error, children }: {
  label: string; required?: boolean; hint?: string; error?: string;
  children: (props: { id: string; "aria-describedby"?: string; "aria-invalid"?: true; "aria-required"?: true }) => ReactNode;
}) {
  const id = useId();
  const hintId = hint ? `${id}-hint` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  const descriptionId = [hintId, errorId].filter(Boolean).join(" ") || undefined;
  return <div className="console-field">
    <label htmlFor={id}>{label}{required ? <span className="required-marker" aria-hidden="true">{"\u00a0*"}</span> : null}</label>
    {children({ id, "aria-describedby": descriptionId, "aria-invalid": error ? true : undefined, "aria-required": required ? true : undefined })}
    {hintId ? <small id={hintId} className="field-hint">{hint}</small> : null}
    {errorId ? <small id={errorId} className="field-error" role="alert">{error}</small> : null}
  </div>;
}
