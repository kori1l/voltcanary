import { describe, expect, it } from "vitest";
import { MlFeatureSnapshotResponse } from "../../../analytics/mlModuleApi";
import { trainingBlockReason } from "./useTrainingDraft";

const failureSnapshot: MlFeatureSnapshotResponse = {
  id: "snapshot-1",
  accountId: "account-1",
  datasetVersionId: "dataset-version-1",
  featureSetVersion: "feature-set-v1",
  codeConfigVersion: "config-v1",
  featureSchemaHash: "sha256:schema",
  featureDataHash: "sha256:data",
  featureConfigHash: "sha256:config",
  rowCount: 10,
  rejectedRowCount: 0,
  artifactUri: "db://features",
  immutableRowReference: null,
  modelFamily: "FAILURE_PREDICTION",
  horizonDays: 30,
  featureTemplateId: "generic-appliance",
  featureTemplateVersion: "template-v1",
  lifecycleStatus: "ACTIVE",
  createdAt: "2026-07-01T00:00:00Z",
};

describe("trainingBlockReason", () => {
  it("requires dataset, snapshot, family, and positive failure horizon before training", () => {
    expect(trainingBlockReason("", "", undefined, "ANOMALY_DETECTION", 14)).toMatch(/READY dataset/);
    expect(trainingBlockReason("dataset-version-1", "", undefined, "ANOMALY_DETECTION", 14)).toMatch(/feature snapshot/);
    expect(trainingBlockReason("dataset-version-1", "snapshot-1", failureSnapshot, "ANOMALY_DETECTION", 14)).toMatch(/must match training family/);
    expect(trainingBlockReason("dataset-version-1", "snapshot-1", failureSnapshot, "FAILURE_PREDICTION", 14)).toMatch(/must match training horizon/);
    expect(trainingBlockReason("dataset-version-1", "snapshot-1", failureSnapshot, "FAILURE_PREDICTION", 30)).toBeNull();
  });
});
