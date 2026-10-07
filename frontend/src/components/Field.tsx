import { useId, type ReactNode } from "react";

export function Field({ label, required, hint, error, children }: {
  label: string; required?: boolean; hint?: string; error?: string;
  children: (props: { id: string; "aria-describedby"?: string; "aria-invalid"?: true }) => ReactNode;
}) {
  const id = useId();
  const descriptionId = hint || error ? `${id}-description` : undefined;
  return <div className="console-field">
    <label htmlFor={id}>{label}{required ? <span aria-hidden="true"> *</span> : null}</label>
    {children({ id, "aria-describedby": descriptionId, "aria-invalid": error ? true : undefined })}
    {descriptionId ? <small id={descriptionId} className={error ? "field-error" : "field-hint"}>{error || hint}</small> : null}
  </div>;
}
