import type { ReactElement } from "react";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { VariableEditor } from "./VariableEditor";
import type { VariableDraft } from "./variablesApi";
import type { Device } from "../../devices/devicesApi";

const mocks = vi.hoisted(() => ({
  listVariables: vi.fn(),
  saveVariable: vi.fn(),
  publishVariable: vi.fn(),
  previewVariable: vi.fn(),
  bindingStatus: vi.fn(),
  variableDescriptors: vi.fn(),
  variableDetail: vi.fn(),
}));
vi.mock("./variablesApi", () => mocks);
vi.mock("../../telemetry/telemetryFieldsApi", () => ({
  getTelemetryFields: vi.fn().mockResolvedValue([]),
}));
const device: Device = {
  id: "device-one",
  name: "Fridge",
  type: "FRIDGE",
  status: "ACTIVE",
  locationId: null,
  manufacturer: null,
  model: null,
  metadata: {},
};
const source = {
  id: "source-one",
  deviceId: device.id,
  sourceType: "SENSOR",
  protocol: "REST",
  externalId: "sensor-one",
  mapping: {
    telemetryPolicy: {
      fields: [
        {
          path: "sensorTemperatureRaw",
          key: "sensorTemperatureRaw",
          label: "Raw temperature",
          unit: "C",
          type: "number",
          store: true,
        },
      ],
    },
  },
};

describe("device variable workflow", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.listVariables.mockResolvedValue([]);
    mocks.variableDescriptors.mockResolvedValue([]);
    mocks.bindingStatus.mockResolvedValue({ desiredGeneration: 0, bindings: [] });
    mocks.previewVariable.mockResolvedValue({
      value: 22.7,
      unit: "C",
      status: "OK",
      steps: [
        { operation: "MULTIPLY", value: 23.5, unit: "C" },
        { operation: "ADD", value: 22.7, unit: "C" },
      ],
    });
    mocks.saveVariable.mockImplementation((_type: string, draft: VariableDraft) =>
      Promise.resolve({
        definition: {
          id: "variable-one",
          version: 1,
          key: draft.key,
          label: draft.label,
          archived: false,
        },
        revisions: [
          {
            revision: 1,
            state: "DRAFT",
            digest: "one",
            contract: { ...draft, variableId: "variable-one", revision: 1, languageVersion: "v1" },
          },
        ],
      }),
    );
  });
  it("requires a fresh server preview before publishing and invalidates it after input changes", async () => {
    const user = userEvent.setup();
    await renderReady(<VariableEditor device={device} sources={[source]} />);
    await user.selectOptions(
      screen.getByLabelText("Retained numeric field"),
      "sensorTemperatureRaw",
    );
    await user.click(screen.getByRole("button", { name: "Save draft" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Preview calculation" })).toBeEnabled(),
    );
    expect(screen.getByRole("button", { name: "Publish revision" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Preview calculation" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Publish revision" })).toBeEnabled(),
    );
    expect(screen.getByText("22.7 C", { selector: "strong" })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Preview input"), { target: { value: "236" } });
    expect(screen.getByRole("button", { name: "Publish revision" })).toBeDisabled();
    expect(screen.getByText(/Preview is stale/)).toBeInTheDocument();
    expect(mocks.publishVariable).not.toHaveBeenCalled();
  });
  it("does not choose an arbitrary source when a device has multiple candidates", async () => {
    await renderReady(
      <VariableEditor
        device={device}
        sources={[source, { ...source, id: "source-two", externalId: "sensor-two" }]}
      />,
    );
    expect(screen.getByLabelText("Variable source")).toHaveValue("");
    expect(screen.getByRole("button", { name: "Save draft" })).toBeDisabled();
    expect(screen.getByText(/UNBOUND — choose a source/)).toBeInTheDocument();
  });
  it("ignores a preview response after its input context changed", async () => {
    const user = userEvent.setup();
    let finish!: (value: unknown) => void;
    mocks.previewVariable.mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    await renderReady(<VariableEditor device={device} sources={[source]} />);
    await user.selectOptions(
      screen.getByLabelText("Retained numeric field"),
      "sensorTemperatureRaw",
    );
    await user.click(screen.getByRole("button", { name: "Save draft" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Preview calculation" })).toBeEnabled(),
    );
    await user.click(screen.getByRole("button", { name: "Preview calculation" }));
    fireEvent.change(screen.getByLabelText("Preview input"), { target: { value: "999" } });
    await act(async () => {
      finish({ value: 22.7, unit: "C", status: "OK", steps: [] });
    });
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Preview calculation" })).toBeEnabled(),
    );
    expect(screen.getByRole("button", { name: "Publish revision" })).toBeDisabled();
  });
});

async function renderReady(element: ReactElement) {
  await act(async () => {
    render(element);
  });
}
