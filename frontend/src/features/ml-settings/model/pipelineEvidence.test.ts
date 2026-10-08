import { expect, it } from "vitest";
import { initialResource } from "../../../components/useSectionResource";
import { applyPipelineEvidence } from "./pipelineEvidence";

it("only advises creating a dataset after an authoritative empty inventory", () => {
  const steps = [{ id: "dataset", label: "Dataset", status: "blocked" as const, detail: "Create a previewed dataset" }];
  for (const status of ["idle", "loading", "error", "no-access", "stale"] as const) {
    const [step] = applyPipelineEvidence(steps, [{ id: "dataset", resource: initialResource(status) }]);
    expect(step.detail).not.toContain("Create");
    expect(step.status).not.toBe("blocked");
  }
  expect(applyPipelineEvidence(steps, [{ id: "dataset", resource: initialResource("empty") }])[0].detail).toContain("Create");
});
