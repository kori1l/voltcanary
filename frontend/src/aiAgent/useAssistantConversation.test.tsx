import { mockAssistantFetch } from "../test/assistantConversationServer";
import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { useAssistantConversation } from "./useAssistantConversation";
import type { AgentPlan } from "./aiAgentApi";
import type { DashboardTaskSummary } from "./aiAgentApi";
import { setTokens } from "../api/client";
import { rememberDashboardTask } from "./assistantTaskRecovery";
const plan = (id: string, ancestors?: string[]): AgentPlan => ({
  planId: id,
  summary: id,
  explanation: id,
  previousReviewIds: ancestors,
  confirmationRequired: true,
  safetyNotes: [],
  planningStatus: "READY",
  executionStatus: "READY",
  actions: [
    {
      id,
      tool: "CREATE_DASHBOARD",
      title: id,
      description: id,
      requiresConfirmation: true,
      parameters: { definition: { name: id, widgets: [] } },
    },
  ],
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  localStorage.clear();
});
it("undoes and redoes exact reviews with GET only, then branches refinement from the selected plan", async () => {
  const fetch = mockAssistantFetch()
    .mockResolvedValueOnce(new Response(JSON.stringify(plan("a")), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify(plan("b", ["a"])), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify(plan("a")), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify(plan("b", ["a"])), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify(plan("a")), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify(plan("c", ["a"])), { status: 200 }));
  const { result } = renderHook(() => useAssistantConversation());
  await act(() => result.current.send("Create"));
  await act(() => result.current.send("Move"));
  expect(result.current.conversation.reviewIds).toEqual(["a", "b"]);
  await act(() => result.current.selectReview(0));
  expect(result.current.conversation.plan).toEqual(plan("a"));
  expect(result.current.redoIndex).toBe(1);
  expect(fetch.mock.calls[2][1]?.method ?? "GET").toBe("GET");
  await act(() => result.current.selectReview(1));
  expect(result.current.conversation.plan?.planId).toBe("b");
  await act(() => result.current.selectReview(0));
  await act(() => result.current.send("Rename"));
  expect(String(fetch.mock.calls[5][0])).toContain("/a/clarifications");
  expect(result.current.conversation.reviewIds).toEqual(["a", "c"]);
  expect(fetch.mock.calls.some((call) => String(call[0]).endsWith("/execute"))).toBe(false);
});

const working = (
  taskId: string,
  revision = 1,
  status: DashboardTaskSummary["status"] = "READY",
): DashboardTaskSummary => ({
  taskId,
  branchId: taskId,
  revision,
  status,
  kind: "DASHBOARD",
  originalRequest: "Создай карту и скорость",
  name: "Мой трамвай",
  lastAcceptedReviewId: status === "READY" ? "saved" : null,
  failureCategory: status === "FAILED" ? "HTTP_502" : null,
  expiresAt: "2099-01-01T00:00:00Z",
  pending: [],
});
const login = () =>
  setTokens(`header.${btoa(JSON.stringify({ sub: "working-state-user" }))}.signature`, "refresh");
