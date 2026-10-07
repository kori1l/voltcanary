import { useId, useState } from "react";
import { Dialog } from "./Dialog";
import { Field } from "./Field";

export function ConfirmDialog({ title, object, impact, dependencies, reasonRequired = false, busy, onCancel, onConfirm }: {
  title: string; object: string; impact: string; dependencies?: string;
  reasonRequired?: boolean; busy?: boolean; onCancel: () => void; onConfirm: (reason: string) => void;
}) {
  const [reason, setReason] = useState("");
  const formId = useId();
  return <Dialog title={title} onClose={onCancel} busy={busy} footer={<>
    <button type="button" className="ghost-button" onClick={onCancel} disabled={busy}>Cancel</button>
    <button type="submit" form={formId} className="danger-button" disabled={busy || (reasonRequired && !reason.trim())}>{busy ? "Working…" : title}</button>
  </>}>
    <p><strong>{object}</strong></p><p>{impact}</p>{dependencies ? <p>{dependencies}</p> : null}
    <form id={formId} onSubmit={(event) => { event.preventDefault(); if (!busy && (!reasonRequired || reason.trim())) onConfirm(reason.trim()); }}>
      {reasonRequired ? <Field label="Reason" required>{(props) => <textarea {...props} required value={reason} onChange={(event) => setReason(event.target.value)} />}</Field> : null}
    </form>
  </Dialog>;
}
