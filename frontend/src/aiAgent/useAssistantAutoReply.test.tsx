import { act, cleanup, renderHook } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { useAssistantAutoReply } from "./useAssistantAutoReply";

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});
const reply = { message: "Назови дашборд «Автобус»", label: "Автобус" };

it("waits a full minute and submits a default only once", () => {
  vi.useFakeTimers();
  const send = vi.fn();
  const { result } = renderHook(() => useAssistantAutoReply("task", reply, false, send));
  act(() => {
    vi.advanceTimersByTime(59_000);
  });
  expect(send).not.toHaveBeenCalled();
  expect(result.current.remaining).toBe(1);
  act(() => {
    vi.advanceTimersByTime(181_000);
  });
  expect(send).toHaveBeenCalledExactlyOnceWith(reply.message);
});
it("cancels for the whole question when the user starts answering", () => {
  vi.useFakeTimers();
  const send = vi.fn();
  const { result, rerender } = renderHook(() => useAssistantAutoReply("task", reply, false, send));
  act(() => {
    vi.advanceTimersByTime(30_000);
    result.current.cancel();
  });
  rerender();
  act(() => {
    vi.advanceTimersByTime(120_000);
  });
  expect(send).not.toHaveBeenCalled();
  expect(result.current.visible).toBe(false);
});
it("discards the old deadline when the question changes and clears timers on unmount", () => {
  vi.useFakeTimers();
  const send = vi.fn();
  const { rerender, unmount } = renderHook(
    ({ id }) => useAssistantAutoReply(id, reply, false, send),
    { initialProps: { id: "old" } },
  );
  act(() => {
    vi.advanceTimersByTime(30_000);
  });
  rerender({ id: "new" });
  act(() => {
    vi.advanceTimersByTime(30_000);
  });
  expect(send).not.toHaveBeenCalled();
  unmount();
  act(() => {
    vi.advanceTimersByTime(120_000);
  });
  expect(send).not.toHaveBeenCalled();
});
