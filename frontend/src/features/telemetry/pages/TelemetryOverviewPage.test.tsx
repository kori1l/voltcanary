import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { mapTelemetryOverview } from "../model/telemetry.mappers";
import { TelemetryOverviewPage } from "./TelemetryOverviewPage";

afterEach(cleanup);
it.each(["change", "input"] as const)("applies a keyboard analysis interval through %s without broadening it or changing its seconds", (eventType) => {
  const from = "2026-10-06T10:00:01Z";
  const to = "2026-10-06T10:00:11Z";
  const onFrom = vi.fn();
  const onTo = vi.fn();
  const overview = mapTelemetryOverview({ deviceId: "device", charts: { activePowerW: { deviceId: "device", metric: "activePowerW", points: [{ timestamp: from, value: 0 }, { timestamp: to, value: 120 }] } }, latestTimestamp: to, selectedMetricKeys: ["activePowerW"], range: { from, to, preset: "custom" }, baseline: null, anomalies: [] });
  render(<TelemetryOverviewPage overview={overview} range="custom" customFrom={from} customTo={to} showPoints selectedMetricKeys={["activePowerW"]} metricSearch="" openedMetricKey="activePowerW" selectedEventId={null} maxDisplayPoints={180} onRangeChange={vi.fn()} onCustomFromChange={onFrom} onCustomToChange={onTo} onShowPointsChange={vi.fn()} onMetricSearchChange={vi.fn()} onToggleMetric={vi.fn()} onSelectAll={vi.fn()} onResetMetrics={vi.fn()} onClearMetrics={vi.fn()} onOpenMetric={vi.fn()} onCloseMetric={vi.fn()} onSelectEvent={vi.fn()} />);
  fireEvent[eventType](screen.getByLabelText("Analyze from"), { target: { value: "2026-10-06T12:01:02" } });
  fireEvent[eventType](screen.getByLabelText("Analyze to"), { target: { value: "2026-10-06T12:01:12" } });
  fireEvent.click(screen.getByRole("button", { name: "Apply analysis interval" }));
  expect(onFrom).toHaveBeenCalledWith("2026-10-06T12:01:02");
  expect(onTo).toHaveBeenCalledWith("2026-10-06T12:01:12");
});
