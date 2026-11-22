import { mockAssistantFetch } from "../test/assistantConversationServer";
import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createMemoryRouter, Outlet, RouterProvider } from "react-router-dom";
import { afterEach, expect, it, vi } from "vitest";
import { AssistantDrawer } from "./AssistantDrawer";
import { WorkflowDraftProvider } from "../components/WorkflowDraftGuard";
import { clearTokens, setSession } from "../api/client";

afterEach(() => {
  cleanup();
  clearTokens();
  localStorage.clear();
  vi.restoreAllMocks();
});
function setup() {
  setSession(`${btoa("{}")}\.${btoa(JSON.stringify({ sub: "drawer-owner" }))}.fixture`, "fixture");
  const router = createMemoryRouter(
    [
      {
        element: (
          <WorkflowDraftProvider>
            <Outlet />
          </WorkflowDraftProvider>
        ),
        children: [
          { path: "/vehicles", element: <AssistantDrawer /> },
          { path: "/devices/bus", element: <p>Bus source setup</p> },
        ],
      },
    ],
    { initialEntries: ["/vehicles"] },
  );
  render(<RouterProvider router={router} />);
}
it("opens the created device after a persisted drawer plan without an unsaved-change blocker", async () => {
  mockAssistantFetch().mockImplementation(
    async (input) =>
      new Response(
        JSON.stringify(
          String(input).endsWith("/execute")
            ? {
                planId: "bus-plan",
                status: "SUCCESS",
                actions: [
                  {
                    actionId: "create",
                    tool: "CREATE_DEVICE",
                    status: "SUCCESS",
                    result: { route: "/devices/bus" },
                  },
                ],
              }
            : {
                planId: "bus-plan",
                version: 1,
                payloadDigest: "reviewed",
                planningStatus: "READY",
                summary: "Create bus",
                explanation: "Source next",
                confirmationRequired: true,
                safetyNotes: [],
                actions: [
                  {
                    id: "create",
                    tool: "CREATE_DEVICE",
                    title: "Create bus",
                    description: "One vehicle",
                    requiresConfirmation: true,
                    parameters: { name: "Bus", type: "VEHICLE" },
                  },
                ],
              },
        ),
        { status: 200 },
      ),
  );
  setup();
  await userEvent.click(screen.getByRole("button", { name: "AI" }));
  await userEvent.type(
    screen.getByRole("textbox", { name: "Сообщение помощнику" }),
    "Create one bus",
  );
  await userEvent.click(screen.getByRole("button", { name: "Отправить сообщение" }));
  await userEvent.click(await screen.findByRole("button", { name: "Review and execute" }));
  await userEvent.click(
    within(screen.getByRole("dialog", { name: "Confirm and execute plan" })).getByRole("button", {
      name: "Confirm and execute plan",
    }),
  );
  await userEvent.click(await screen.findByRole("link", { name: "Open result" }));
  expect(await screen.findByText("Bus source setup")).toBeInTheDocument();
  expect(screen.queryByRole("dialog", { name: "Unsaved changes" })).not.toBeInTheDocument();
});
it("preserves unsubmitted text across minimizing and window mode changes", async () => {
  mockAssistantFetch();
  setup();
  await userEvent.click(screen.getByRole("button", { name: "AI" }));
  await userEvent.type(screen.getByRole("textbox", { name: "Сообщение помощнику" }), "Мой запрос");
  await userEvent.click(screen.getByRole("button", { name: "Вид и расположение" }));
  await userEvent.click(screen.getByRole("button", { name: "Развернуть" }));
  expect(document.querySelector(".assistant-window-expanded")).not.toBeNull();
  await userEvent.click(screen.getByRole("button", { name: "Свернуть помощника" }));
  await userEvent.click(screen.getByRole("button", { name: "AI" }));
  expect(screen.getByRole("textbox", { name: "Сообщение помощнику" })).toHaveValue("Мой запрос");
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});
