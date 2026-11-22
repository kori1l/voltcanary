import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, expect, it, vi } from "vitest";
import { DashboardDefinitionPreview } from "./DashboardDefinitionPreview";
import { blankDashboard } from "../model/dashboardLayout";
import type { DashboardDefinition } from "../../../dashboards/dashboardsApi";

vi.mock("../../../vehicles/VehicleMap", () => ({
  VehicleMap: ({ evidence }: { evidence: unknown }) => (
    <div role="region" aria-label="Vehicle movement map">
      {evidence ? "Recorded GPS map" : "Waiting for GPS"}
    </div>
  ),
}));

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});
const definition: DashboardDefinition = {
  ...blankDashboard("Reviewed definition"),
  widgets: [
    {
      id: "temperature",
      type: "latest-value",
      typeVersion: 1,
      title: "Температура",
      binding: { mode: "FIXED", deviceIds: ["device"] },
      query: { metric: "temperatureC", unit: "C" },
      presentation: { showTitle: true },
      navigation: {},
      payload: {},
    },
  ],
  layouts: {
    desktop: [{ widgetId: "temperature", x: 6, y: 0, w: 6, h: 3 }],
    tablet: [{ widgetId: "temperature", x: 0, y: 0, w: 6, h: 3 }],
    mobile: [{ widgetId: "temperature", x: 0, y: 0, w: 1, h: 3 }],
  },
};
it("queries the exact reviewed definition and uses ordinary canvas placement and no-data rendering", async () => {
  const fetch = vi.spyOn(globalThis, "fetch").mockImplementation(
    async (input) =>
      new Response(
        JSON.stringify(
          String(input).endsWith("widget-types")
            ? []
            : {
                asOf: "2026-10-22T12:32:00Z",
                panels: [
                  {
                    widgetId: "temperature",
                    status: "no_data",
                    data: null,
                    provenance: {},
                    evaluatedCount: 1,
                    requestedCount: 1,
                  },
                ],
              },
        ),
        { status: 200 },
      ),
  );
  render(
    <MemoryRouter>
      <DashboardDefinitionPreview definition={definition} />
    </MemoryRouter>,
  );
  expect(await screen.findByText("No data")).toBeInTheDocument();
  expect(screen.getByRole("article", { name: "Температура" })).toHaveStyle({
    gridColumn: "7 / span 6",
    gridRow: "1 / span 3",
  });
  const request = fetch.mock.calls.find(([url]) =>
    String(url).endsWith("/dashboards/preview/query"),
  )!;
  expect(JSON.parse(request[1]!.body as string)).toEqual({ definition });
  expect(fetch.mock.calls.some(([url]) => String(url).endsWith("/dashboards"))).toBe(false);
});
it("never displays a late response from an obsolete definition", async () => {
  let finish: (value: Response) => void = () => {};
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, options) => {
    if (String(input).endsWith("widget-types")) return new Response("[]", { status: 200 });
    const body = JSON.parse(options?.body as string);
    if (body.definition.name === "Reviewed definition")
      return new Promise((resolve) => {
        finish = resolve;
      });
    return new Response(
      JSON.stringify({
        asOf: "2026-10-22T12:32:00Z",
        panels: [
          {
            widgetId: "temperature",
            status: "no_data",
            data: null,
            provenance: {},
            evaluatedCount: 1,
            requestedCount: 1,
          },
        ],
      }),
      { status: 200 },
    );
  });
  const view = render(
    <MemoryRouter>
      <DashboardDefinitionPreview definition={definition} />
    </MemoryRouter>,
  );
  view.rerender(
    <MemoryRouter>
      <DashboardDefinitionPreview definition={{ ...definition, name: "Replacement" }} />
    </MemoryRouter>,
  );
  await screen.findByText("No data");
  finish(
    new Response(
      JSON.stringify({
        asOf: "2026-10-22T12:32:00Z",
        panels: [
          {
            widgetId: "temperature",
            status: "ready",
            data: { value: { value: 9999, unit: "C" } },
            provenance: {},
          },
        ],
      }),
      { status: 200 },
    ),
  );
  await waitFor(() => expect(screen.queryByText(/9999/)).not.toBeInTheDocument());
});

it("renders the genuine vehicle map beside a chart from one reviewed composition", async () => {
  const composed: DashboardDefinition = {
    ...blankDashboard("Автобус"),
    widgets: [
      {
        id: "map",
        type: "vehicle-map",
        typeVersion: 1,
        title: "Карта движения",
        binding: { mode: "FIXED", deviceIds: ["bus"] },
        query: { sourceId: "hsl-bus" },
        presentation: { showTitle: true },
        navigation: {},
        payload: {},
      },
      {
        id: "speed",
        type: "time-series",
        typeVersion: 1,
        title: "Скорость",
        binding: { mode: "FIXED", deviceIds: ["bus"] },
        query: {
          metric: "speedKmh",
          unit: "km/h",
          series: [{ deviceId: "bus", metric: "speedKmh" }],
        },
        presentation: { showTitle: true },
        navigation: {},
        payload: {},
      },
    ],
    layouts: {
      desktop: [
        { widgetId: "map", x: 0, y: 0, w: 6, h: 6 },
        { widgetId: "speed", x: 6, y: 0, w: 6, h: 6 },
      ],
      tablet: [],
      mobile: [],
    },
  };
  vi.spyOn(globalThis, "fetch").mockImplementation(
    async (input) =>
      new Response(
        JSON.stringify(
          String(input).endsWith("widget-types")
            ? []
            : {
                asOf: "2026-10-22T12:32:00Z",
                panels: [
                  {
                    widgetId: "map",
                    status: "ready",
                    data: {
                      positions: {
                        latest: {
                          deviceId: "bus",
                          position: { latitude: 60.17, longitude: 24.94 },
                        },
                        history: { points: [] },
                      },
                    },
                    provenance: {},
                    evaluatedCount: 1,
                    requestedCount: 1,
                  },
                  {
                    widgetId: "speed",
                    status: "no_data",
                    data: null,
                    provenance: {},
                    evaluatedCount: 1,
                    requestedCount: 1,
                  },
                ],
              },
        ),
        { status: 200 },
      ),
  );
  render(
    <MemoryRouter>
      <DashboardDefinitionPreview definition={composed} />
    </MemoryRouter>,
  );
  expect(await screen.findByRole("region", { name: "Vehicle movement map" })).toHaveTextContent(
    "Recorded GPS map",
  );
  expect(screen.getByRole("article", { name: "Карта движения" })).toHaveStyle({
    gridColumn: "1 / span 6",
  });
  expect(screen.getByRole("article", { name: "Скорость" })).toHaveStyle({
    gridColumn: "7 / span 6",
  });
  expect(screen.getByText("No data")).toBeInTheDocument();
});
