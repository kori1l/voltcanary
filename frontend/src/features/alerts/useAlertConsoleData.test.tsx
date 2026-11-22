import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { StrictMode, useEffect } from "react";
import { afterEach, expect, it, vi } from "vitest";
import * as api from "../../alerts/alertsApi";
import * as devices from "../../devices/devicesApi";
import { useAlertConsoleData } from "./useAlertConsoleData";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

it("loads rule pages independently of the inbox and reference lists", async () => {
  const rules = vi
    .spyOn(api, "listAlertRules")
    .mockImplementation(async (_filters, page = 0) =>
      Array.from(
        { length: page === 0 ? 100 : 1 },
        (_, i) => ({ id: `${page}-${i}` }) as api.AlertRule,
      ),
    );
  const channels = vi.spyOn(api, "listNotificationChannels").mockResolvedValue([]);
  vi.spyOn(devices, "listDevices").mockResolvedValue([]);
  vi.spyOn(devices, "listLocations").mockResolvedValue([]);
  const { result } = renderHook(() => useAlertConsoleData({}));
  await waitFor(() => expect(result.current.rules.data).toHaveLength(100));
  expect(result.current.rulePaging.hasMore).toBe(true);
  act(() => result.current.rulePaging.selectPage(1));
  expect(result.current.rules.data).toBeNull();
  await waitFor(() => expect(result.current.rules.data?.[0].id).toBe("1-0"));
  expect(rules).toHaveBeenLastCalledWith({}, 1);
  expect(result.current.rulePaging.hasMore).toBe(false);
  expect(channels).toHaveBeenCalledTimes(1);
});

it("finishes summary and inbox loading after StrictMode replays the initial read", async () => {
  vi.spyOn(api, "listAlertPage").mockResolvedValue({
    content: [],
    page: 0,
    size: 100,
    totalPages: 1,
    hasNext: false,
    totalElements: 19,
  });
  const summary = vi.spyOn(api, "getAlertSummary").mockResolvedValue({
    total: 19,
    byStatus: { OPEN: 19 },
    bySeverity: { CRITICAL: 1 },
    byDevice: {},
    generatedAt: "2026-10-10T22:00:00Z",
  });
  vi.spyOn(api, "listAlertRules").mockResolvedValue([]);
  vi.spyOn(api, "listNotificationChannels").mockResolvedValue([]);
  vi.spyOn(devices, "listDevices").mockResolvedValue([]);
  vi.spyOn(devices, "listLocations").mockResolvedValue([]);
  const { result } = renderHook(
    () => {
      const data = useAlertConsoleData({ limit: 100 });
      useEffect(() => {
        void data.load(false);
      }, []);
      return data;
    },
    { wrapper: StrictMode },
  );
  await waitFor(() => expect(result.current.summary.data?.byStatus.OPEN).toBe(19));
  expect(result.current.summary.refreshing).toBe(false);
  expect(result.current.alerts.refreshing).toBe(false);
  expect(result.current.pagination?.totalElements).toBe(19);
  expect(summary).toHaveBeenCalledTimes(2);
});
