import { Dispatch, SetStateAction, useEffect, useState } from "react";
import { MlFeatureSnapshotResponse } from "../../../analytics/mlModuleApi";

export type TrainingDraft = {
  datasetVersionId: string;
  featureSnapshotId: string;
  modelFamily: "ANOMALY_DETECTION" | "FAILURE_PREDICTION";
  algorithm: string;
  failureHorizonDays: number;
  minimumPositiveLabels: number;
  minimumLabelCoverage: number;
};

export function useTrainingDraft(accountId: string) {
  return usePersistedDraft<TrainingDraft>(
    `voltcanary.ml.${accountId}.training.ANOMALY_DETECTION.training-run`,
    {
      datasetVersionId: "",
      featureSnapshotId: "",
      modelFamily: "ANOMALY_DETECTION",
      algorithm: "baseline-statistical-anomaly-v1",
      failureHorizonDays: 14,
      minimumPositiveLabels: 20,
      minimumLabelCoverage: 0.8,
    },
  );
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

function usePersistedDraft<T>(key: string, initialValue: T) {
  const initialDraft = readDraft<T>(key);
  const [restored, setRestored] = useState(Boolean(initialDraft));
  const [value, setValue] = useState<T>(() => {
    if (initialDraft) {
      return { ...initialValue, ...initialDraft };
    }
    return initialValue;
  });

  useEffect(() => {
    const stored = readDraft<T>(key);
    setValue(stored ? { ...initialValue, ...stored } : initialValue);
    setRestored(Boolean(stored));
  }, [key]);

  useEffect(() => {
    try {
      window.localStorage.setItem(key, JSON.stringify(value));
    } catch {
      // Draft persistence is best effort; server state remains authoritative.
    }
  }, [key, value]);

  function clearDraft() {
    try {
      window.localStorage.removeItem(key);
    } catch {
      // Ignore storage failures.
    }
    setValue(initialValue);
    setRestored(false);
  }

  return [value, setValue as Dispatch<SetStateAction<T>>, clearDraft, restored] as const;
}

function readDraft<T>(key: string): Partial<T> | null {
  try {
    const raw = window.localStorage.getItem(key);
    return raw ? (JSON.parse(raw) as Partial<T>) : null;
  } catch {
    return null;
  }
}
