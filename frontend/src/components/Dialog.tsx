import {X} from "lucide-react";
import { createContext, useContext, useEffect, useId, useRef, useState, type ReactNode } from "react";
import { useWorkflowDraft } from "./workflowDraftContext";

const CloseContext = createContext<() => void>(() => {});

export function DialogClose({ children = "Cancel" }: { children?: ReactNode }) {
  const close = useContext(CloseContext);
  return <button type="button" className="ghost-button" onClick={close}>{children}</button>;
}

// Native modal dialogs provide top-layer isolation, focus containment and Escape.
export function Dialog({ id, title, children, footer, onClose, dirty = false, busy = false, wide = false, guardChanges = false, side = false }: {
  title: string; children: ReactNode; footer?: ReactNode; onClose: () => void;
  id?:string; dirty?: boolean; busy?: boolean; wide?: boolean; guardChanges?: boolean; side?:boolean|"left";
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const [discarding, setDiscarding] = useState(false);
  const [edited, setEdited] = useState(false);
  useWorkflowDraft(dirty || edited, onClose, false);
  const returnFocus = useRef<HTMLElement | null>(null);
  const keepEditing = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (discarding) keepEditing.current?.focus();
    else if (returnFocus.current?.isConnected) returnFocus.current.focus();
  }, [discarding]);
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const panel = ref.current;
    panel?.showModal();
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      panel?.close();
      document.body.style.overflow = previousOverflow;
      if (previous?.isConnected) previous.focus();
    };
  }, []);

  function requestClose() {
    if (busy) return;
    if (dirty || edited) {
      returnFocus.current = document.activeElement as HTMLElement | null;
      setDiscarding(true);
    }
    else onClose();
  }

  return <CloseContext.Provider value={requestClose}><dialog id={id} ref={ref} className={`console-dialog${wide ? " console-dialog-wide" : ""}${side ? " console-dialog-side" : ""}${side==="left"?" console-dialog-side-left":""}`} aria-labelledby={titleId} onCancel={(event) => { event.preventDefault(); requestClose(); }} onClick={(event)=>{if(side!=="left" || event.target!==event.currentTarget)return;const bounds=event.currentTarget.getBoundingClientRect();if(event.clientX<bounds.left || event.clientX>bounds.right || event.clientY<bounds.top || event.clientY>bounds.bottom)requestClose();}}>
    <header className="console-dialog-header"><h2 id={titleId}>{title}</h2><button type="button" className="ghost-button console-dialog-close" aria-label="Close" title="Close" onClick={requestClose} disabled={busy}><X size={20} aria-hidden="true"/></button></header>
    <div className="console-dialog-body" onChangeCapture={() => { if (guardChanges) setEdited(true); }}>{children}</div>
    {discarding ? <footer className="console-dialog-footer" role="alert">
      <p>You have unsaved changes. Discard them?</p>
      <button ref={keepEditing} type="button" className="ghost-button" onClick={() => setDiscarding(false)}>Keep editing</button>
      <button type="button" className="danger-button" onClick={onClose}>Discard changes</button>
    </footer> : footer ? <footer className="console-dialog-footer">{footer}</footer> : null}
  </dialog></CloseContext.Provider>;
}
