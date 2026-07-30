import { apiRequest } from "../api/client";

export type MlDatasetStatus = "DRAFT" | "VALIDATING" | "READY" | "FAILED" | "ARCHIVED";
export type MlDatasetSourceType = "INTERNAL_TELEMETRY" | "EXTERNAL_IMPORT" | "MIXED";
export type MlTrainingSplitStrategy = "TIME_BASED" | "DEVICE_BASED" | "RANDOM";
export type MlTrainingRunStatus = "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELED";
export type MlModelStatus = "CANDIDATE" | "APPROVED" | "ACTIVE" | "REJECTED" | "ARCHIVED";
export type MlDeploymentMode = "SHADOW" | "CANARY" | "ACTIVE" | "DISABLED";
export type MlDeploymentScope = "SYSTEM" | "ACCOUNT" | "DEVICE_TYPE" | "DEVICE";
export type MlLabelType = "NORMAL" | "ANOMALY" | "DEGRADATION" | "MAINTENANCE" | "UNKNOWN";
export type MlLabelSource = "MANUAL" | "ALERT_OUTCOME" | "MAINTENANCE_OUTCOME" | "IMPORTED";

export interface MlDatasetSummary {
  datasetId: string;
  datasetVersionId: string;
  name: string;
  description: string | null;
  sourceType: MlDatasetSourceType;
  version: number;
  status: MlDatasetStatus;
  splitStrategy: MlTrainingSplitStrategy;
  featureConfigVersion: string;
  sourceWindowStart: string | null;
  sourceWindowEnd: string | null;
  createdAt: string;
  readyAt: string | null;
  failureReason: string | null;
}

export interface MlValidationReport {
  id: string;
  datasetVersionId: string | null;
  importedFileId: string | null;
  acceptedRows: number;
  rejectedRows: number;
  warningCount: number;
  rejectedReasons: Record<string, number>;
  mappingStats: Record<string, number>;
  summary: string;
  createdAt: string;
}

export interface MlLineage {
  id: string;
  datasetVersionId: string;
  sourceType: MlDatasetSourceType;
  sourceReference: string;
  filterJson: string;
  selectedDevicesJson: string;
  selectedMetricsJson: string;
  sourceWindowStart: string | null;
  sourceWindowEnd: string | null;
  contentHash: string;
}

export interface MlDatasetDetail {
  dataset: MlDatasetSummary;
  validationReports: MlValidationReport[];
  lineage: MlLineage[];
}

export interface MlExternalImportResponse {
  datasetId: string;
  importedFileId: string;
  validationReportId: string;
  contentHash: string;
  acceptedRows: number;
  rejectedRows: number;
  warningCount: number;
  validationMessages: string[];
  importedAt: string;
}

export interface MlFeatureSnapshotResponse {
  id: string;
  accountId: string;
  datasetVersionId: string;
  featureSetVersion: string;
  codeConfigVersion: string;
  featureSchemaHash: string;
  rowCount: number;
  rejectedRowCount: number;
  artifactUri: string;
  createdAt: string;
}

export interface MlTrainingRun {
  id: string;
  accountId: string;
  datasetVersionId: string;
  featureSnapshotId: string | null;
  modelFamily: string;
  algorithm: string;
  status: MlTrainingRunStatus;
  evaluationMetricsJson: string | null;
  baselineMetricsJson: string | null;
  artifactUri: string | null;
  logSummary: string | null;
  failureReason: string | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
}

export interface MlModelVersion {
  id: string;
  accountId: string;
  modelKey: string;
  version: number;
  family: string;
  algorithm: string;
  status: MlModelStatus;
  trainingRunId: string | null;
  datasetVersionId: string | null;
  featureSnapshotId: string | null;
  featureSchemaHash: string | null;
  artifactUri: string | null;
  artifactHash: string | null;
  compatibleDeviceTypes: string[];
  supportedMetrics: string[];
  evaluationMetrics: Record<string, unknown>;
  approvedBy: string | null;
  approvedAt: string | null;
  createdAt: string;
}

export interface MlDeploymentPolicy {
  id: string;
  accountId: string | null;
  scope: MlDeploymentScope;
  deviceId: string | null;
  deviceType: string | null;
  modelVersionId: string | null;
  mode: MlDeploymentMode;
  canaryPercent: number | null;
  fallbackEnabled: boolean;
  updatedBy: string | null;
  updatedAt: string;
}

export interface MlResolvedPolicy {
  policySource: MlDeploymentScope | null;
  policyId: string | null;
  modelVersionId: string | null;
  mode: MlDeploymentMode;
  fallbackEnabled: boolean;
  fallbackReason: string | null;
}

export interface MlLabel {
  id: string;
  accountId: string;
  datasetVersionId: string;
  deviceId: string | null;
  externalDeviceKey: string | null;
  metricName: string | null;
  labelStartAt: string;
  labelEndAt: string;
  label: MlLabelType;
  source: MlLabelSource;
  createdBy: string | null;
  createdAt: string;
  reason: string | null;
}

