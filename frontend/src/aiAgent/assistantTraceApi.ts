import { apiRequest, apiResponseRequest, sessionIdentityRevision } from "../api/client";

export interface AssistantTraceEvent {
  id: string;
  schemaVersion: number;
  sequence: number;
  conversationId: string;
  messageId: string;
  turnId: string;
  requestId: string;
  attemptId: string;
  reviewId?: string;
  actionId?: string;
  modelRunId?: string;
  stage: string;
  status: string;
  code?: string;
  durationMs?: number;
  details: Record<string, unknown>;
  truncated: boolean;
  createdAt: string;
}
const route = (conversationId: string, turnId: string) =>
  `/ai-agent/conversations/${encodeURIComponent(conversationId)}/turns/${encodeURIComponent(turnId)}/trace`;
export async function getAssistantTrace(conversationId: string, turnId: string) {
  const events: AssistantTraceEvent[] = [];
  let after = "0";
  do {
    const page = await apiRequest<{ items: AssistantTraceEvent[]; nextCursor: string | null }>(
      `${route(conversationId, turnId)}?after=${after}`,
    );
    events.push(...page.items);
    if (!page.nextCursor) break;
    if (Number(page.nextCursor) <= Number(after))
      throw new Error("Не удалось прочитать порядок этапов.");
    after = page.nextCursor;
  } while (true);
  return events;
}
export async function exportAssistantTrace(conversationId: string, turnId: string) {
  const actor = sessionIdentityRevision();
  const response = await apiResponseRequest(`${route(conversationId, turnId)}/export`);
  const blob = await response.blob();
  if (actor !== sessionIdentityRevision()) return;
  const url = URL.createObjectURL(blob),
    anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = `assistant-trace-${turnId}.json`;
  anchor.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 0);
}
