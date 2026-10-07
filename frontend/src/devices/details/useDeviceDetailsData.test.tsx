import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { StrictMode, useEffect } from "react";
import { afterEach, expect, it, vi } from "vitest";
import { useDeviceDetailsData } from "./useDeviceDetailsData";

afterEach(() => { cleanup(); vi.restoreAllMocks(); });
const device = { id: "device", name: "Kitchen fridge", type: "FRIDGE", status: "ACTIVE", locationId: "kitchen" };
it("does not turn a superseded StrictMode load into a device error", async () => {
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => new Response(JSON.stringify(String(input).endsWith("/devices/device") ? device : []), { status: 200 }));
  const { result } = renderHook(() => {
    const data = useDeviceDetailsData("device");
    useEffect(() => { void data.loadDeviceContext(); }, []);
    return data;
  }, { wrapper: StrictMode });
  await waitFor(() => expect(result.current.device?.name).toBe("Kitchen fridge"));
  expect(result.current.error).toBeNull();
});
it("shows the actual device error and clears it after retry succeeds", async () => {
  let failed = true;
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    const isDevice = String(input).endsWith("/devices/device");
    return new Response(JSON.stringify(isDevice ? failed ? { message: "Device service unavailable" } : device : []), { status: isDevice && failed ? 503 : 200 });
  });
  const { result } = renderHook(() => useDeviceDetailsData("device"));
  await act(() => result.current.loadDeviceContext());
  expect(result.current.error).toBe("Device service unavailable");
  failed = false;
  await act(() => result.current.loadDeviceContext());
  expect(result.current.device?.id).toBe("device");
  expect(result.current.error).toBeNull();
});
it("does not report a missing device when only location lookup fails", async () => {
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    const isDevice = String(input).endsWith("/devices/device");
    return new Response(JSON.stringify(isDevice ? device : { message: "Locations unavailable" }), { status: isDevice ? 200 : 503 });
  });
  const { result } = renderHook(() => useDeviceDetailsData("device"));
  await act(() => result.current.loadDeviceContext());
  expect(result.current.device?.id).toBe("device");
  expect(result.current.error).toBeNull();
  expect(result.current.locationName).toBe("Location unavailable");
});
