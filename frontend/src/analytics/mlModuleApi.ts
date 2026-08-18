import { apiRequest } from "../api/client";

export type MlDatasetStatus = "DRAFT" | "VALIDATING" | "READY" | "FAILED" | "ARCHIVED" | "DELETED";
export type MlDatasetSourceType = "INTERNAL_TELEMETRY" | "EXTERNAL_IMPORT" | "MIXED";
export type MlTrainingSplitStrategy = "TIME_BASED" | "DEVICE_BASED" | "RANDOM";
export type MlTrainingRunStatus = "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELED" | "ARCHIVED" | "DELETED";
export type MlModelStatus = "CANDIDATE" | "APPROVED" | "ACTIVE" | "REJECTED" | "ARCHIVED" | "DELETED";
export type MlDeploymentMode = "SHADOW" | "CANARY" | "ACTIVE" | "DISABLED";
export type MlDeploymentScope = "SYSTEM" | "ACCOUNT" | "DEVICE_TYPE" | "DEVICE";
export type MlModelFamily = "ANOMALY_DETECTION" | "DEGRADATION_SCORING" | "FAILURE_PREDICTION" | "FORECASTING";
export type MlMetricSelectionMode = "COMMON" | "ALL_AVAILABLE";
export type MlWorkbenchStatus = "READY" | "READY_WITH_WARNINGS" | "BLOCKED" | "NO_DATA";
export type MlWorkbenchGateStatus = "PASS" | "WARN" | "BLOCK";
export type MlCandidateWindowKind = "BEST_COVERAGE" | "RECENT_STABLE" | "FAILURE_RICH" | "USER_SELECTED";
export type MlLabelType =
  | "NORMAL"
  | "ANOMALY"
  | "DEGRADATION"
  | "MAINTENANCE"
  | "CONFIRMED_FAILURE"
  | "CORRECTIVE_MAINTENANCE"
  | "PREVENTIVE_MAINTENANCE"
  | "INSPECTION_NORMAL"
  | "FALSE_POSITIVE"
  | "SENSOR_FAULT"
  | "UNRESOLVED_ANOMALY"
  | "UNKNOWN";
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
  artifactUri: string | null;
  artifactHash: string | null;
  immutableRowReference: string | null;
  dataHash: string | null;
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
  qualityProfile: Record<string, unknown>;
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
  datasetVersionId: string;
  importedFileId: string;
  validationReportId: string;
  contentHash: string;
  acceptedRows: number;
  rejectedRows: number;
  warningCount: number;
  validationMessages: string[];
  importedAt: string;
}

export interface MlInternalDatasetResponse {
  datasetId: string;
  datasetVersionId: string;
  accountId: string;
  version: number;
  status: MlDatasetStatus;
  splitStrategy: MlTrainingSplitStrategy;
  acceptedRows: number;
  rejectedRows: number;
  warningCount: number;
  validationMessages: string[];
  createdAt: string;
  readyAt: string | null;
}

export interface MlDatasetWorkbenchPreviewRequest {
  modelFamily?: MlModelFamily | null;
  deviceIds: string[];
  deviceType?: string | null;
  deviceMetadata?: Record<string, string> | null;
  metricSelectionMode?: MlMetricSelectionMode | null;
  selectedMetrics?: string[];
  from: string;
  to: string;
  quickRange?: string | null;
  horizonDays?: number | null;
  splitStrategy?: MlTrainingSplitStrategy | null;
  splitConfigJson?: string | null;
  featureConfigVersion?: string | null;
  minSamplesPerMetric?: number | null;
  maxSamplingGapSeconds?: number | null;
  candidateLimit?: number | null;
}

export interface MlDatasetMetricCoverage {
  metricName: string;
  displayName: string;
  canonicalMetricName: string;
  unit: string | null;
  devicesWithMetric: number;
  selectedDeviceCount: number;
  coverageRatio: number;
  sampleCount: number;
  maxSamplingGapSeconds: number;
  duplicateTimestampRate: number;
  nonFiniteImpossibleValueCount: number;
  unitNormalizationWarnings: number;
  status: MlWorkbenchStatus;
  blockingReasons: string[];
  warningReasons: string[];
}

