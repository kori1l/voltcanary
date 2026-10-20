import { cleanup, renderHook, waitFor } from "@testing-library/react";
import { StrictMode, useEffect } from "react";
import { afterEach, expect, it, vi } from "vitest";
import * as api from "../../alerts/alertsApi";
import * as devices from "../../devices/devicesApi";
import { useAlertConsoleData } from "./useAlertConsoleData";

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

it("finishes summary and inbox loading after StrictMode replays the initial read", async () => {
  vi.spyOn(api, "listAlertPage").mockResolvedValue({ content: [], page: 0, size: 100, totalPages: 1, hasNext: false, totalElements: 19 });
  const summary = vi.spyOn(api, "getAlertSummary").mockResolvedValue({ total: 19, byStatus: { OPEN: 19 }, bySeverity: { CRITICAL: 1 }, byDevice: {}, generatedAt: "2026-10-10T22:00:00Z" });
  vi.spyOn(api, "listAlertRules").mockResolvedValue([]);
  vi.spyOn(api, "listNotificationChannels").mockResolvedValue([]);
  vi.spyOn(devices, "listDevices").mockResolvedValue([]);
  vi.spyOn(devices, "listLocations").mockResolvedValue([]);
  const { result } = renderHook(() => {
    const data = useAlertConsoleData({ limit: 100 });
    useEffect(() => { void data.load(false); }, []);
    return data;
  }, { wrapper: StrictMode });
  await waitFor(() => expect(result.current.summary.data?.byStatus.OPEN).toBe(19));
  expect(result.current.summary.refreshing).toBe(false);
  expect(result.current.alerts.refreshing).toBe(false);
  expect(result.current.pagination?.totalElements).toBe(19);
  expect(summary).toHaveBeenCalledTimes(2);
});
