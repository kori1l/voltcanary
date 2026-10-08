import { describe, expect, it } from "vitest";
import { initialPipelineContext, selectedPipelineSteps, type PipelineContext, type PipelineEvidence } from "./pipelineContext";

const context: PipelineContext = { ...initialPipelineContext(), accountId: "account-A", datasetVersionId: "dataset-A", family: "FAILURE_PREDICTION", horizonDays: 7, snapshotId: "snapshot-A", runId: "run-A", modelId: "model-A", policyId: "policy-A" };
function evidence(): PipelineEvidence {
  return {
    datasets: [{ accountId: "account-A", datasetVersionId: "dataset-A", status: "READY" }] as PipelineEvidence["datasets"],
    snapshots: [{ id: "snapshot-A", accountId: "account-A", datasetVersionId: "dataset-A", modelFamily: "FAILURE_PREDICTION", horizonDays: 7, lifecycleStatus: "ACTIVE", rowCount: 100, artifactUri: "artifact:snapshot" }] as PipelineEvidence["snapshots"],
    runs: [{ id: "run-A", accountId: "account-A", datasetVersionId: "dataset-A", featureSnapshotId: "snapshot-A", modelFamily: "FAILURE_PREDICTION", status: "SUCCEEDED", completedAt: "2026-10-08T00:00:00Z", artifactUri: "artifact:run" }] as PipelineEvidence["runs"],
    models: [{ id: "model-A", accountId: "account-A", datasetVersionId: "dataset-A", featureSnapshotId: "snapshot-A", trainingRunId: "run-A", family: "FAILURE_PREDICTION", status: "APPROVED", approvedAt: "2026-10-08T00:00:00Z", artifactUri: "artifact:model", compatibleDeviceTypes: ["FRIDGE"] }] as PipelineEvidence["models"],
    policies: [{ id: "policy-A", accountId: "account-A", scope: "ACCOUNT", modelVersionId: "model-A", mode: "ACTIVE" }] as PipelineEvidence["policies"],
    labels: [{ id: "label-A", accountId: "account-A", datasetVersionId: "dataset-A", horizonDays: 7, label: "CONFIRMED_FAILURE" }] as PipelineEvidence["labels"],
    datasetDeviceIds: ["device-A"], prediction: null,
  };
}
const getStep = (items: ReturnType<typeof selectedPipelineSteps>, id: string) => items.find((item) => item.id === id)!;
describe("selected pipeline lineage", () => {
  it("keeps verified approval and configuration distinct from observed inference", () => {
    const steps = selectedPipelineSteps(context, evidence());
    expect(getStep(steps, "model").status).toBe("ready");
    expect(getStep(steps, "policy").status).toBe("ready");
    expect(getStep(steps, "monitoring").status).toBe("review");
  });
  it.each(["QUEUED", "RUNNING"] as const)("reports %s as progress without implying model completion", (status) => {
    const data = evidence(); data.runs[0].status = status;
    const steps = selectedPipelineSteps(context, data);
    expect(getStep(steps, "training").status).toBe(status.toLowerCase());
    expect(getStep(steps, "model").status).not.toBe("ready");
  });
  it.each(["datasetVersionId", "accountId", "modelFamily"] as const)("rejects a foreign snapshot %s", (field) => {
    const data = evidence(); data.snapshots[0][field] = "foreign";
    const steps = selectedPipelineSteps(context, data);
    for (const id of ["snapshot", "training", "model", "policy"]) expect(getStep(steps, id).status).not.toBe("ready");
  });
  it("explains seven versus fourteen day mismatch", () => {
    const data = evidence(); data.snapshots[0].horizonDays = 14;
    expect(getStep(selectedPipelineSteps(context, data), "snapshot")).toMatchObject({ status: "review", detail: expect.stringContaining("14 days; selected 7 days") });
  });
  it("keeps legacy missing model/run metadata unverifiable and disables a policy separately", () => {
    const data = evidence(); data.models[0].trainingRunId = null;
    expect(getStep(selectedPipelineSteps(context, data), "model").status).toBe("review");
    data.models[0].trainingRunId = "run-A"; data.policies[0].mode = "DISABLED";
    const steps = selectedPipelineSteps(context, data);
    expect(getStep(steps, "model").status).toBe("ready");
    expect(getStep(steps, "policy").status).toBe("blocked");
  });
  it("uses only selected dataset/horizon labels and requires known device lineage", () => {
    const data = evidence(); data.labels![0].datasetVersionId = "dataset-B";
    expect(getStep(selectedPipelineSteps(context, data), "labels").status).toBe("blocked");
    const scoped = { ...context, deviceId: "device-B", deviceType: "FRIDGE" };
    expect(getStep(selectedPipelineSteps(scoped, data), "dataset").status).toBe("review");
    data.labels = null;
    expect(getStep(selectedPipelineSteps(context, data), "labels").status).toBe("unavailable");
  });
  it("does not require failure horizon or labels for anomaly detection", () => {
    const anomaly = { ...context, family: "ANOMALY_DETECTION" as const, horizonDays: null };
    const data = evidence(); data.labels = null; data.snapshots[0].modelFamily = "ANOMALY_DETECTION";
    expect(getStep(selectedPipelineSteps(anomaly, data), "labels").status).toBe("ready");
    expect(getStep(selectedPipelineSteps(anomaly, data), "snapshot").status).toBe("ready");
  });
  it("recognizes a recorded MODEL prediction only for its selected device, model, family and horizon", () => {
    const selected = { ...context, deviceId: "device-A", deviceType: "FRIDGE" };
    const data = evidence();
    data.prediction = { deviceId: "device-A", modelVersionId: "model-A", modelFamily: "FAILURE_PREDICTION", failureHorizonDays: 7, predictionStatus: "MODEL", createdAt: "2026-10-08T00:00:00Z" } as PipelineEvidence["prediction"];
    expect(getStep(selectedPipelineSteps(selected, data), "monitoring").status).toBe("ready");
    data.prediction!.failureHorizonDays = 14;
    expect(getStep(selectedPipelineSteps(selected, data), "monitoring").status).toBe("review");
  });
});
