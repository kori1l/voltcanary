import { mockAssistantFetch } from "../test/assistantConversationServer";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createMemoryRouter, Outlet, RouterProvider } from "react-router-dom";
import { afterEach, expect, it, vi } from "vitest";
import { AiAgentPage } from "../pages/AiAgentPage";
import { AssistantDrawer } from "./AssistantDrawer";
import { WorkflowDraftProvider } from "../components/WorkflowDraftGuard";
import { clearTokens, setTokens } from "../api/client";

afterEach(() => {
  cleanup();
  clearTokens();
  localStorage.clear();
  vi.restoreAllMocks();
});
const question = {
  planId: "question",
  version: 1,
  payloadDigest: "old",
  planningStatus: "NEEDS_INPUT",
  executionStatus: "NEEDS_INPUT",
  summary: "Нужно имя",
  explanation: "Как назвать дашборд?",
  questions: ["Как назвать дашборд?"],
  actions: [],
  safetyNotes: [],
  confirmationRequired: false,
};
const ready = {
  ...question,
  planId: "clarified",
  payloadDigest: "new",
  planningStatus: "READY",
  executionStatus: "READY",
  summary: "Уточнённый обзор",
  questions: [],
  confirmationRequired: true,
  actions: [
    {
      id: "create",
      tool: "CREATE_DASHBOARD",
      title: "Создать обзор",
      description: "Private draft",
      requiresConfirmation: true,
      parameters: {},
    },
  ],
};

it.each(["page", "drawer"])(
  "continues a clarification in the %s and preserves the answer for retry",
  async (surface) => {
    setTokens(`${btoa("{}")}\.${btoa(JSON.stringify({ sub: "reply-owner" }))}.fixture`, "fixture");
    let replies = 0;
    const fetch = mockAssistantFetch().mockImplementation(async (input) => {
      const url = String(input);
      if (url.endsWith("/clarifications")) {
        replies++;
        return new Response(
          JSON.stringify(replies === 1 ? { message: "Temporary clarification outage" } : ready),
          { status: replies === 1 ? 502 : 200 },
        );
      }
      return new Response(JSON.stringify(url.endsWith("/plans") ? question : []), { status: 200 });
    });
    const router = createMemoryRouter(
      [
        {
          element: (
            <WorkflowDraftProvider>
              <Outlet />
            </WorkflowDraftProvider>
          ),
          children: [
            {
              path: "/ai-agent",
              element: (
                <>
                  <AiAgentPage />
                  <AssistantDrawer />
                </>
              ),
            },
            { path: "/vehicles", element: <AssistantDrawer /> },
          ],
        },
      ],
      { initialEntries: [surface === "page" ? "/ai-agent" : "/vehicles"] },
    );
    render(<RouterProvider router={router} />);
    if (surface === "drawer") await userEvent.click(screen.getByRole("button", { name: "AI" }));
    const request = await screen.findByRole("textbox", { name: "Сообщение помощнику" });
    await userEvent.clear(request);
    await userEvent.type(request, "Создай личный дашборд");
    await userEvent.click(screen.getByRole("button", { name: "Отправить сообщение" }));
    await screen.findByText("Как назвать дашборд?");
    const answer = screen.getByRole("textbox", { name: "Сообщение помощнику" });
    await userEvent.type(answer, "Мой обзор");
    if (surface === "drawer") {
      await userEvent.click(screen.getByRole("button", { name: "Свернуть помощника" }));
      await userEvent.click(screen.getByRole("button", { name: "AI" }));
      expect(answer).toHaveValue("Мой обзор");
    }
    await userEvent.click(screen.getByRole("button", { name: "Отправить сообщение" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Отправить сообщение" })).toBeEnabled(),
    );
    expect(answer).toHaveValue("Мой обзор");
    expect(screen.getByText("Как назвать дашборд?")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Отправить сообщение" }));
    expect(await screen.findByRole("heading", { name: "Уточнённый обзор" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Создать этот дашборд" })).toBeEnabled();
    expect(screen.getByRole("textbox", { name: "Сообщение помощнику" })).toHaveValue("");
    expect(screen.getByText("Создай личный дашборд", { selector: "p" })).toBeInTheDocument();
    expect(fetch.mock.calls.some(([url]) => String(url).endsWith("/execute"))).toBe(false);
    expect(replies).toBe(2);
    await userEvent.type(
      screen.getByRole("textbox", { name: "Сообщение помощнику" }),
      "Убери события",
    );
    expect(screen.getByRole("button", { name: "Создать этот дашборд" })).toBeDisabled();
    expect(fetch.mock.calls.some(([url]) => String(url).endsWith("/execute"))).toBe(false);
  },
);
