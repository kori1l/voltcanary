import { vi } from "vitest";
import type { AgentPlan, ExecutePlanResponse } from "../aiAgent/aiAgentApi";
import type { SavedConversation, SavedMessage, SubmittedMessage } from "../aiAgent/conversationApi";

/** In-memory transport fixture for UI workflows; production storage is covered by JDBC/API tests. */
export function mockAssistantFetch() {
  const domain = vi.fn<typeof fetch>();
  const conversations = new Map<
    string,
    {
      saved: SavedConversation;
      messages: SavedMessage[];
      plan: AgentPlan | null;
      pendingStatus: string | null;
      requests: Map<string, unknown>;
    }
  >();
  let selected: string | null = null;
  const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status });
  const detail = (id: string) => {
    const item = conversations.get(id)!;
    return {
      conversation: item.saved,
      plan: item.plan,
      resourceStatus: item.plan ? "AVAILABLE" : "NONE",
      pendingStatus: item.pendingStatus,
    };
  };
  function append(
    id: string,
    role: "user" | "assistant",
    kind: string,
    text: string,
    requestId: string,
    planId?: string,
  ) {
    const item = conversations.get(id)!;
    item.saved = { ...item.saved, revision: item.saved.revision + 1 };
    item.messages.push({
      id: crypto.randomUUID(),
      sequence: item.saved.revision,
      requestId,
      role,
      kind,
      text,
      redacted: false,
      planId,
    });
  }
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, options) => {
    const url = new URL(String(input), "http://localhost:8080"),
      path = url.pathname,
      method = options?.method ?? "GET";
    if (path.endsWith("/trace")) return json({ items: [], nextCursor: null });
    if (path === "/ai-agent/conversations") {
      if (method === "GET")
        return json({
          conversations: {
            items: [...conversations.values()].map((value) => value.saved),
            nextCursor: null,
          },
          activeConversationId: selected,
        });
      const id = JSON.parse(String(options?.body)).conversationId;
      if (!conversations.has(id))
        conversations.set(id, {
          saved: {
            id,
            title: "Новый разговор",
            revision: 0,
            lastPlanId: null,
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString(),
            expiresAt: "2099-01-01T00:00:00Z",
          },
          messages: [],
          plan: null,
          pendingStatus: null,
          requests: new Map(),
        });
      selected = id;
      return json(conversations.get(id)!.saved);
    }
    const match = path.match(/^\/ai-agent\/conversations\/([^/]+)(\/messages)?$/);
    if (match) {
      const id = match[1],
        item = conversations.get(id);
      if (!item) return json({ message: "Conversation unavailable" }, 404);
      if (method === "PATCH") {
        selected = id;
        return json(item.saved);
      }
      if (!match[2]) return json(detail(id));
      if (method === "GET")
        return json({
          items: item.messages.filter(
            (message) => message.sequence > Number(url.searchParams.get("after") ?? 0),
          ),
          nextCursor: null,
        });
      const body = JSON.parse(String(options?.body)) as SubmittedMessage;
      if (item.requests.has(body.requestId)) return json(detail(id));
      if (body.expectedRevision !== item.saved.revision)
        return json({ message: "Conversation changed" }, 409);
      item.requests.set(body.requestId, body);
      item.pendingStatus = "PENDING";
      append(id, "user", "INPUT", body.displayText ?? body.text, body.requestId);
      Object.assign(item.messages[item.messages.length - 1], { automatic: body.automatic });
      if (item.saved.title === "Новый разговор") item.saved.title = body.text.slice(0, 65);
      try {
        const response = await domain(
          `http://localhost:8080/ai-agent/plans${body.parentPlanId ? `/${body.parentPlanId}/clarifications` : ""}`,
          {
            method: "POST",
            body: JSON.stringify({
              message: body.text,
              context: body.context,
              ...body.metadata,
              baseReviewId: body.parentPlanId,
            }),
          },
        );
        if (!response.ok) {
          item.pendingStatus = "FAILED";
          append(id, "assistant", "REPLY", "Не удалось подготовить ответ", body.requestId);
          return json(detail(id));
        }
        const plan = (await response.clone().json()) as AgentPlan;
        item.plan = plan;
        item.saved.lastPlanId = plan.planId;
        item.pendingStatus = "COMPLETED";
        append(id, "assistant", "REPLY", plan.explanation, body.requestId, plan.planId);
      } catch (failure) {
        item.requests.delete(body.requestId);
        throw failure;
      }
      return json(detail(id));
    }
    const response = await domain(input, options);
    if (path.endsWith("/execute") && response.ok) {
      const execution = (await response.clone().json()) as ExecutePlanResponse;
      for (const [id, item] of conversations)
        if (item.plan?.planId === execution.planId) {
          item.plan = { ...item.plan, execution, executionStatus: execution.status };
          append(
            id,
            "assistant",
            "EXECUTION",
            execution.actions
              .map((action) => action.result?.summary)
              .filter(Boolean)
              .join("\n") || `Результат выполнения: ${execution.status}`,
            execution.planId,
            execution.planId,
          );
        }
    }
    return response;
  });
  return domain;
}
