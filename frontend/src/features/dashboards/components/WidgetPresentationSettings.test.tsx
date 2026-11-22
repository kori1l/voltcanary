import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import type {
  WidgetManifest,
  WidgetPresentationSetting,
} from "../../../dashboards/dashboardQueriesApi";
import { WidgetPresentationSettings } from "./WidgetPresentationSettings";
import { WidgetAppearanceSettings } from "./WidgetAppearanceSettings";

afterEach(cleanup);
const flag = (
  key: string,
  label: string,
  section: "GENERAL" | "APPEARANCE",
  fallback = false,
): WidgetPresentationSetting => ({
  key,
  label,
  section,
  group: section === "GENERAL" ? "Header" : "Map",
  type: "BOOLEAN",
  defaultValue: fallback,
  choices: [],
  maxLength: null,
});
const manifest = (type: string, settings: WidgetPresentationSetting[]): WidgetManifest => ({
  type,
  typeVersion: 1,
  title: type,
  resultType: "NOTE",
  supported: true,
  dependency: null,
  reducers: [],
  units: [],
  maxSeries: 0,
  maxDetailDevices: 0,
  maxRows: 50,
  timeBasis: "NONE",
  visualizations: ["line"],
  capabilities: {
    version: 1,
    dataKind: "DOMAIN_EVIDENCE",
    valueTypes: [],
    binding: { modes: [], maxDevices: 1, sourceRequired: false, metricRequired: false },
    visualizations: ["line"],
    settings,
  },
});

it.each(["vehicle-map", "time-series", "context-note", "future-widget"])(
  "offers the same common header setting for %s",
  (type) => {
    const change = vi.fn();
    render(
      <WidgetPresentationSettings
        manifest={{ type } as WidgetManifest}
        section="GENERAL"
        presentation={{ showTitle: true, legacyValue: "retained" }}
        onChange={change}
      />,
    );
    fireEvent.click(screen.getByRole("checkbox", { name: "Show panel title" }));
    expect(change).toHaveBeenCalledWith({ showTitle: false, legacyValue: "retained" });
    expect(
      screen.getByRole("checkbox", { name: "Show measurement details below the panel" }),
    ).toBeVisible();
  },
);

it("uses only the server's settings and visualization choices, including for a new type", () => {
  render(
    <WidgetAppearanceSettings
      widget={{
        id: "new",
        type: "future-widget",
        typeVersion: 1,
        title: "New",
        binding: { mode: "ALL" },
        query: {},
        presentation: {},
        navigation: {},
        payload: {},
      }}
      manifest={manifest("future-widget", [
        flag("showTitle", "Show panel title", "GENERAL"),
        flag("showMapNotices", "Map notices", "APPEARANCE", true),
      ])}
      onChange={() => {}}
    />,
  );
  expect(screen.getByRole("checkbox", { name: "Map notices" })).toBeChecked();
  expect(screen.queryByRole("checkbox", { name: "Show panel title" })).not.toBeInTheDocument();
  expect(screen.getByRole("option", { name: "Line" })).toBeInTheDocument();
  expect(screen.queryByRole("option", { name: "Pie" })).not.toBeInTheDocument();
  expect(screen.queryByText("Show calculation explanations")).not.toBeInTheDocument();
});
