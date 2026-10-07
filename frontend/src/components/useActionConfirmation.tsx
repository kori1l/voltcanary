import { useCallback, useEffect, useRef, useState } from "react";
import { ConfirmDialog } from "./ConfirmDialog";

export interface ConfirmationRequest { title: string; object: string; impact: string; dependencies?: string; reasonRequired?: boolean }

export function useActionConfirmation() {
  const [pending, setPending] = useState<ConfirmationRequest | null>(null);
  const resolver = useRef<((reason: string | null) => void) | null>(null);
  useEffect(() => () => { resolver.current?.(null); resolver.current = null; }, []);
  const confirm = useCallback((request: ConfirmationRequest): Promise<string | null> => {
    if (resolver.current) return Promise.resolve(null);
    return new Promise((resolve) => { resolver.current = resolve; setPending(request); });
  }, []);
  function finish(reason: string | null) {
    const resolve = resolver.current;
    resolver.current = null;
    setPending(null);
    resolve?.(reason);
  }
  const dialog = pending ? <ConfirmDialog {...pending} onCancel={() => finish(null)} onConfirm={(reason) => finish(reason)} /> : null;
  return { confirm, dialog };
}
