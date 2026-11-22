import { afterEach, expect, it, vi } from "vitest";
import { RequestDeadlineError, withRequestDeadline } from "./requestDeadline";

afterEach(() => vi.useRealTimers());

it("bounds even an unresponsive operation and aborts its transport", async () => {
  vi.useFakeTimers();
  let signal!: AbortSignal;
  const result = withRequestDeadline(undefined, 50, (current) => {
    signal = current;
    return new Promise(() => {});
  });
  const rejected = expect(result).rejects.toBeInstanceOf(RequestDeadlineError);
  await vi.advanceTimersByTimeAsync(50);
  await rejected;
  expect(signal.aborted).toBe(true);
});

it("preserves explicit cancellation without waiting for a noncooperative operation", async () => {
  const controller = new AbortController();
  const result = withRequestDeadline(controller.signal, 30_000, () => new Promise(() => {}));
  const rejected = expect(result).rejects.toMatchObject({ name: "AbortError" });
  controller.abort();
  await rejected;
});

it("removes timers and external listeners after success", async () => {
  vi.useFakeTimers();
  const controller = new AbortController();
  const remove = vi.spyOn(controller.signal, "removeEventListener");
  expect(await withRequestDeadline(controller.signal, 50, async () => "ok")).toBe("ok");
  expect(vi.getTimerCount()).toBe(0);
  expect(remove).toHaveBeenCalledWith("abort", expect.any(Function));
});
