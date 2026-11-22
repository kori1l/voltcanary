import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { MlModelsTab } from "./MlModelsTab";
import type { MlModelVersion } from "../../../analytics/mlModuleApi";
afterEach(cleanup);
const models = [
  {
    id: "selected",
    modelKey: "Chosen model",
    version: 2,
    status: "ACTIVE",
    family: "ANOMALY_DETECTION",
    algorithm: "ISOLATION_FOREST",
  },
  {
    id: "other",
    modelKey: "Other model",
    version: 1,
    status: "ACTIVE",
    family: "ANOMALY_DETECTION",
    algorithm: "ISOLATION_FOREST",
  },
] as MlModelVersion[];
const props = {
  models,
  modelsAvailable: true,
  statusFilter: "default" as const,
  onChanged: vi.fn(),
  onDelete: vi.fn(),
  onFilterChange: vi.fn(),
  setError: vi.fn(),
};
it("opens exactly the selected authorized model card and permits returning to the registry", () => {
  const clear = vi.fn();
  render(<MlModelsTab {...props} selectedModelId="selected" onClearSelection={clear} />);
  expect(screen.getByText("Chosen model v2")).toBeVisible();
  expect(screen.queryByText("Other model v1")).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Show all models" }));
  expect(clear).toHaveBeenCalledOnce();
});
it("does not substitute another model for an unavailable selection", () => {
  render(<MlModelsTab {...props} selectedModelId="denied" />);
  expect(
    screen.getByText("Selected model unavailable or outside the current status filter."),
  ).toBeVisible();
  expect(screen.queryByText("Other model v1")).not.toBeInTheDocument();
});
