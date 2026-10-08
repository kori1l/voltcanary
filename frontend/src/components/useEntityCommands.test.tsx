import { act, cleanup, renderHook } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ApiError } from "../api/client";
import { useEntityCommands } from "./useEntityCommands";
afterEach(cleanup);
it("keeps a canonical save after failed reload and retries only its read", async () => {
  const mutate = vi.fn().mockResolvedValue({ id: "saved-1" }); const refresh = vi.fn().mockRejectedValue(new Error("offline"));
  const { result } = renderHook(useEntityCommands);
  await act(() => result.current.run("create", { label: "Create", mutate, refresh, nonIdempotent: true }));
  expect(result.current.outcomes.create).toMatchObject({ phase: "committed", result: { id: "saved-1" }, refreshError: expect.any(String) });
  refresh.mockResolvedValue(true);
  await act(() => result.current.retryRead("create"));
  expect(mutate).toHaveBeenCalledTimes(1); expect(refresh).toHaveBeenCalledTimes(2);
  expect(result.current.outcomes.create.refreshError).toBeUndefined();
});
it("blocks duplicate commands but leaves another entity available", async () => {
  let release!: (value: { id: string }) => void;
  const mutate = vi.fn(() => new Promise<{ id: string }>((resolve) => { release = resolve; }));
  const other = vi.fn().mockResolvedValue({ id: "B" }); const { result } = renderHook(useEntityCommands);
  let pending!: Promise<unknown>;
  act(() => { pending = result.current.run("A:ack", { label: "Ack A", mutate }); });
  await act(async () => { await result.current.run("A:ack", { label: "Ack A", mutate }); await result.current.run("B:ack", { label: "Ack B", mutate: other }); });
  expect(mutate).toHaveBeenCalledTimes(1); expect(other).toHaveBeenCalledTimes(1);
  await act(async () => { release({ id: "A" }); await pending; });
});
it("requires canonical reconciliation after ambiguous create and sends nothing on cancelled review", async () => {
  const mutate = vi.fn().mockRejectedValue(new ApiError("offline", 0)); const refresh = vi.fn().mockResolvedValue(true);
  const { result } = renderHook(useEntityCommands);
  await act(() => result.current.run("create", { label: "Create", mutate, refresh, nonIdempotent: true }));
  await act(() => result.current.run("create", { label: "Create", mutate, refresh, nonIdempotent: true }));
  expect(mutate).toHaveBeenCalledTimes(1); expect(result.current.outcomes.create.phase).toBe("unknown-result");
  act(() => result.current.reconcile("create", { id: "reviewed-existing" }));
  expect(result.current.outcomes.create.phase).toBe("committed");
  await act(() => result.current.run("delete", { label: "Delete", review: async () => false, mutate }));
  expect(mutate).toHaveBeenCalledTimes(1);
});
