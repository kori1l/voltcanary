import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, expect, it } from "vitest";
import { VehicleFieldWidget } from "./VehicleFieldWidget";
import type { PositionEvidence, PositionPoint } from "./positionApi";
import type { DashboardWidget } from "../dashboards/dashboardsApi";

afterEach(cleanup);
const point: PositionPoint = {
  recordedAt: "2026-10-23T10:00:00Z",
  receivedAt: "2026-10-23T10:00:01Z",
  latitude: 60,
  longitude: 24,
  speedKmh: 0,
  headingDeg: 0,
  quality: ["OK"],
  fields: {
    "transport.doorsOpen": {
      value: false,
      type: "BOOLEAN",
      label: "Двери",
      unit: "",
      display: true,
    },
    "transport.nextStopId": {
      value: "Central",
      type: "STRING",
      label: "Остановка",
      unit: "",
      display: true,
    },
  },
};
const evidence: PositionEvidence = {
  latest: {
    deviceId: "bus",
    sourceId: "hsl",
    asOf: point.recordedAt,
    freshness: "FRESH",
    position: point,
  },
  history: {
    deviceId: "bus",
    sourceId: "hsl",
    from: point.recordedAt,
    to: point.recordedAt,
    points: [point],
    returnedCount: 1,
    truncated: false,
  },
};
const widget = {
  id: "doors",
  type: "vehicle-value",
  title: "Двери",
  presentation: { vehicleField: "transport.doorsOpen" },
} as unknown as DashboardWidget;
it("renders boolean false as closed and zero speed as a measured value", () => {
  const { rerender } = render(<VehicleFieldWidget widget={widget} evidence={evidence} />);
  expect(screen.getByText("Закрыты")).toBeVisible();
  rerender(
    <VehicleFieldWidget
      widget={{ ...widget, presentation: { vehicleField: "speedKmh" } }}
      evidence={evidence}
    />,
  );
  expect(screen.getByText("0")).toBeVisible();
  expect(screen.getByText("km/h")).toBeVisible();
});
it("renders recorded text and keeps absent fields unavailable", () => {
  const { rerender } = render(
    <VehicleFieldWidget
      widget={{ ...widget, presentation: { vehicleField: "transport.nextStopId" } }}
      evidence={evidence}
    />,
  );
  expect(screen.getByText("Central")).toBeVisible();
  rerender(
    <VehicleFieldWidget
      widget={{ ...widget, presentation: { vehicleField: "unknown" } }}
      evidence={evidence}
    />,
  );
  expect(screen.getByRole("status")).toHaveTextContent("Текущее значение недоступно");
});
it("never presents a stale observation as current", () => {
  render(
    <VehicleFieldWidget
      widget={widget}
      evidence={{ ...evidence, latest: { ...evidence.latest, freshness: "STALE" } }}
    />,
  );
  expect(screen.getByRole("status")).toHaveTextContent("Текущее значение недоступно");
  expect(screen.getByText(/Устаревшие данные/)).toBeVisible();
});
it("lists observed door states in time order without filling missing observations", () => {
  const opened = {
    ...point,
    recordedAt: "2026-10-23T10:05:00Z",
    fields: {
      ...point.fields,
      "transport.doorsOpen": { ...point.fields!["transport.doorsOpen"], value: true },
    },
  };
  render(
    <VehicleFieldWidget
      widget={{ ...widget, type: "vehicle-timeline" }}
      evidence={{
        ...evidence,
        history: { ...evidence.history, points: [point, opened], truncated: true },
      }}
    />,
  );
  const rows = screen.getAllByRole("listitem");
  expect(rows).toHaveLength(2);
  expect(rows[0]).toHaveTextContent("Открыты");
  expect(rows[1]).toHaveTextContent("Закрыты");
  expect(screen.getByText(/Между измерениями состояние неизвестно/)).toBeVisible();
  expect(screen.getByRole("status")).toHaveTextContent("1000");
});

it("keeps compact stale, missing, false and zero values distinct without verbose metadata", () => {
  const { rerender } = render(
    <VehicleFieldWidget
      widget={{ ...widget, presentation: { ...widget.presentation, compact: true } }}
      evidence={{ ...evidence, latest: { ...evidence.latest, freshness: "STALE" } }}
    />,
  );
  expect(screen.getByText("Закрыты")).toBeVisible();
  expect(screen.getByRole("status")).toHaveTextContent("Устарело");
  expect(screen.getByRole("status").getAttribute("title")).toContain("Последнее наблюдение:");
  expect(screen.queryByText("Текущее значение недоступно.")).not.toBeInTheDocument();
  rerender(
    <VehicleFieldWidget
      widget={{ ...widget, presentation: { vehicleField: "speedKmh", compact: true } }}
      evidence={evidence}
    />,
  );
  expect(screen.getByText("0")).toBeVisible();
  expect(screen.queryByRole("status")).not.toBeInTheDocument();
  rerender(
    <VehicleFieldWidget
      widget={{ ...widget, presentation: { vehicleField: "missing", compact: true } }}
      evidence={evidence}
    />,
  );
  expect(screen.getByText("—")).toBeVisible();
  expect(screen.getByRole("status")).toHaveTextContent("Нет данных");
});
