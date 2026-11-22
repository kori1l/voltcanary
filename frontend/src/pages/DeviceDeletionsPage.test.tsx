import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, expect, it, vi } from "vitest";
import { DeviceDeletionsPage } from "./DeviceDeletionsPage";
import { listDeviceDeletions } from "../devices/devicesApi";
import { getTelemetryMetrics, getTelemetryReadings } from "../telemetry/telemetryApi";
vi.mock("../devices/devicesApi", () => ({ listDeviceDeletions: vi.fn() }));
vi.mock("../telemetry/telemetryApi", () => ({
  getTelemetryMetrics: vi.fn(),
  getTelemetryReadings: vi.fn(),
}));
beforeEach(() => vi.resetAllMocks());
it("shows pending purge separately and offers read-only retained telemetry", async () => {
  vi.mocked(listDeviceDeletions).mockResolvedValue([
    {
      deviceId: "retained",
      name: "Old bus",
      deviceType: "VEHICLE",
      deleteHistory: false,
      status: "COMPLETED",
      completedSteps: "telemetry",
      lastError: null,
      createdAt: "2026-10-25T12:00:00Z",
    },
    {
      deviceId: "purged",
      name: "Purge bus",
      deviceType: "VEHICLE",
      deleteHistory: true,
      status: "PENDING",
      completedSteps: "",
      lastError: "Cleanup pending: telemetry",
      createdAt: "2026-10-25T12:00:00Z",
    },
  ]);
  vi.mocked(getTelemetryMetrics).mockResolvedValue([
    { name: "speedKmh", displayName: "Speed", unit: "km/h", type: "number", min: 0, max: 100 },
  ]);
  vi.mocked(getTelemetryReadings).mockResolvedValue({
    deviceId: "retained",
    metric: "speedKmh",
    points: [{ timestamp: "2026-10-25T12:00:00Z", value: 37 }],
  });
  render(
    <MemoryRouter>
      <DeviceDeletionsPage />
    </MemoryRouter>,
  );
  expect(await screen.findByText("Очистка выполняется")).toBeInTheDocument();
  expect(screen.getAllByRole("button", { name: "Посмотреть историю" })).toHaveLength(1);
  fireEvent.click(screen.getByRole("button", { name: "Посмотреть историю" }));
  expect(await screen.findByText("37")).toBeInTheDocument();
  expect(getTelemetryReadings).toHaveBeenCalledWith(
    "retained",
    "speedKmh",
    expect.any(String),
    expect.any(String),
    expect.any(AbortSignal),
  );
});
it("reports errors without pretending that no deleted devices exist", async () => {
  vi.mocked(listDeviceDeletions).mockRejectedValue(new Error("Unavailable"));
  render(
    <MemoryRouter>
      <DeviceDeletionsPage />
    </MemoryRouter>,
  );
  expect(await screen.findByRole("alert")).toHaveTextContent("Unavailable");
  expect(screen.queryByText("Удалённых устройств пока нет.")).not.toBeInTheDocument();
});
