import { mockAssistantFetch } from "../test/assistantConversationServer";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, expect, it, vi } from "vitest";
import { AssistantDrawer } from "./AssistantDrawer";
import { clearTokens } from "../api/client";

afterEach(() => {
  cleanup();
  localStorage.clear();
  vi.restoreAllMocks();
  vi.useRealTimers();
});
const question = {
  planId: "first",
  summary: "Уточнение",
  explanation: "Как назвать?",
  questions: ["Как назвать?"],
  planningStatus: "NEEDS_INPUT",
  executionStatus: "NEEDS_INPUT",
  confirmationRequired: false,
  actions: [],
  safetyNotes: [],
  clarification: {
    missingFields: ["name"],
    suggestedReply: "Назови дашборд «Обзор рейса». Покажи предпросмотр.",
    suggestionLabel: "Название: «Обзор рейса»",
    suggestedValues: { name: "Обзор рейса" },
  },
};
it("visibly sends a default name after a minute without executing or looping on another question", async () => {
  vi.useFakeTimers();
  const nameQuestion = {
    ...question,
    explanation: "Как назвать дашборд?",
    questions: ["Как назвать дашборд?"],
  };
  const fetch = mockAssistantFetch()
    .mockResolvedValueOnce(new Response(JSON.stringify(nameQuestion), { status: 200 }))
    .mockResolvedValueOnce(
      new Response(JSON.stringify({ ...question, planId: "second" }), { status: 200 }),
    );
  render(
    <MemoryRouter>
      <AssistantDrawer />
    </MemoryRouter>,
  );
  fireEvent.click(screen.getByRole("button", { name: "AI" }));
  fireEvent.change(screen.getByRole("textbox"), { target: { value: "Создай дашборд" } });
  await act(async () => {
    fireEvent.click(screen.getByRole("button", { name: "Отправить сообщение" }));
  });
  expect(screen.getByText(/выберу: Обзор рейса/)).toBeInTheDocument();
  await act(async () => {
    vi.advanceTimersByTime(60_000);
  });
  expect(fetch).toHaveBeenCalledTimes(2);
  expect(JSON.parse(String(fetch.mock.calls[1][1]?.body)).message).toContain(
    '"name":"Обзор рейса"',
  );
  expect(screen.getByText("Выбрано автоматически после минуты ожидания")).toBeInTheDocument();
  expect(screen.queryByText(/Используй предложенные значения:/)).not.toBeInTheDocument();
  await act(async () => {
    vi.advanceTimersByTime(120_000);
  });
  expect(fetch).toHaveBeenCalledTimes(2);
});
it("does not auto-answer after typing starts even if the draft is then erased", async () => {
  vi.useFakeTimers();
  const fetch = mockAssistantFetch().mockResolvedValue(
    new Response(JSON.stringify(question), { status: 200 }),
  );
  render(
    <MemoryRouter>
      <AssistantDrawer />
    </MemoryRouter>,
  );
  fireEvent.click(screen.getByRole("button", { name: "AI" }));
  fireEvent.change(screen.getByRole("textbox"), { target: { value: "Создай дашборд" } });
  await act(async () => {
    fireEvent.click(screen.getByRole("button", { name: "Отправить сообщение" }));
  });
  fireEvent.change(screen.getByRole("textbox"), { target: { value: "Мой" } });
  fireEvent.change(screen.getByRole("textbox"), { target: { value: "" } });
  await act(async () => {
    vi.advanceTimersByTime(120_000);
  });
  expect(fetch).toHaveBeenCalledTimes(1);
});
it("allows a new missing field while preventing repeated defaults for that field", async () => {
  vi.useFakeTimers();
  const periodQuestion = {
    ...question,
    planId: "period",
    clarification: {
      missingFields: ["period"],
      suggestedReply: "Используй последние 24 часа",
      suggestionLabel: "Последние 24 часа",
      suggestedValues: { period: "LAST_24_HOURS" },
    },
  };
  const fetch = mockAssistantFetch()
    .mockResolvedValueOnce(new Response(JSON.stringify(question), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify(periodQuestion), { status: 200 }))
    .mockResolvedValueOnce(
      new Response(JSON.stringify({ ...periodQuestion, planId: "period-again" }), { status: 200 }),
    );
  render(
    <MemoryRouter>
      <AssistantDrawer />
    </MemoryRouter>,
  );
  fireEvent.click(screen.getByRole("button", { name: "AI" }));
  fireEvent.change(screen.getByRole("textbox"), { target: { value: "Создай дашборд" } });
  await act(async () => {
    fireEvent.click(screen.getByRole("button", { name: "Отправить сообщение" }));
  });
  await act(async () => {
    vi.advanceTimersByTime(60_000);
  });
  expect(fetch).toHaveBeenCalledTimes(2);
  await act(async () => {
    vi.advanceTimersByTime(60_000);
  });
  expect(fetch).toHaveBeenCalledTimes(3);
  await act(async () => {
    vi.advanceTimersByTime(120_000);
  });
  expect(fetch).toHaveBeenCalledTimes(3);
});
it("retains a slow request when minimized, shows elapsed time, and accepts its late reply", async () => {
  vi.useFakeTimers();
  let finish: (value: Response) => void = () => {};
  const fetch = mockAssistantFetch().mockImplementation(
    () =>
      new Promise((done) => {
        finish = done;
      }),
  );
  render(
    <MemoryRouter>
      <AssistantDrawer />
    </MemoryRouter>,
  );
  fireEvent.click(screen.getByRole("button", { name: "AI" }));
  fireEvent.change(screen.getByRole("textbox"), { target: { value: "Создай дашборд" } });
  await act(async () => {
    fireEvent.click(screen.getByRole("button", { name: "Отправить сообщение" }));
  });
  act(() => {
    vi.advanceTimersByTime(65_000);
  });
  expect(
    screen
      .getAllByRole("status")
      .some((element) => element.textContent?.includes("Подготавливаю запрос…")),
  ).toBe(true);
  expect(screen.getByRole("timer")).toHaveTextContent("1:05");
  fireEvent.click(screen.getByRole("button", { name: "Свернуть помощника" }));
  act(() => {
    vi.advanceTimersByTime(30_000);
  });
  fireEvent.click(screen.getByRole("button", { name: "AI" }));
  expect(screen.getByRole("timer")).toHaveTextContent("1:35");
  expect(fetch).toHaveBeenCalledTimes(1);
  await act(async () => {
    finish(new Response(JSON.stringify(question), { status: 200 }));
  });
  expect(screen.getByText("Как назвать?")).toBeInTheDocument();
  expect(screen.queryByRole("timer")).not.toBeInTheDocument();
  expect(screen.getByRole("textbox")).toHaveValue("");
});
it("retains a reviewed conversation across all modes and unsent refinements without extra requests", async () => {
  const fetch = mockAssistantFetch().mockResolvedValue(
    new Response(JSON.stringify(question), { status: 200 }),
  );
  render(
    <MemoryRouter>
      <AssistantDrawer />
    </MemoryRouter>,
  );
  await userEvent.click(screen.getByRole("button", { name: "AI" }));
  await userEvent.type(
    screen.getByRole("textbox", { name: "Сообщение помощнику" }),
    "Создай дашборд",
  );
  await userEvent.click(screen.getByRole("button", { name: "Отправить сообщение" }));
  await screen.findByText("Как назвать?");
  await userEvent.type(screen.getByRole("textbox", { name: "Сообщение помощнику" }), "Мой обзор");
  for (const mode of ["Боковая панель", "Развернуть", "Плавающее окно"]) {
    await userEvent.click(screen.getByRole("button", { name: "Вид и расположение" }));
    await userEvent.click(screen.getByRole("button", { name: mode }));
  }
  expect(screen.getByRole("textbox", { name: "Сообщение помощнику" })).toHaveValue("Мой обзор");
  expect(screen.getByText("Как назвать?")).toBeInTheDocument();
  expect(fetch).toHaveBeenCalledTimes(1);
  expect(JSON.parse(localStorage.getItem("voltcanary.assistant.window.v1")!)).toEqual({
    mode: "floating",
    position: "right",
  });
});
it("ignores late planning responses after identity changes", async () => {
  let resolve: (value: Response) => void = () => {};
  mockAssistantFetch().mockImplementation(
    () =>
      new Promise((done) => {
        resolve = done;
      }),
  );
  render(
    <MemoryRouter>
      <AssistantDrawer />
    </MemoryRouter>,
  );
  await userEvent.click(screen.getByRole("button", { name: "AI" }));
  await userEvent.type(screen.getByRole("textbox"), "Создай дашборд");
  await userEvent.click(screen.getByRole("button", { name: "Отправить сообщение" }));
  act(() => clearTokens());
  await act(async () => resolve(new Response(JSON.stringify(question), { status: 200 })));
  expect(screen.queryByText("Как назвать?")).not.toBeInTheDocument();
  expect(screen.getByRole("textbox")).toHaveValue("");
});
it("automatically runs read-only analysis and shows the returned explanation", async () => {
  const fetch = mockAssistantFetch().mockImplementation(
    async (input) =>
      new Response(
        JSON.stringify(
          String(input).endsWith("/execute")
            ? {
                planId: "first",
                status: "SUCCESS",
                actions: [
                  {
                    actionId: "read",
                    tool: "EXPLAIN_ALERT",
                    status: "SUCCESS",
                    result: { summary: "Температура превысила порог на 1,4 °C." },
                    error: null,
                  },
                ],
              }
            : {
                ...question,
                planningStatus: "READY",
                executionStatus: "READY",
                actions: [
                  {
                    id: "read",
                    tool: "EXPLAIN_ALERT",
                    title: "Разбор события",
                    description: "Read only",
                    requiresConfirmation: false,
                    parameters: {},
                  },
                ],
              },
        ),
        { status: 200 },
      ),
  );
  render(
    <MemoryRouter>
      <AssistantDrawer />
    </MemoryRouter>,
  );
  await userEvent.click(screen.getByRole("button", { name: "AI" }));
  await userEvent.type(screen.getByRole("textbox"), "Объясни событие");
  await userEvent.click(screen.getByRole("button", { name: "Отправить сообщение" }));
  expect(await screen.findByText("Температура превысила порог на 1,4 °C.")).toBeInTheDocument();
  await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2));
});
