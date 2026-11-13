import {act, cleanup, render, screen} from "@testing-library/react";
import {afterEach, expect, it, vi} from "vitest";
import {AssistantProgress} from "./AssistantProgress";

afterEach(() => {cleanup(); vi.useRealTimers();});

it("keeps showing elapsed wait beyond both former limits without inventing stages", () => {
  vi.useFakeTimers();
  const view = render(<AssistantProgress phase="planning"/>);
  act(() => {vi.advanceTimersByTime(95_000);});
  expect(screen.getByRole("status")).toHaveTextContent("Подготавливаю запрос…");
  expect(screen.getByRole("timer")).toHaveTextContent("1:35");
  expect(screen.queryByText(/Проверяю виджеты/)).not.toBeInTheDocument();
  view.rerender(<AssistantProgress phase="reading"/>);
  expect(screen.getByRole("status")).toHaveTextContent("Читаю данные…");
  view.unmount();
  expect(vi.getTimerCount()).toBe(0);
});
