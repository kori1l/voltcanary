import { mockAssistantFetch } from "../test/assistantConversationServer";
import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, createMemoryRouter, Outlet, RouterProvider } from "react-router-dom";
import { afterEach, expect, it, vi } from "vitest";
import { PlanReview } from "./PlanReview";
import type { AgentPlan } from "./aiAgentApi";
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
const device = "4980c7b7-b2b7-4e6c-8f90-263dd23cb211";
const plan: AgentPlan = {
  planId: "dashboard-task",
  version: 1,
  payloadDigest: "reviewed",
  executionStatus: "READY",
  planningStatus: "READY",
  summary: "Личный черновик дашборда",
  explanation: "Проверьте шаблон и ресурсы.",
  confirmationRequired: true,
  safetyNotes: [],
  actions: [
    {
      id: "create",
      tool: "CREATE_DASHBOARD",
      title: "Создать личный черновик",
      description: "Reviewed template",
      requiresConfirmation: true,
      parameters: {
        templateId: "my-devices",
        templateName: "My devices",
        templateVersion: 1,
        deviceNames: ["Мой холодильник"],
        definition: {
          name: "Мой обзор",
          defaultContext: { time: { preset: "LAST_24_HOURS" } },
          widgets: [{ id: "power", title: "Измеренная мощность" }],
        },
      },
    },
  ],
};

it("reviews an update in the shared preview and confirms the exact same-dashboard review", async () => {
  const update: AgentPlan = {
    ...plan,
    planId: "update-review",
    actions: [
      {
        ...plan.actions[0],
        tool: "UPDATE_DASHBOARD_DRAFT",
        parameters: {
          ...plan.actions[0].parameters,
          dashboardId: "existing-dashboard",
          draftEtag: "accepted-version",
          baseRevision: 2,
        },
      },
    ],
  };
  const fetch = mockAssistantFetch().mockResolvedValue(
    new Response(
      JSON.stringify({
        planId: update.planId,
        status: "SUCCESS",
        actions: [
          {
            actionId: "create",
            tool: "UPDATE_DASHBOARD_DRAFT",
            status: "SUCCESS",
            result: {
              dashboardId: "existing-dashboard",
              route: "/dashboards/existing-dashboard/edit",
            },
            error: null,
          },
        ],
      }),
      { status: 200 },
    ),
  );
  render(
    <MemoryRouter>
      <PlanReview plan={update} conversational />
    </MemoryRouter>,
  );
  expect(screen.getByRole("region", { name: "Dashboard draft preview" })).toBeInTheDocument();
  expect(fetch.mock.calls).toHaveLength(0);
  await userEvent.click(screen.getByRole("button", { name: "Обновить этот дашборд" }));
  expect(await screen.findByRole("link", { name: /Open/i })).toHaveAttribute(
    "href",
    "/dashboards/existing-dashboard/edit",
  );
  expect(fetch.mock.calls.filter(([, options]) => options?.method === "POST")).toHaveLength(1);
  expect(String(fetch.mock.calls[0][0])).toContain("update-review");
});

it("a persisted review leaves no unsaved request warning when opening the next workflow", async () => {
  setTokens(
    `${btoa("{}")}\.${btoa(JSON.stringify({ sub: "dashboard-owner" }))}.fixture`,
    "fixture",
  );
  const fetch = mockAssistantFetch().mockImplementation(
    async (input) =>
      new Response(
        JSON.stringify(
          String(input).endsWith("/plans")
            ? plan
            : String(input).endsWith("/provider")
              ? { provider: "rules", model: "rules", enabled: false, fallback: "rules" }
              : [],
        ),
        { status: 200 },
      ),
  );
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
          { path: "/ai-agent/settings", element: <p>Settings destination</p> },
        ],
      },
    ],
    { initialEntries: ["/ai-agent"] },
  );
  render(<RouterProvider router={router} />);
  await userEvent.type(
    await screen.findByRole("textbox", { name: "Сообщение помощнику" }),
    "Подготовь личный дашборд",
  );
  await userEvent.click(screen.getByRole("button", { name: "Отправить сообщение" }));
  await screen.findByRole("region", { name: "Dashboard draft preview" });
  await userEvent.click(screen.getByRole("link", { name: "Настройки моделей и провайдеров" }));
  expect(await screen.findByText("Settings destination")).toBeInTheDocument();
  expect(screen.queryByRole("dialog", { name: "Unsaved changes" })).not.toBeInTheDocument();
  expect(fetch.mock.calls.filter(([, options]) => options?.method === "POST")).toHaveLength(1);
});

