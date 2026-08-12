import type {
  MlDatasetWorkbenchPreviewResponse,
  MlMetricSelectionMode,
  MlModelFamily,
  MlTrainingSplitStrategy,
} from "../../../analytics/mlModuleApi";

export type DatasetWorkbenchPreviewStatus = "idle" | "loading" | "ready" | "stale" | "error";
export type DatasetWorkbenchModelFamily = Extract<MlModelFamily, "ANOMALY_DETECTION" | "FAILURE_PREDICTION">;
export type DatasetWorkbenchQuickRange = "24h" | "7d" | "30d" | "custom";
export type DatasetPipelineStepStatus = "ready" | "review" | "blocked";

export interface DatasetPipelineStep {
  id: string;
  label: string;
  detail: string;
  status: DatasetPipelineStepStatus;
}

export interface DatasetWorkbenchDraft {
  name: string;
  description: string;
  modelFamily: DatasetWorkbenchModelFamily;
  horizonDays: number;
  deviceIds: string[];
  deviceQuery: string;
  deviceTypeFilter: string;
  deviceStatusFilter: string;
  metricSelectionMode: MlMetricSelectionMode;
  selectedMetrics: string[];
  focusedMetric: string;
  from: string;
  to: string;
  quickRange: DatasetWorkbenchQuickRange;
  selectedCandidateId: string | null;
  splitStrategy: MlTrainingSplitStrategy;
  featureConfigVersion: string;
  minSamplesPerMetric: number;
  maxSamplingGapSeconds: number;
}

export interface DatasetWorkbenchDefaults {
  modelFamily: DatasetWorkbenchModelFamily;
  horizonDays: number | null;
  datasetName: string;
}

export interface DatasetWorkbenchValidation {
  staleDeviceIds: string[];
  staleMetrics: string[];
  blockingReasons: string[];
  warningReasons: string[];
  createDisabled: boolean;
  createDisabledReason: string | null;
}

export interface DatasetWorkbenchPreviewState {
  preview: MlDatasetWorkbenchPreviewResponse | null;
  status: DatasetWorkbenchPreviewStatus;
  error: string | null;
}
