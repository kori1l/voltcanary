import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import schema from "../../../../docs/target/configurable-operations-language.schema.json";
import { ConfigurableRuleWorkbench } from "./ConfigurableRuleWorkbench";
import { compileConfigurableRule } from "./configurableRuleApi";

vi.mock("../../auth/authApi", () => ({ me: vi.fn().mockResolvedValue({ id: "actor" }) }));
vi.mock("../../devices/devicesApi", () => ({
  getCurrentDeviceAccount: vi.fn().mockResolvedValue({ id: "account" }),
}));
vi.mock("./configurableRuleApi", () => ({
  compileConfigurableRule: vi.fn().mockRejectedValue(new Error("Fixture status")),
}));

it("uses declared input ports and preserves an edge when its source is renamed", async () => {
  const language = {
    registry: { registryDigest: "registry", $defs: { Rule: schema.$defs.Rule } },
    nativeQuery: { profile: "native-live-sql-1" },
    ruleExecutionReady: false,
  };
  render(
    <ConfigurableRuleWorkbench
      language={language}
      activationOpen={false}
      onActivationChange={vi.fn()}
    />,
  );
  const add = screen.getByRole("button", { name: "Add node" });
  fireEvent.click(add);
  fireEvent.change(screen.getByLabelText("Node name"), { target: { value: "source" } });
  fireEvent.change(screen.getByLabelText("Method"), { target: { value: "read_stream" } });
  fireEvent.change(screen.getByLabelText("Binding Id"), { target: { value: "binding" } });
  fireEvent.change(screen.getByLabelText("Schema Revision"), { target: { value: "1" } });
  fireEvent.click(add);
  const second = within(screen.getByRole("group", { name: "Node 2" }));
  fireEvent.change(second.getByLabelText("Node name"), { target: { value: "duration" } });
  const duration = within(screen.getByRole("group", { name: "Node duration" }));
  fireEvent.change(duration.getByLabelText("Method"), { target: { value: "sustained" } });
  fireEvent.change(duration.getByLabelText("Input events"), { target: { value: "0" } });
  const predicate = { op: "FIELD", fieldId: "flag" };
  fireEvent.change(duration.getByLabelText("Node settings (JSON)"), {
    target: { value: JSON.stringify({ predicate }) },
  });
  fireEvent.change(duration.getByLabelText("Subject Field Id"), { target: { value: "subject" } });
  fireEvent.change(duration.getByLabelText("Minimum Duration Us"), {
    target: { value: "9007199254740993" },
  });
  fireEvent.change(duration.getByLabelText("Max Gap Us"), { target: { value: "2000000" } });
  fireEvent.change(
    within(screen.getByRole("group", { name: "Node source" })).getByLabelText("Node name"),
    { target: { value: "renamed" } },
  );
  expect(duration.getByLabelText("Input events")).toHaveValue("0");
  fireEvent.change(screen.getByLabelText("Rule output"), { target: { value: "1" } });
  const from = "2026-11-17T23:59:15Z";
  const boundary = "2026-11-18T00:00:00Z";
  const proofId = "39d7ec72-6fbf-41f6-957f-adc9ec104675";
  fireEvent.change(screen.getByLabelText("Coverage starts (UTC)"), { target: { value: from } });
  fireEvent.change(screen.getByLabelText("Activation boundary (UTC)"), {
    target: { value: boundary },
  });
  fireEvent.change(screen.getByLabelText("Coverage proof ID"), { target: { value: proofId } });
  fireEvent.click(screen.getByRole("button", { name: "Prepare rule" }));
  await waitFor(() =>
    expect(compileConfigurableRule).toHaveBeenCalledWith(
      expect.objectContaining({
        spec: expect.objectContaining({
          terminal: "duration",
          nodes: [
            {
              id: "renamed",
              method: "read_stream",
              inputs: {},
              config: { bindingId: "binding", schemaRevision: 1 },
            },
            {
              id: "duration",
              method: "sustained",
              inputs: { events: "renamed" },
              config: {
                predicate,
                subjectFieldId: "subject",
                minimumDurationUs: "9007199254740993",
                maxGapUs: "2000000",
              },
            },
          ],
        }),
      }),
      { account: "account", binding: "binding", schemaRevision: 1, from, boundary, proofId },
      expect.any(AbortSignal),
    ),
  );
});
