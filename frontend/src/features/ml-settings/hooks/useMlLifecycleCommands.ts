import type { MlLifecycleImpact } from "../../../analytics/mlModuleApi";
import type { ConfirmationRequest } from "../../../components/useActionConfirmation";
import { useEntityCommands } from "../../../components/useEntityCommands";
import { sanitizeApiMessage } from "../../../api/client";
import { formatImpactDependencies, requestLifecycleReason } from "../model/mlLifecycleActions";
export function useMlLifecycleCommands(confirm: (request: ConfirmationRequest) => Promise<string | null>, refresh: () => Promise<boolean>, setError: (value: string | null) => void, setMessage: (value: string | null) => void) {
  const commands = useEntityCommands();
  async function execute(key: string, id: string, action: string, label: string, preview: () => Promise<MlLifecycleImpact>, mutate: (reason: string) => Promise<unknown>, savedMessage: string, committed?: () => void, refreshRecords: () => Promise<unknown> = refresh) {
    let reason = "";
    await commands.run(key, { label: `${action} ${label}`,
      review: async () => { const impact = await preview(); if (impact.blocked) { setError(sanitizeApiMessage(`${impact.message}${formatImpactDependencies(impact)}`)); return false; } const answer = await requestLifecycleReason(confirm, action, label, impact); reason = answer ?? ""; return Boolean(answer); },
      mutate: async () => { await mutate(reason); return { id }; },
      onCommitted: () => { setMessage(savedMessage); committed?.(); }, refresh: refreshRecords,
    });
  }
  return { ...commands, execute };
}
