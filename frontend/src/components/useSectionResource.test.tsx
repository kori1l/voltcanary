import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { useSectionResource } from "./useSectionResource";

afterEach(cleanup);

describe("independent resource evidence", () => {
  it("distinguishes a successful empty result from a failed request and retains previous data", async () => {
    const { result } = renderHook(() => useSectionResource<number[]>());
    await act(() => result.current.load(async () => [], (data) => !data.length));
    expect(result.current.status).toBe("empty");
    await act(() => result.current.load(async () => [7]));
    const observedAt = result.current.receivedAt;
    await act(() => result.current.load(async () => { throw new Error("Gateway offline"); }));
    expect(result.current).toMatchObject({ status: "stale", data: [7], receivedAt: observedAt, error: "Gateway offline" });
    await act(() => result.current.load(async () => [8]));
    expect(result.current).toMatchObject({ status: "ready", data: [8], error: null });
  });

  it("ignores an older response arriving after a more recent result", async () => {
    const { result } = renderHook(() => useSectionResource<string>());
    let release!: (value: string) => void;
    const older = new Promise<string>((resolve) => { release = resolve; });
    let pending!: Promise<string | null>;
    act(() => { pending = result.current.load(() => older); });
    await act(() => result.current.load(async () => "newer"));
    await act(async () => { release("older"); await pending; });
    expect(result.current.data).toBe("newer");
  });

  it("does not display another entity's retained data after changing scope", async () => {
    const { result, rerender } = renderHook(({ scope }) => useSectionResource<string>(scope), { initialProps: { scope: "device-a" } });
    await act(() => result.current.load(async () => "A"));
    rerender({ scope: "device-b" });
    expect(result.current.data).toBeNull();
    await act(() => result.current.load(async () => { throw new Error("Unavailable B"); }));
    await waitFor(() => expect(result.current.status).toBe("error"));
    expect(result.current.data).toBeNull();
  });
});
