import type { DatasetPipelineStep } from "../../ml-datasets/model/datasetWorkbench.types";
import type { SectionResource } from "../../../components/useSectionResource";

export function applyPipelineEvidence(steps: DatasetPipelineStep[], sections: { id: string; resource: SectionResource<unknown> }[]): DatasetPipelineStep[] {
  return steps.map((step) => {
    const resource = sections.find((section) => section.id === step.id)?.resource;
    if (!resource || ["ready", "empty"].includes(resource.status)) return step;
    if (resource.status === "stale") return { ...step, status: "stale", detail: "Retained prerequisite evidence; refresh failed. Retry to verify readiness." };
    if (["idle", "waiting-dependency", "loading"].includes(resource.status)) return { ...step, status: "loading", detail: "Waiting to verify prerequisite evidence." };
    return { ...step, status: "unavailable", detail: "Unable to verify prerequisite. Retry its inventory; absence has not been established." };
  });
}
