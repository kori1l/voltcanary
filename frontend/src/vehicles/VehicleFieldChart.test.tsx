import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, expect, it } from "vitest";
import { recordedFieldPoints, VehicleFieldChart } from "./VehicleFieldChart";

afterEach(cleanup);
it("breaks missing samples and long gaps without treating false or zero as missing", () => {
  const points = recordedFieldPoints([
    { at: "2026-10-23T10:00:00Z", value: false },
    { at: "2026-10-23T10:00:05Z", value: true },
    { at: "2026-10-23T10:00:10Z", value: null },
    { at: "2026-10-23T10:00:15Z", value: 0 },
    { at: "2026-10-23T10:01:15Z", value: 1 },
  ]);
  expect(points.map((point) => [point.value, point.gap])).toEqual([
    [0, true],
    [1, false],
    [0, true],
    [1, true],
  ]);
});
it("renders door observations as a graph with named states and recorded timestamps", () => {
  render(
    <VehicleFieldChart
      title="Двери"
      from="2026-10-23T10:00:00Z"
      to="2026-10-23T10:01:00Z"
      samples={[
        { at: "2026-10-23T10:00:00Z", value: false },
        { at: "2026-10-23T10:00:05Z", value: true },
      ]}
      falseLabel="Закрыты"
      trueLabel="Открыты"
      truncated
    />,
  );
  expect(screen.getByRole("img", { name: "Двери: записанные состояния" })).toBeInTheDocument();
  expect(screen.getByText("Закрыты")).toBeVisible();
  expect(screen.getByText("Открыты")).toBeVisible();
  expect(screen.getByText(/Показаны последние 1000/)).toBeVisible();
  expect(document.querySelectorAll("circle title")).toHaveLength(2);
});
