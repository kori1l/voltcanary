import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it, vi } from "vitest";
import { VariableRollout } from "./VariableRollout";

const mocks = vi.hoisted(() => ({ create: vi.fn(), list: vi.fn(), resume: vi.fn() }));
vi.mock("../../devices/devicesApi", () => ({
  listDevices: vi.fn().mockResolvedValue([
    { id: "one", name: "Fridge one" },
    { id: "two", name: "Fridge two" },
  ]),
  listDataSources: vi
    .fn()
    .mockImplementation((device: string) =>
      Promise.resolve([{ id: "source-" + device, externalId: "sensor-" + device }]),
    ),
}));
vi.mock("./variablesApi", () => ({
  createVariableRollout: mocks.create,
  variableRollouts: mocks.list,
  resumeVariableRollout: mocks.resume,
}));

it("changes only explicitly selected sources and exposes resumable partial failures", async () => {
  const user = userEvent.setup();
  mocks.list.mockResolvedValueOnce([]).mockResolvedValue([
    {
      id: "target",
      sourceId: "source-two",
      status: "FAILED",
      diagnostic: "Binding changed; reload",
    },
  ]);
  mocks.create.mockResolvedValue([]);
  mocks.resume.mockResolvedValue({ status: "FAILED" });
  render(
    <VariableRollout type="FRIDGE" variableId="variable" revision={2} inputPaths={{ x: "raw" }} />,
  );
  await user.click(screen.getByRole("button", { name: "Roll out to selected sources" }));
  await user.click(await screen.findByRole("checkbox", { name: "Fridge two · sensor-two" }));
  await user.click(screen.getByRole("button", { name: "Apply to 1 sources" }));
  expect(mocks.create).toHaveBeenCalledWith(
    "FRIDGE",
    "variable",
    2,
    [{ deviceId: "two", sourceId: "source-two" }],
    { x: "raw" },
  );
  expect(await screen.findByText(/FAILED · Binding changed/)).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Resume target" }));
  expect(mocks.resume).toHaveBeenCalledWith("FRIDGE", "variable", "target");
});
