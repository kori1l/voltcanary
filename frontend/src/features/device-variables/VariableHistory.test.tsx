import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, it, vi } from "vitest";
import { VariableHistory } from "./VariableHistory";

const mocks = vi.hoisted(() => ({
  descriptors: vi.fn(),
  read: vi.fn(),
  latest: vi.fn(),
  outcomes: vi.fn(),
  request: vi.fn(),
}));
vi.mock("./variablesApi", () => ({
  variableDescriptors: mocks.descriptors,
  readVariable: mocks.read,
  latestVariable: mocks.latest,
  variableOutcomes: mocks.outcomes,
}));
vi.mock("../../api/client", () => ({ apiRequest: mocks.request }));
const descriptor = {
  variableId: "variable-one",
  dataSourceId: "source-one",
  sourceId: "sensor",
  key: "temperature",
  label: "Temperature",
  origin: "DERIVED",
  unit: "C",
  revision: 1,
  generation: 1,
  enabled: true,
  metricToken: "var_metric",
};
const binding = {
  variableId: descriptor.variableId,
  dataSourceId: descriptor.dataSourceId,
  revision: 1,
  version: 1,
  inputPaths: { x: "raw" },
  enabled: true,
  inherited: false,
};
const point = {
  timestamp: new Date(Date.now() - 120000).toISOString(),
  value: 0,
  minimum: 0,
  maximum: 0,
  count: 1,
  revision: 1,
  generation: 1,
};
beforeEach(() => {
  vi.clearAllMocks();
  mocks.descriptors.mockResolvedValue([descriptor]);
  mocks.read.mockResolvedValue({ points: [point], unit: "C", hasMore: false });
  mocks.latest.mockResolvedValue({ points: [point] });
  mocks.outcomes.mockResolvedValue([]);
  mocks.request.mockResolvedValue({ evaluationStatus: "EVALUATED", matchCount: 0 });
});
it("shows a valid zero and sends source/unit identity without a caller account header", async () => {
  render(
    <VariableHistory
      deviceId="device-one"
      sourceId="source-one"
      desiredGeneration={1}
      bindings={[binding]}
      refreshKey={0}
    />,
  );
  expect(await screen.findByText("0 C")).toBeInTheDocument();
  expect(screen.getByRole("img", { name: "Recorded variable values in C" })).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Test against raw history" }));
  expect(
    await screen.findByText(/Threshold test: EVALUATED; 0 matching observations/),
  ).toBeInTheDocument();
  const [path, options] = mocks.request.mock.calls[0];
  expect(path).toBe("/alert-rules/test");
  expect(options.headers).toBeUndefined();
  expect(JSON.parse(options.body).rule.config.variable).toMatchObject({
    variableId: "variable-one",
    dataSourceId: "source-one",
    unit: "C",
  });
});
it("keeps absent measurements distinct from zero", async () => {
  mocks.read.mockResolvedValue({ points: [], hasMore: false });
  mocks.latest.mockResolvedValue({ points: [] });
  render(
    <VariableHistory
      deviceId="device-one"
      sourceId="source-one"
      desiredGeneration={1}
      bindings={[binding]}
      refreshKey={0}
    />,
  );
  expect(await screen.findByText("No measurements in this period.")).toBeInTheDocument();
  expect(screen.getByText("No valid measurement")).toBeInTheDocument();
  expect(screen.queryByText("0 C")).not.toBeInTheDocument();
});
it("shows denied descriptor access and disables rule actions", async () => {
  mocks.descriptors.mockRejectedValue(new Error("Forbidden variable source"));
  render(
    <VariableHistory
      deviceId="device-two"
      sourceId="source-two"
      desiredGeneration={0}
      bindings={[]}
      refreshKey={0}
    />,
  );
  expect(await screen.findByText(/Forbidden variable source/)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Create threshold rule" })).toBeDisabled();
  expect(mocks.read).not.toHaveBeenCalled();
});
