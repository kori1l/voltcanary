import { render, screen } from "@testing-library/react";
import { describe, it, expect } from "vitest";
import { PositionFieldDetails } from "./PositionFieldDetails";
import type { PositionPoint } from "./positionApi";
const point: PositionPoint = {
  recordedAt: "2026-10-12T00:00:00Z",
  receivedAt: "2026-10-12T00:00:00Z",
  latitude: 60,
  longitude: 25,
  speedKmh: 42,
  headingDeg: 0,
  quality: [],
  fields: {
    doors: { value: false, type: "boolean", label: "Doors", unit: "", display: true },
    count: { value: 0, type: "number", label: "Count", unit: "", display: false },
  },
};
describe("map field presentation", () => {
  it("uses declared display defaults instead of exposing extra coordinate/context fields", () => {
    render(<PositionFieldDetails point={point} />);
    expect(screen.getByText("Doors")).toBeInTheDocument();
    expect(screen.queryByText("latitude")).not.toBeInTheDocument();
    expect(screen.queryByText("Count")).not.toBeInTheDocument();
  });
  it("preserves false/zero and only shows the widget selection", () => {
    const view = render(<PositionFieldDetails point={point} visibleFields={["doors", "count"]} />);
    expect(screen.getByText("False")).toBeInTheDocument();
    expect(screen.getByText("0")).toBeInTheDocument();
    expect(screen.queryByText("speedKmh")).not.toBeInTheDocument();
    view.rerender(<PositionFieldDetails point={point} visibleFields={[]} />);
    expect(screen.queryByRole("definition")).not.toBeInTheDocument();
    expect(screen.queryByText("Doors")).not.toBeInTheDocument();
  });
});
