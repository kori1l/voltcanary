import { act, cleanup, renderHook } from "@testing-library/react";
import { afterEach, expect, it } from "vitest";
import { useEntityResources } from "./useEntityResources";
import { ApiError } from "../api/client";

afterEach(cleanup);
it("reports an invalid response predicate as a load error without throwing during render", async () => {
  const { result } = renderHook(() => useEntityResources<string[]>("inventory"));
  await act(() => result.current.load("labels", async () => "invalid" as unknown as string[], (data) => { if (!Array.isArray(data)) throw new Error("Invalid inventory"); return !data.length; }));
  expect(result.current.items.labels).toMatchObject({ data: null, status: "error", error: "Invalid inventory" });
});
it("keeps failures and freshness independent per entity and clears permission loss", async () => {
  const { result } = renderHook(() => useEntityResources<number>("fleet"));
  await act(async () => { await Promise.all([result.current.load("a", async () => 1), result.current.load("b", async () => 2)]); });
  const receivedAt = result.current.items.a.receivedAt;
  await act(() => result.current.load("a", async () => { throw new Error("offline"); }));
  expect(result.current.items.a).toMatchObject({ data: 1, status: "stale", receivedAt });
  expect(result.current.items.b).toMatchObject({ data: 2, status: "ready" });
  await act(() => result.current.load("a", async () => { throw new ApiError("revoked", 403); }));
  expect(result.current.items.a).toMatchObject({ data: null, status: "no-access", receivedAt: null });
});
it("rejects superseded requests and responses from an obsolete scope", async () => {
  const { result, rerender } = renderHook(({ scope }) => useEntityResources<string>(scope), { initialProps: { scope: "A" } });
  let release!: (value: string) => void;
  const deferred = new Promise<string>((resolve) => { release = resolve; });
  let pending!: Promise<string | null>;
  act(() => { pending = result.current.load("device", () => deferred); });
  await act(() => result.current.load("device", async () => "new"));
  rerender({ scope: "B" });
  await act(() => result.current.load("device", async () => { throw new Error("B offline"); }));
  await act(async () => { release("old A"); await pending; });
  expect(result.current.items.device).toMatchObject({ data: null, status: "error" });
});