it("reuses the original request identity after a lost initial response", async () => {
  login();
  const domain = mockAssistantFetch().mockResolvedValue(
    new Response(JSON.stringify(plan("saved"))),
  );
  const transport = vi.mocked(globalThis.fetch),
    implementation = transport.getMockImplementation()!;
  const bodies: unknown[] = [];
  transport.mockImplementation(async (url, options) => {
    const response = await implementation(url, options);
    if (String(url).endsWith("/messages") && options?.method === "POST") {
      bodies.push(JSON.parse(String(options.body)));
      if (bodies.length === 1) throw new TypeError("Lost response");
    }
    return response;
  });
  const { result } = renderHook(() => useAssistantConversation());
  await waitFor(() => expect(result.current.busy).toBe(false));
  await act(() => result.current.send("Создай карту"));
  await act(() => result.current.send("Создай карту"));
  expect(bodies).toHaveLength(2);
  expect(bodies[1]).toEqual(bodies[0]);
  expect(domain).toHaveBeenCalledTimes(1);
  expect(result.current.conversation.turns).toHaveLength(2);
  expect(result.current.conversation.plan?.planId).toBe("saved");
});
it("recovers an initial failed task without inventing a review and continues by stored identity", async () => {
  login();
  rememberDashboardTask("root-task", "root-task");
  const fetch = mockAssistantFetch()
    .mockResolvedValueOnce(
      new Response(JSON.stringify({ task: working("root-task", 2, "FAILED"), lastReview: null })),
    )
    .mockResolvedValueOnce(
      new Response(JSON.stringify({ ...plan("saved"), task: working("root-task", 3) })),
    );
  const { result } = renderHook(() => useAssistantConversation());
  await waitFor(() => expect(result.current.busy).toBe(false));
  await act(() => result.current.recover());
  expect(result.current.conversation.plan).toBeNull();
  expect(result.current.conversation.task?.name).toBe("Мой трамвай");
  await act(() => result.current.send("Попробуй иначе"));
  const body = JSON.parse(String(fetch.mock.calls[1][1]?.body));
  expect(body.message).toBe("Попробуй иначе");
  expect(body.taskId).toBe("root-task");
  expect(body.expectedTaskRevision).toBe(2);
});
it("retains the previous preview after a rejected refinement", async () => {
  const failed = {
    ...plan("failed", ["a"]),
    actions: [],
    planningStatus: "NEEDS_INPUT",
    task: working("task", 2, "FAILED"),
  };
  mockAssistantFetch()
    .mockResolvedValueOnce(new Response(JSON.stringify({ ...plan("a"), task: working("task") })))
    .mockResolvedValueOnce(new Response(JSON.stringify(failed)));
  const { result } = renderHook(() => useAssistantConversation());
  await act(() => result.current.send("Создай карту"));
  await act(() => result.current.send("Сделай меньше"));
  expect(result.current.conversation.retainedPreview?.planId).toBe("a");
  expect(result.current.conversation.plan?.planId).toBe("failed");
  expect(result.current.conversation.task?.revision).toBe(2);
});
it("restores the selected old review after remount and continues from its exact snapshot", async () => {
  login();
  const first = {
    ...plan("a"),
    task: { ...working("task", 1), name: "Старое имя", lastAcceptedReviewId: "a" },
  };
  const latest = {
    ...plan("b", ["a"]),
    task: { ...working("task", 2), name: "Новое имя", lastAcceptedReviewId: "b" },
  };
  const fetch = mockAssistantFetch()
    .mockResolvedValueOnce(new Response(JSON.stringify(first)))
    .mockResolvedValueOnce(new Response(JSON.stringify(latest)))
    .mockResolvedValueOnce(new Response(JSON.stringify(first)))
    .mockResolvedValueOnce(new Response(JSON.stringify(first)))
    .mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          ...plan("c", ["a"]),
          task: { ...first.task, branchId: "fork", revision: 2 },
        }),
      ),
    );
  const initial = renderHook(() => useAssistantConversation());
  await waitFor(() => expect(initial.result.current.busy).toBe(false));
  await act(() => initial.result.current.send("Создай"));
  await act(() => initial.result.current.send("Переименуй"));
  await act(() => initial.result.current.selectReview(0));
  initial.unmount();
  const restored = renderHook(() => useAssistantConversation());
  await waitFor(() => expect(restored.result.current.busy).toBe(false));
  expect(restored.result.current.conversation.plan).toEqual(first);
  expect(restored.result.current.conversation.task?.name).toBe("Старое имя");
  expect(fetch.mock.calls.slice(3, 4).every((call) => (call[1]?.method ?? "GET") === "GET")).toBe(
    true,
  );
  await act(() => restored.result.current.send("Уменьши карточку"));
  const body = JSON.parse(String(fetch.mock.calls[4][1]?.body));
  expect(body.baseReviewId).toBe("a");
  expect(body.expectedTaskRevision).toBe(1);
  expect(restored.result.current.conversation.task?.branchId).toBe("fork");
});
