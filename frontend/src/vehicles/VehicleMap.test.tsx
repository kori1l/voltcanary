import { render, screen, waitFor, cleanup } from "@testing-library/react";
import { afterEach, it, expect, vi } from "vitest";
import { VehicleMap } from "./VehicleMap";
import type { PositionEvidence } from "./positionApi";

vi.mock("maplibre-gl", () => ({
  setWorkerUrl: vi.fn(),
  Map: class {
    constructor() {
      throw new Error("WebGL unavailable");
    }
  },
}));
afterEach(cleanup);
const evidence: PositionEvidence = {
  latest: {
    deviceId: "car",
    sourceId: "gps",
    asOf: "2026-10-11T10:01:00Z",
    freshness: "STALE",
    position: {
      recordedAt: "2026-10-11T10:00:00Z",
      receivedAt: "2026-10-11T10:00:01Z",
      latitude: 44.8,
      longitude: 20.4,
      speedKmh: 0,
      headingDeg: 0,
      quality: ["OK"],
    },
  },
  history: {
    deviceId: "car",
    sourceId: "gps",
    from: "2026-10-11T09:45:00Z",
    to: "2026-10-11T10:01:00Z",
    points: [],
    returnedCount: 0,
    truncated: true,
  },
};
it("keeps textual GPS evidence and explicit stale/truncated states when WebGL fails", async () => {
  render(<VehicleMap evidence={evidence} />);
  await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("Map unavailable"));
  expect(screen.getByText("44.80000, 20.40000")).toBeInTheDocument();
  expect(screen.getByText("Текущая скорость недоступна")).toBeInTheDocument();
  expect(screen.queryByText(/0[.,]0 km\/h/)).not.toBeInTheDocument();
  expect(screen.getByText("Stale position")).toBeInTheDocument();
  expect(screen.getByText(/Latest 1000/)).toBeInTheDocument();
});
it("does not invent a position while waiting for its first sample", () => {
  render(<VehicleMap evidence={null} />);
  expect(screen.getByRole("status")).toHaveTextContent("Waiting for GPS");
  expect(screen.queryByLabelText("Follow vehicle")).not.toBeInTheDocument();
});
it("opens the existing map for zone editing without inventing a vehicle marker", async () => {
  render(
    <VehicleMap
      evidence={null}
      zoneEditing
      zones={[{ id: "zone", name: "Depot", latitude: 60, longitude: 24, radiusMetres: 200 }]}
    />,
  );
  expect(screen.getByText(/No GPS position/)).toBeVisible();
  expect(screen.queryByLabelText("Follow vehicle")).not.toBeInTheDocument();
  expect(screen.queryByText("Recorded position")).not.toBeInTheDocument();
  await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("Map unavailable"));
});
it("keeps dashboard journey details collapsed so the map retains room", () => {
  const recorded: PositionEvidence = {
    ...evidence,
    latest: {
      ...evidence.latest,
      position: {
        ...evidence.latest.position!,
        fields: {
          speedKmh: {
            value: 0,
            type: "NUMBER",
            label: "Recorded speed",
            unit: "km/h",
            display: true,
          },
        },
      },
    },
  };
  render(<VehicleMap compact evidence={recorded} />);
  expect(screen.getByText("Данные GPS и рейса")).toBeVisible();
  expect(screen.queryByText("Recorded speed")).not.toBeInTheDocument();
  expect(screen.getByRole("checkbox", { name: "Follow vehicle" })).toBeVisible();
  expect(screen.getByText("Stale position")).toBeVisible();
});

it("hides map metadata independently while retaining stale and failure warnings", async () => {
  render(<VehicleMap compact showDetails={false} evidence={evidence} />);
  expect(screen.queryByRole("checkbox", { name: "Follow vehicle" })).not.toBeInTheDocument();
  expect(screen.queryByText("Данные GPS и рейса")).not.toBeInTheDocument();
  expect(screen.queryByText("Stale position")).not.toBeInTheDocument();
  expect(screen.getByText(/Последние измерения устарели/)).toBeVisible();
  await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("Map unavailable"));
});

it("hides optional stale and truncation footers but keeps map failures visible", async () => {
  render(<VehicleMap compact showDetails={false} showNotices={false} evidence={evidence} />);
  expect(screen.queryByText(/Последние измерения устарели/)).not.toBeInTheDocument();
  expect(screen.queryByText(/Latest 1000/)).not.toBeInTheDocument();
  expect(screen.getByLabelText("Vehicle movement map")).toBeInTheDocument();
  await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("Map unavailable"));
  expect(screen.getByRole("button", { name: "Retry map" })).toBeVisible();
});
