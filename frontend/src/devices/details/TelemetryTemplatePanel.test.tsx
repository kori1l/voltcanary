import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { it, expect, vi } from "vitest";
import { TelemetryTemplatePanel } from "./TelemetryTemplatePanel";
import {
  listTelemetryTemplates,
  saveTelemetryTemplate,
  assignTelemetryTemplate,
} from "../telemetryTemplatesApi";
vi.mock("../devicesApi", () => ({ getDevice: vi.fn().mockResolvedValue({ type: "VEHICLE" }) }));
vi.mock("../telemetryTemplatesApi", () => ({
  listTelemetryTemplates: vi.fn(),
  saveTelemetryTemplate: vi.fn(),
  assignTelemetryTemplate: vi.fn(),
}));
it("saves shared selection, couples GPS coordinates and assigns the same template by type", async () => {
  vi.mocked(listTelemetryTemplates).mockResolvedValue([]);
  const onAssigned = vi.fn().mockResolvedValue(undefined);
  render(
    <TelemetryTemplatePanel
      sources={[
        {
          id: "source",
          deviceId: "device",
          externalId: "hsl",
          sourceType: "API",
          protocol: "REST",
        },
      ]}
      onAssigned={onAssigned}
    />,
  );
  fireEvent.click(screen.getByRole("button", { name: "Use HSL fields" }));
  fireEvent.click(screen.getByRole("checkbox", { name: "Store Latitude" }));
  expect(screen.getByRole("checkbox", { name: "Store Longitude" })).not.toBeChecked();
  fireEvent.click(screen.getByRole("checkbox", { name: "Store Latitude" }));
  fireEvent.click(screen.getByRole("checkbox", { name: "Store Acceleration" }));
  vi.mocked(saveTelemetryTemplate).mockImplementation(async (value) => ({
    ...value,
    id: "template",
    version: 1,
  }));
  fireEvent.click(screen.getByRole("button", { name: "Save template" }));
  await waitFor(() => expect(saveTelemetryTemplate).toHaveBeenCalled());
  expect(
    vi
      .mocked(saveTelemetryTemplate)
      .mock.calls[0][0].policy.fields.find((field) => field.path === "transport.accelerationMs2")
      ?.store,
  ).toBe(false);
  vi.mocked(assignTelemetryTemplate).mockResolvedValue({ assigned: 15 });
  await waitFor(() =>
    expect(screen.getByRole("button", { name: "Apply to all VEHICLE sources" })).toBeEnabled(),
  );
  fireEvent.click(screen.getByRole("button", { name: "Apply to all VEHICLE sources" }));
  await waitFor(() =>
    expect(assignTelemetryTemplate).toHaveBeenCalledWith("template", [], "VEHICLE"),
  );
});
