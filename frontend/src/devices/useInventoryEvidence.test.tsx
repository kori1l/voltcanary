import { act, cleanup, renderHook } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { useInventoryEvidence } from "./useInventoryEvidence";
import type { Device } from "./devicesApi";
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
const devices = Array.from({ length: 12 }, (_, index) => ({ id: `device-${index}` } as Device));
it("bounds actual telemetry/risk reads to six while every row exposes pending evidence", async () => {
  let active = 0, maximum = 0, started = 0;
  const releases: Array<() => void> = [];
  vi.spyOn(globalThis, "fetch").mockImplementation((input) => new Promise<Response>((resolve) => {
    active++; started++; maximum = Math.max(maximum, active);
    const url = String(input);
    releases.push(() => { active--; resolve(new Response(JSON.stringify(url.endsWith("/latest") ? { readings: { activePowerW: 10 }, timestamp: "2026-10-08T00:00:00Z" } : { level: "LOW", score: 10 }))); });
  }));
  const { result } = renderHook(() => useInventoryEvidence("page-A"));
  let pending!: Promise<boolean>;
  act(() => { pending = result.current.load(devices); });
  await vi.waitFor(() => expect(started).toBe(6));
  expect(Object.values(result.current.telemetry)).toHaveLength(12);
  expect(result.current.telemetry["device-11"].status).toBe("loading");
  for (let wave = 1; wave <= 4; wave++) {
    await act(async () => releases.splice(0).forEach((release) => release()));
    if (wave < 4) await vi.waitFor(() => expect(started).toBe((wave + 1) * 6));
  }
  await act(async () => expect(await pending).toBe(true));
  expect(maximum).toBe(6);
});
it("aborts superseded page reads and does not publish their device evidence", async () => {
  const signals: AbortSignal[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation((_input, init) => new Promise<Response>((_resolve, reject) => { const signal = init!.signal as AbortSignal; signals.push(signal); signal.addEventListener("abort", () => reject(new DOMException("Cancelled", "AbortError")), { once: true }); }));
  const { result, rerender } = renderHook(({ scope }) => useInventoryEvidence(scope), { initialProps: { scope: "page-A" } });
  let pending!: Promise<boolean>;
  act(() => { pending = result.current.load(devices); });
  await vi.waitFor(() => expect(signals).toHaveLength(6));
  rerender({ scope: "page-B" });
  await act(async () => { await pending; });
  expect(signals.every((signal) => signal.aborted)).toBe(true);
  expect(result.current.telemetry).toEqual({});
});