it("reviews named template panels and creates only after confirmation, then opens the ordinary editor", async () => {
  const fetch = mockAssistantFetch().mockResolvedValue(
    new Response(
      JSON.stringify({
        planId: plan.planId,
        status: "SUCCESS",
        actions: [
          {
            actionId: "create",
            tool: "CREATE_DASHBOARD",
            status: "SUCCESS",
            result: { route: "/dashboards/actual-id/edit", setupStatus: "PRIVATE_DRAFT" },
            error: null,
          },
        ],
      }),
      { status: 200 },
    ),
  );
  render(
    <MemoryRouter>
      <PlanReview plan={plan} />
    </MemoryRouter>,
  );
  const preview = screen.getByRole("region", { name: "Dashboard draft preview" });
  expect(within(preview).getByText("My devices · v1")).toBeInTheDocument();
  expect(within(preview).getByText("Мой холодильник")).toBeInTheDocument();
  expect(within(preview).getByText("Измеренная мощность")).toBeInTheDocument();
  expect(within(preview).getByText("Последние 24 часа")).toBeInTheDocument();
  expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Review and execute" }));
  await userEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel" }));
  expect(fetch).not.toHaveBeenCalled();
  await userEvent.click(screen.getByRole("button", { name: "Review and execute" }));
  await userEvent.click(
    within(screen.getByRole("dialog")).getByRole("button", { name: "Confirm and execute plan" }),
  );
  expect(await screen.findByRole("link", { name: "Open result" })).toHaveAttribute(
    "href",
    "/dashboards/actual-id/edit",
  );
  expect(fetch).toHaveBeenCalledOnce();
});

it("successful device creation offers a separate dashboard task without automatically requesting or executing it", async () => {
  const fetch = mockAssistantFetch();
  const created: AgentPlan = {
    ...plan,
    executionStatus: "SUCCESS",
    actions: [{ ...plan.actions[0], tool: "CREATE_DEVICE" }],
    execution: {
      planId: plan.planId,
      status: "SUCCESS",
      actions: [
        {
          actionId: "create",
          tool: "CREATE_DEVICE",
          status: "SUCCESS",
          result: {
            deviceId: device,
            route: `/devices/${device}?tab=configuration`,
            setupStatus: "SOURCE_NOT_CONNECTED",
          },
          error: null,
        },
      ],
    },
  };
  render(
    <MemoryRouter>
      <PlanReview plan={created} />
    </MemoryRouter>,
  );
  expect(screen.getByRole("link", { name: "Подготовить отдельный дашборд" })).toHaveAttribute(
    "href",
    `/ai-agent?deviceId=${device}&workflow=dashboard`,
  );
  expect(
    screen.getByText("Устройство создано. Источник данных ещё не подключён."),
  ).toBeInTheDocument();
  expect(fetch).not.toHaveBeenCalled();
});

it("the handoff page preserves the device and asks for review without automatically preparing a task", async () => {
  const fetch = mockAssistantFetch().mockImplementation(
    async (input) =>
      new Response(
        JSON.stringify(
          String(input).endsWith("/provider")
            ? { provider: "rules", model: "rules", enabled: false, fallback: "rules" }
            : [],
        ),
        { status: 200 },
      ),
  );
  render(
    <MemoryRouter initialEntries={[`/ai-agent?deviceId=${device}&workflow=dashboard`]}>
      <AiAgentPage />
      <AssistantDrawer />
    </MemoryRouter>,
  );
  expect(await screen.findByRole("textbox", { name: "Сообщение помощнику" })).toHaveValue(
    "Создай личный дашборд для выбранного устройства. Предложи подходящий шаблон и уточни имя перед созданием.",
  );
  expect(screen.queryByRole("textbox", { name: "Context JSON" })).not.toBeInTheDocument();
  expect(fetch.mock.calls.every((call) => !call[1]?.method || call[1].method === "GET")).toBe(true);
});
