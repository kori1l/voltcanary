import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { it, expect, vi } from "vitest";
import { VehicleConsole } from "./VehicleConsole";

vi.mock("../devices/devicesApi", () => ({
  listDevices: vi.fn().mockResolvedValue([
    {
      id: "tram",
      name: "HSL tram 0040-00412",
      type: "VEHICLE",
      metadata: { feedProvider: "HSL" },
    },
  ]),
  listDataSources: vi
    .fn()
    .mockResolvedValue([
      { id: "source", externalId: "hsl-tram-0040-00412", protocol: "REST", sourceType: "API" },
    ]),
}));
vi.mock("./monitoringApi", () => ({
  readMonitoring: vi.fn().mockResolvedValue({ speedLimitKmh: 80, idleMinutes: 2, zones: [] }),
  fleetPositions: vi.fn().mockResolvedValue([
    {
      deviceId: "tram",
      sourceId: "hsl-tram-0040-00412",
      asOf: new Date().toISOString(),
      freshness: "FRESH",
      position: {
        latitude: 60.18,
        longitude: 24.92,
        speedKmh: 36,
        recordedAt: new Date().toISOString(),
        ignition: null,
        fuelPercent: null,
        odometerKm: null,
      },
    },
  ]),
}));
vi.mock("./FleetMap", () => ({ FleetMap: () => null }));
vi.mock("./VehicleHistory", () => ({ VehicleHistory: () => null }));
vi.mock("./VehicleZones", () => ({ VehicleZones: () => null }));

it("identifies HSL measurements as real and offers the device source instead of emulator controls", async () => {
  const view = render(
    <MemoryRouter>
      <VehicleConsole />
    </MemoryRouter>,
  );
  const source = await screen.findByRole("link", { name: "Device & source" });
  expect(source).toHaveAttribute("href", "/devices/tram?tab=movement");
  expect(screen.queryByRole("link", { name: "Configure emulator" })).not.toBeInTheDocument();
  expect(screen.getByText(/Real GPS observations/)).toHaveTextContent("Source: HSL HFP open data");
  expect(screen.getByText("Fuel: No data")).toBeInTheDocument();
  view.unmount();
});