export interface MlDatasetCandidateWindow {
  id: string;
  kind: MlCandidateWindowKind;
  from: string;
  to: string;
  score: number;
  status: MlWorkbenchStatus;
  recommended: boolean;
  sampleCount: number;
  deviceCount: number;
  metricCount: number;
  coverageRatio: number;
  maxSamplingGapSeconds: number;
  positiveLabels: number;
  normalLabels: number;
  unobservableLabels: number;
  maintenanceExcludedLabels: number;
  blockingReasons: string[];
  warningReasons: string[];
  explanation: string;
}

export interface MlDatasetTimelineBucket {
  bucketStart: string;
  bucketEnd: string;
  sampleCount: number;
  coverageRatio: number;
  status: MlWorkbenchStatus;
  events: string[];
}

export interface MlDatasetLabelReadiness {
  horizonDays: number | null;
  positiveLabels: number;
  normalLabels: number;
  unresolvedLabels: number;
  unobservableRows: number;
  maintenanceExcludedLabels: number;
  coverageRatio: number;
  status: MlWorkbenchStatus;
}

export interface MlDatasetQualityGate {
  key: string;
  label: string;
  status: MlWorkbenchGateStatus;
  actual: unknown;
  threshold: unknown;
  blocking: boolean;
  message: string;
}

export interface MlDatasetWorkbenchPreviewResponse {
  previewId: string;
  previewHash: string;
  expiresAt: string;
  status: MlWorkbenchStatus;
  recommendedCandidateId: string | null;
  selectedWindow: {
    from: string;
    to: string;
    source: MlCandidateWindowKind;
  };
  availableMetrics: MlDatasetMetricCoverage[];
  candidateWindows: MlDatasetCandidateWindow[];
  timeline: MlDatasetTimelineBucket[];
  labelReadiness: MlDatasetLabelReadiness | null;
  qualityGates: MlDatasetQualityGate[];
  blockingReasons: string[];
  warningReasons: string[];
  createRequestDefaults: {
    metrics: string[];
    from: string;
    to: string;
    splitStrategy: MlTrainingSplitStrategy;
    featureConfigVersion: string;
    minSamplesPerMetric: number;
    maxSamplingGapSeconds: number;
    modelFamily: MlModelFamily;
    horizonDays: number | null;
    metricSelectionMode: MlMetricSelectionMode;
  };
}

export interface MlFeatureSnapshotResponse {
  id: string;
  accountId: string;
  datasetVersionId: string;
  featureSetVersion: string;
  codeConfigVersion: string;
  featureSchemaHash: string;
  featureDataHash: string | null;
  featureConfigHash: string | null;
  rowCount: number;
  rejectedRowCount: number;
  artifactUri: string;
  immutableRowReference: string | null;
  modelFamily: string | null;
  horizonDays: number | null;
  featureTemplateId: string | null;
  featureTemplateVersion: string | null;
  lifecycleStatus: string | null;
  createdAt: string;
}

export type MlHorizonLabelGenerationStatus = "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED";

export interface MlHorizonLabelGenerationResult {
  lineageHash: string;
  generatedLabels: number;
  positiveLabels: number;
  normalLabels: number;
  maintenanceExcludedLabels: number;
  unresolvedLabels: number;
  unobservableRows: number;
}

