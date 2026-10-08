import { beforeEach, describe, expect, it } from "vitest";
import {
  buildAvailableChartMetrics,
  buildChartMetrics,
  getChartTimeWindow,
  readDashboardChartPreferences,
  writeDashboardChartPreferences,
} from "./deviceTelemetryModel";

describe("deviceTelemetryModel", () => {
  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem("voltcanary.accessToken", `${btoa("{}")}\.${btoa(JSON.stringify({ sub: "account-1" }))}.fixture`);
  });

  it("falls back to safe chart preferences when storage is missing or malformed", () => {
    localStorage.setItem("voltcanary.dashboardCharts.device-1", "{bad json");

    const preferences = readDashboardChartPreferences("device-1");

    expect(preferences.selectedMetricKeys).toEqual(["activePowerW", "voltageV", "currentA", "energyWh"]);
    expect(preferences.chartDensity).toBe("balanced");
    expect(preferences.chartTimeRange).toBe("24h");
  });

  it("persists typed chart preferences", () => {
    writeDashboardChartPreferences("device-1", {
      selectedMetricKeys: ["voltageV"],
      showChartPoints: true,
      chartDensity: "detailed",
      chartTimeRange: "custom",
      customChartFrom: "2026-07-01T00:00",
      customChartTo: "2026-07-01T01:00",
      expandedChartMetricKey: "voltageV",
    });

    expect(readDashboardChartPreferences("device-1")).toMatchObject({
      selectedMetricKeys: ["voltageV"],
      showChartPoints: true,
      chartDensity: "detailed",
      chartTimeRange: "custom",
      expandedChartMetricKey: "voltageV",
    });
  });

  it("preserves exact custom intervals without silently widening them", () => {
    const window = getChartTimeWindow("custom", "2026-07-01T00:00:00", "2026-07-01T00:00:10");

    expect(window).not.toBeNull();
    expect(new Date(window!.to).getTime() - new Date(window!.from).getTime()).toBe(10_000);
  });

  it("rejects invalid and reversed custom ranges", () => {
    expect(getChartTimeWindow("custom", "invalid", "2026-07-01T00:00:10")).toBeNull();
    expect(getChartTimeWindow("custom", "2026-07-01T00:00:10", "2026-07-01T00:00:00")).toBeNull();
  });

  it("orders known and inferred telemetry metrics consistently", () => {
    expect(buildChartMetrics({ voltageV: 230, customTemperatureC: 7, activePowerW: 120 }).map((metric) => metric.key)).toEqual([
      "activePowerW",
      "voltageV",
      "customTemperatureC",
    ]);
    expect(buildAvailableChartMetrics({
      activePowerW: { deviceId: "device-1", metric: "activePowerW", points: [{ timestamp: "2026-07-01T00:00:00Z", value: 1 }] },
      voltageV: { deviceId: "device-1", metric: "voltageV", points: [] },
    }).map((metric) => metric.key)).toEqual(["activePowerW"]);
  });
});
