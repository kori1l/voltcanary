import { act, cleanup, renderHook } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { useInventoryEvidence } from "./useInventoryEvidence";
import type { Device } from "./devicesApi";
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});
const devices = Array.from({ length: 12 }, (_, index) => ({ id: `device-${index}` }) as Device);
it("treats absent risk as empty evidence while retaining real service and access failures", async () => {
  let status = 404;
  vi.spyOn(globalThis, "fetch").mockImplementation(
    async (input) =>
      new Response(
        JSON.stringify(
          String(input).endsWith("/latest")
            ? { readings: { activePowerW: 10 }, timestamp: "2026-10-08T00:00:00Z" }
            : { message: "Risk read unavailable" },
        ),
        { status: String(input).endsWith("/latest") ? 200 : status },
      ),
  );
  const { result } = renderHook(() => useInventoryEvidence("owned-page"));
  await act(async () => {
    expect(await result.current.load([devices[0]])).toBe(true);
  });
  expect(result.current.risk["device-0"].status).toBe("empty");
  expect(result.current.risk["device-0"].error).toBeNull();
  expect(result.current.riskByDevice["device-0"]).toBeNull();
  status = 500;
  await act(async () => {
    expect(await result.current.reloadRisk("device-0")).toBe(false);
  });
  expect(result.current.risk["device-0"].status).toBe("error");
  status = 403;
  await act(async () => {
    await result.current.reloadRisk("device-0");
  });
  expect(result.current.risk["device-0"].status).toBe("no-access");
  expect(result.current.riskByDevice["device-0"]).toBeNull();
});
it("bounds actual telemetry/risk reads to six while every row exposes pending evidence", async () => {
  let active = 0,
    maximum = 0,
    started = 0;
  const releases: Array<() => void> = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(
    (input) =>
      new Promise<Response>((resolve) => {
        active++;
        started++;
        maximum = Math.max(maximum, active);
        const url = String(input);
        releases.push(() => {
          active--;
          resolve(
            new Response(
              JSON.stringify(
                url.endsWith("/latest")
                  ? { readings: { activePowerW: 10 }, timestamp: "2026-10-08T00:00:00Z" }
                  : { level: "LOW", score: 10 },
              ),
            ),
          );
        });
      }),
  );
  const { result } = renderHook(() => useInventoryEvidence("page-A"));
  let pending!: Promise<boolean>;
  act(() => {
    pending = result.current.load(devices);
  });
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
  vi.spyOn(globalThis, "fetch").mockImplementation(
    (_input, init) =>
      new Promise<Response>((_resolve, reject) => {
        const signal = init!.signal as AbortSignal;
        signals.push(signal);
        signal.addEventListener(
          "abort",
          () => reject(new DOMException("Cancelled", "AbortError")),
          { once: true },
        );
      }),
  );
  const { result, rerender } = renderHook(({ scope }) => useInventoryEvidence(scope), {
    initialProps: { scope: "page-A" },
  });
  let pending!: Promise<boolean>;
  act(() => {
    pending = result.current.load(devices);
  });
  await vi.waitFor(() => expect(signals).toHaveLength(6));
  rerender({ scope: "page-B" });
  await act(async () => {
    await pending;
  });
  expect(signals.every((signal) => signal.aborted)).toBe(true);
  expect(result.current.telemetry).toEqual({});
});