export interface MlHorizonLabelGenerationResponse {
  jobId: string;
  datasetVersionId: string;
  status: MlHorizonLabelGenerationStatus;
  submittedAt: string;
  startedAt: string | null;
  completedAt: string | null;
  progress: number | null;
  message: string | null;
  failureReason: string | null;
  horizonDays: number;
  generationVersion: string;
  includePreventiveMaintenance: boolean;
  inputLineageHash: string;
  result: MlHorizonLabelGenerationResult | null;
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
  leaseOwner?: string | null;
  leaseExpiresAt?: string | null;
  heartbeatAt?: string | null;
  cancellationRequested?: boolean;
  retryCount?: number;
  staleRecoveredAt?: string | null;
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
  artifactManifestUri: string | null;
  artifactHash: string | null;
  artifactSizeBytes: number | null;
  compatibleDeviceTypes: string[];
  supportedMetrics: string[];
  evaluationMetrics: Record<string, unknown>;
  modelOrigin?: "TRAINED" | "DEMO" | "SYSTEM_HEURISTIC" | string;
  demoModel?: boolean;
  systemHeuristic?: boolean;
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
  horizonDays: number | null;
  generationVersion: string | null;
  sourceOutcomeIdsJson: string | null;
  lineageHash: string | null;
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

export interface MlLifecycleActionRequest {
  reason: string;
  force?: boolean;
}

export interface MlLifecycleImpact {
  action: string;
  blocked: boolean;
  message: string;
  dependencies: Array<{
    type: string;
    id: string;
    details?: Record<string, unknown>;
  }>;
}

export type MlLifecycleStatusFilter = "default" | "active" | "failed" | "archived" | "deleted" | "all";

function statusFilterQuery(statusFilter: MlLifecycleStatusFilter = "default") {
  return statusFilter === "default" ? "" : `?statusFilter=${encodeURIComponent(statusFilter)}`;
}

export function listMlDatasets(statusFilter: MlLifecycleStatusFilter = "default") {
  return apiRequest<MlDatasetSummary[]>(`/analytics/ml/datasets${statusFilterQuery(statusFilter)}`);
}

export function getMlDatasetVersion(datasetVersionId: string) {
  return apiRequest<MlDatasetDetail>(`/analytics/ml/datasets/versions/${datasetVersionId}`);
}

export function createInternalTelemetryDataset(body: {
  name: string;
  description?: string | null;
  deviceIds: string[];
  deviceType?: string | null;
  deviceMetadata?: Record<string, string>;
  metrics: string[];
  from: string;
  to: string;
  splitStrategy: MlTrainingSplitStrategy;
  splitConfigJson?: string | null;
  featureConfigVersion: string;
  minSamplesPerMetric: number;
  maxSamplingGapSeconds: number;
  previewId?: string | null;
  previewHash?: string | null;
  modelFamily?: MlModelFamily | null;
  horizonDays?: number | null;
  metricSelectionMode?: MlMetricSelectionMode | null;
}) {
  return apiRequest<MlInternalDatasetResponse>("/analytics/ml/datasets/internal-telemetry", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export function previewInternalTelemetryDataset(body: MlDatasetWorkbenchPreviewRequest) {
  return apiRequest<MlDatasetWorkbenchPreviewResponse>("/analytics/ml/datasets/internal-telemetry/preview", {
    method: "POST",
    body: JSON.stringify(body),
  });
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
  modelFamily?: string | null;
  horizonDays?: number | null;
  featureTemplateId?: string | null;
  featureTemplateVersion?: string | null;
}) {
  return apiRequest<MlFeatureSnapshotResponse>("/analytics/ml/datasets/feature-snapshots", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export function listFeatureSnapshots(datasetVersionId?: string) {
  const query = datasetVersionId ? `?datasetVersionId=${encodeURIComponent(datasetVersionId)}` : "";
  return apiRequest<MlFeatureSnapshotResponse[]>(`/analytics/ml/datasets/feature-snapshots${query}`);
}

export function archiveMlDatasetVersion(datasetVersionId: string, reason: string) {
  return datasetVersionAction(datasetVersionId, "archive", reason);
}

export function previewMlDatasetVersionLifecycle(datasetVersionId: string, action: "archive" | "delete") {
  return apiRequest<MlLifecycleImpact>(`/analytics/ml/datasets/versions/${datasetVersionId}/lifecycle-impact?action=${action}`);
}

export function deleteMlDatasetVersion(datasetVersionId: string, reason: string) {
  return datasetVersionAction(datasetVersionId, "delete", reason);
}

function datasetVersionAction(datasetVersionId: string, action: "archive" | "delete", reason: string) {
  return apiRequest<MlDatasetSummary>(`/analytics/ml/datasets/versions/${datasetVersionId}/${action}`, {
    method: "POST",
    body: JSON.stringify({ reason, force: false } satisfies MlLifecycleActionRequest),
  });
}

export function archiveFeatureSnapshot(featureSnapshotId: string, reason: string) {
  return featureSnapshotAction(featureSnapshotId, "archive", reason);
}

export function previewFeatureSnapshotLifecycle(featureSnapshotId: string, action: "archive" | "delete") {
  return apiRequest<MlLifecycleImpact>(`/analytics/ml/datasets/feature-snapshots/${featureSnapshotId}/lifecycle-impact?action=${action}`);
}

export function deleteFeatureSnapshot(featureSnapshotId: string, reason: string) {
  return featureSnapshotAction(featureSnapshotId, "delete", reason);
}

function featureSnapshotAction(featureSnapshotId: string, action: "archive" | "delete", reason: string) {
  return apiRequest<MlFeatureSnapshotResponse>(`/analytics/ml/datasets/feature-snapshots/${featureSnapshotId}/${action}`, {
    method: "POST",
    body: JSON.stringify({ reason, force: false } satisfies MlLifecycleActionRequest),
  });
}

export function generateHorizonLabels(body: {
  datasetVersionId: string;
  horizonDays: number;
  generationVersion?: string | null;
  includePreventiveMaintenance: boolean;
}) {
  return apiRequest<MlHorizonLabelGenerationResponse>("/analytics/ml/datasets/horizon-labels", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export function getHorizonLabelGenerationStatus(jobId: string) {
  return apiRequest<MlHorizonLabelGenerationResponse>(`/analytics/ml/datasets/horizon-labels/${jobId}`);
}

export function listTrainingRuns(statusFilter: MlLifecycleStatusFilter = "default") {
  return apiRequest<MlTrainingRun[]>(`/analytics/ml/training-runs${statusFilterQuery(statusFilter)}`);
}

export function createTrainingRun(body: {
  datasetVersionId: string;
  featureSnapshotId?: string | null;
  modelFamily: string;
  algorithm: string;
  failureHorizonDays?: number | null;
  minimumPositiveLabels?: number | null;
  minimumLabelCoverage?: number | null;
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

export function archiveTrainingRun(runId: string, reason: string) {
  return trainingRunAction(runId, "archive", reason);
}

export function previewTrainingRunLifecycle(runId: string, action: "archive" | "delete") {
  return apiRequest<MlLifecycleImpact>(`/analytics/ml/training-runs/${runId}/lifecycle-impact?action=${action}`);
}

export function deleteTrainingRun(runId: string, reason: string) {
  return trainingRunAction(runId, "delete", reason);
}

function trainingRunAction(runId: string, action: "archive" | "delete", reason: string) {
  return apiRequest<MlTrainingRun>(`/analytics/ml/training-runs/${runId}/${action}`, {
    method: "POST",
    body: JSON.stringify({ reason, force: false } satisfies MlLifecycleActionRequest),
  });
}

export function listMlModels(statusFilter: MlLifecycleStatusFilter = "default") {
  return apiRequest<MlModelVersion[]>(`/analytics/ml/models${statusFilterQuery(statusFilter)}`);
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

export function deleteMlModel(modelVersionId: string, reason: string) {
  return modelAction(modelVersionId, "delete", reason);
}

export function previewMlModelLifecycle(modelVersionId: string, action: "delete") {
  return apiRequest<MlLifecycleImpact>(`/analytics/ml/models/${modelVersionId}/lifecycle-impact?action=${action}`);
}

function modelAction(modelVersionId: string, action: "approve" | "reject" | "archive" | "delete", reason: string) {
  return apiRequest<MlModelVersion>(`/analytics/ml/models/${modelVersionId}/${action}`, {
    method: "POST",
    body: JSON.stringify({ reason, force: false } satisfies MlLifecycleActionRequest),
  });
}

export function listDeploymentPolicies(statusFilter: MlLifecycleStatusFilter = "default") {
  return apiRequest<MlDeploymentPolicy[]>(`/analytics/ml/deployment-policies${statusFilterQuery(statusFilter)}`);
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

export function disableDeploymentPolicy(policyId: string, reason: string) {
  return deploymentPolicyAction(policyId, "disable", reason);
}

export function previewDeploymentPolicyLifecycle(policyId: string, action: "disable" | "delete") {
  return apiRequest<MlLifecycleImpact>(`/analytics/ml/deployment-policies/${policyId}/lifecycle-impact?action=${action}`);
}

export function deleteDeploymentPolicy(policyId: string, reason: string) {
  return deploymentPolicyAction(policyId, "delete", reason);
}

function deploymentPolicyAction(policyId: string, action: "disable" | "delete", reason: string) {
  return apiRequest<MlDeploymentPolicy>(`/analytics/ml/deployment-policies/${policyId}/${action}`, {
    method: "POST",
    body: JSON.stringify({ reason, force: false } satisfies MlLifecycleActionRequest),
  });
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

export function deleteMlLabel(labelId: string, reason: string) {
  return apiRequest<MlLabel>(`/analytics/ml/labels/${labelId}/delete`, {
    method: "POST",
    body: JSON.stringify({ reason, force: false } satisfies MlLifecycleActionRequest),
  });
}

export function previewMlLabelLifecycle(labelId: string, action: "delete") {
  return apiRequest<MlLifecycleImpact>(`/analytics/ml/labels/${labelId}/lifecycle-impact?action=${action}`);
}
