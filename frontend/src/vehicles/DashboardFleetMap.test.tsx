import { render, screen, fireEvent } from "@testing-library/react";
import { it, expect, vi } from "vitest";
import { DashboardFleetMap } from "./DashboardFleetMap";
import type { LatestPosition } from "./positionApi";
vi.mock("./FleetMap", () => ({
  FleetMap: ({
    items,
    onSelect,
  }: {
    items: { deviceId: string; name: string }[];
    onSelect: (id: string) => void;
  }) => (
    <div>
      {items.map((item) => (
        <button key={item.deviceId} onClick={() => onSelect(item.deviceId)}>
          Marker {item.name}
        </button>
      ))}
    </div>
  ),
}));
it("passes every vehicle to one map and selects the actual recorded measurement", () => {
  const positions = ["a", "b", "c"].map((deviceId) => ({
    deviceId,
    sourceId: deviceId,
    freshness: "FRESH",
    asOf: "2026-10-11T19:00:00Z",
    position: {
      latitude: 60.18,
      longitude: 24.92,
      speedKmh: 36,
      headingDeg: 1,
      recordedAt: "2026-10-11T19:00:00Z",
      receivedAt: "",
      quality: [],
    },
  })) as LatestPosition[];
  render(
    <DashboardFleetMap
      positions={positions}
      devices={positions.map((item) => ({ id: item.deviceId, name: `Tram ${item.deviceId}` }))}
      attribution="HSL HFP open data"
    />,
  );
  expect(screen.getAllByRole("button", { name: /Marker Tram/ })).toHaveLength(3);
  expect(screen.queryByRole("table", { name: "Vehicle measurements" })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: /Vehicles 3/ }));
  expect(screen.getByRole("table", { name: "Vehicle measurements" })).toBeInTheDocument();
  expect(screen.getByText(/Source: HSL HFP open data/)).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Marker Tram b" }));
  expect(screen.getByText(/Tram b · 60.18000, 24.92000/)).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Tram c" }));
  expect(screen.getByRole("button", { name: "Tram c" })).toHaveAttribute("aria-pressed", "true");
  expect(screen.getByRole("button", { name: "Tram c" }).closest("tr")).toHaveClass("selected");
  fireEvent.click(screen.getByRole("button", { name: /Vehicles 3/ }));
  expect(screen.queryByRole("table", { name: "Vehicle measurements" })).not.toBeInTheDocument();
});
