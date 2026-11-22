import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { useDeviceSimulationPresets } from "./useDeviceSimulationPresets";
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});
it("loads simulator presets only when that setup mode is opened and keeps failed reads retryable", async () => {
  let status = 503;
  const fetch = vi
    .spyOn(globalThis, "fetch")
    .mockImplementation(
      async () =>
        new Response(
          JSON.stringify(
            status === 200
              ? [{ id: "normal", name: "Normal" }]
              : { message: "Simulator unavailable" },
          ),
          { status },
        ),
    );
  const { result, rerender } = renderHook(({ enabled }) => useDeviceSimulationPresets(enabled), {
    initialProps: { enabled: false },
  });
  expect(fetch).not.toHaveBeenCalled();
  rerender({ enabled: true });
  await waitFor(() => expect(result.current.status).toBe("error"));
  status = 200;
  await act(async () => {
    await result.current.retry();
  });
  expect(result.current.data?.[0].id).toBe("normal");
});
