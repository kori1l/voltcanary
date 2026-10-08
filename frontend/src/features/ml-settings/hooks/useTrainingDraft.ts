import { Dispatch, SetStateAction, useEffect, useState } from "react";
import { MlFeatureSnapshotResponse } from "../../../analytics/mlModuleApi";
import { decodeDraftFields, draftScope } from "../../../components/scopedDrafts";
import { useScopedDraftValue } from "../../../components/useScopedDraftValue";

export type TrainingDraft = {
  datasetVersionId: string;
  featureSnapshotId: string;
  modelFamily: "ANOMALY_DETECTION" | "FAILURE_PREDICTION";
  algorithm: string;
  failureHorizonDays: number;
  minimumPositiveLabels: number;
  minimumLabelCoverage: number;
};

export function useTrainingDraft(accountId: string, datasetVersionId = "new", selection?: { snapshotId: string; family: TrainingDraft["modelFamily"]; horizonDays: number | null }) {
  const initial: TrainingDraft = {
      datasetVersionId: datasetVersionId === "new" ? "" : datasetVersionId,
      featureSnapshotId: selection?.snapshotId ?? "",
      modelFamily: selection?.family ?? "ANOMALY_DETECTION",
      algorithm: "baseline-statistical-anomaly-v1",
      failureHorizonDays: selection?.horizonDays ?? 14,
      minimumPositiveLabels: 20,
      minimumLabelCoverage: 0.8,
    };
  const scope = draftScope(accountId, `training.${datasetVersionId || "new"}`, "new-run-v1");
  return useScopedDraftValue(scope, initial, (data) => {
    const decoded = decodeDraftFields(data, initial);
    if (decoded?.modelFamily && !["ANOMALY_DETECTION", "FAILURE_PREDICTION"].includes(decoded.modelFamily)) return null;
    if (datasetVersionId !== "new" && decoded?.datasetVersionId && decoded.datasetVersionId !== datasetVersionId) return null;
    return decoded;
  }, `voltcanary.ml.${accountId}.training.ANOMALY_DETECTION.training-run`);
}

export function trainingBlockReason(
  datasetVersionId: string,
  featureSnapshotId: string,
  selectedSnapshot: MlFeatureSnapshotResponse | undefined,
  modelFamily: "ANOMALY_DETECTION" | "FAILURE_PREDICTION",
  failureHorizonDays: number,
) {
  if (!datasetVersionId) {
    return "Select a READY dataset before queueing training.";
  }
  if (!featureSnapshotId) {
    return "Select or generate a feature snapshot before queueing training.";
  }
  if (!selectedSnapshot) {
    return "Selected feature snapshot is unavailable. Generate or select a current feature snapshot.";
  }
  if (selectedSnapshot.modelFamily && selectedSnapshot.modelFamily !== modelFamily) {
    return `Feature snapshot family ${selectedSnapshot.modelFamily} must match training family ${modelFamily}.`;
  }
  if (modelFamily === "FAILURE_PREDICTION" && (!failureHorizonDays || failureHorizonDays < 1)) {
    return "Failure prediction requires a positive horizon.";
  }
  if (modelFamily === "FAILURE_PREDICTION" && selectedSnapshot.horizonDays && selectedSnapshot.horizonDays !== failureHorizonDays) {
    return `Feature snapshot horizon ${selectedSnapshot.horizonDays} must match training horizon ${failureHorizonDays}.`;
  }
  return null;
}