export interface MlLabelAudit {
  id: string;
  labelId: string;
  previousLabel: MlLabelType | null;
  newLabel: MlLabelType;
  editedBy: string | null;
  editedAt: string;
  reason: string | null;
}

export function listMlDatasets() {
  return apiRequest<MlDatasetSummary[]>("/analytics/ml/datasets");
}

export function getMlDatasetVersion(datasetVersionId: string) {
  return apiRequest<MlDatasetDetail>(`/analytics/ml/datasets/versions/${datasetVersionId}`);
}

export function importMlDataset(kind: "csv" | "jsonl", file: File, mapping: Record<string, unknown>, datasetName: string, description: string) {
  const form = new FormData();
  form.set("file", file);
  form.set("mappingJson", JSON.stringify(mapping));
  if (datasetName.trim()) {
    form.set("datasetName", datasetName.trim());
  }
  if (description.trim()) {
    form.set("description", description.trim());
  }
  return apiRequest<MlExternalImportResponse>(`/analytics/ml/datasets/external-${kind}`, {
    method: "POST",
    body: form,
  });
}

export function generateFeatureSnapshot(body: {
  datasetVersionId: string;
  featureSetVersion: string;
  codeConfigVersion: string;
  rollingWindowSize: number;
  missingGapSeconds: number;
  enabledFeatureFamilies: string[];
}) {
  return apiRequest<MlFeatureSnapshotResponse>("/analytics/ml/datasets/feature-snapshots", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export function listTrainingRuns() {
  return apiRequest<MlTrainingRun[]>("/analytics/ml/training-runs");
}

export function createTrainingRun(body: {
  datasetVersionId: string;
  featureSnapshotId?: string | null;
  modelFamily: string;
  algorithm: string;
  hyperparametersJson: string;
}) {
  return apiRequest<MlTrainingRun>("/analytics/ml/training-runs", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export function cancelTrainingRun(runId: string) {
  return apiRequest<MlTrainingRun>(`/analytics/ml/training-runs/${runId}/cancel`, { method: "POST" });
}

export function retryTrainingRun(runId: string) {
  return apiRequest<MlTrainingRun>(`/analytics/ml/training-runs/${runId}/retry`, { method: "POST" });
}

export function listMlModels() {
  return apiRequest<MlModelVersion[]>("/analytics/ml/models");
}

export function approveMlModel(modelVersionId: string, reason: string) {
  return modelAction(modelVersionId, "approve", reason);
}

export function rejectMlModel(modelVersionId: string, reason: string) {
  return modelAction(modelVersionId, "reject", reason);
}

export function archiveMlModel(modelVersionId: string, reason: string) {
  return modelAction(modelVersionId, "archive", reason);
}

function modelAction(modelVersionId: string, action: "approve" | "reject" | "archive", reason: string) {
  return apiRequest<MlModelVersion>(`/analytics/ml/models/${modelVersionId}/${action}`, {
    method: "POST",
    body: JSON.stringify({ reason }),
  });
}

export function listDeploymentPolicies() {
  return apiRequest<MlDeploymentPolicy[]>("/analytics/ml/deployment-policies");
}

export function saveDeploymentPolicy(body: {
  scope: MlDeploymentScope;
  deviceId?: string | null;
  deviceType?: string | null;
  modelVersionId?: string | null;
  mode: MlDeploymentMode;
  canaryPercent?: number | null;
  fallbackEnabled: boolean;
  reason?: string | null;
}) {
  return apiRequest<MlDeploymentPolicy>("/analytics/ml/deployment-policies", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export function resolveDeploymentPolicy(deviceId: string, deviceType: string) {
  const params = new URLSearchParams();
  if (deviceId.trim()) {
    params.set("deviceId", deviceId.trim());
  }
  if (deviceType.trim()) {
    params.set("deviceType", deviceType.trim());
  }
  const query = params.toString();
  return apiRequest<MlResolvedPolicy>(`/analytics/ml/deployment-policies/resolve${query ? `?${query}` : ""}`);
}

export function listMlLabels(datasetVersionId: string) {
  return apiRequest<MlLabel[]>(`/analytics/ml/labels?datasetVersionId=${encodeURIComponent(datasetVersionId)}`);
}

export function createMlLabel(body: {
  datasetVersionId: string;
  deviceId?: string | null;
  externalDeviceKey?: string | null;
  metricName?: string | null;
  labelStartAt: string;
  labelEndAt: string;
  label: MlLabelType;
  reason?: string | null;
}) {
  return apiRequest<MlLabel>("/analytics/ml/labels", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export function correctMlLabel(labelId: string, body: { label: MlLabelType; reason?: string | null }) {
  return apiRequest<MlLabel>(`/analytics/ml/labels/${labelId}`, {
    method: "PUT",
    body: JSON.stringify(body),
  });
}

export function getMlLabelAudit(labelId: string) {
  return apiRequest<MlLabelAudit[]>(`/analytics/ml/labels/${labelId}/audit`);
}
