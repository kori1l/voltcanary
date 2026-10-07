import { PredictionResponse } from "../../../analytics/analyticsApi";
import { generateFeatureSnapshot, MlDatasetSummary, MlDeploymentPolicy, MlFeatureSnapshotResponse, MlLabel, MlLabelType, MlLifecycleStatusFilter, MlModelVersion, MlTrainingRun } from "../../../analytics/mlModuleApi";
import { Device } from "../../../devices/devicesApi";
import { DatasetPipelineStep, DatasetWorkbenchModelFamily } from "../../../features/ml-datasets/model/datasetWorkbench.types";

export type WorkflowMode = DatasetWorkbenchModelFamily;
export type FeatureSnapshotConfig = Parameters<typeof generateFeatureSnapshot>[0];
export type DatasetLifecycleKind = "dataset" | "snapshot";
export type ArchiveDeleteAction = "archive" | "delete";
export const lifecycleStatusFilters: MlLifecycleStatusFilter[] = ["default", "active", "failed", "archived", "deleted", "all"];
export function buildPipelineSteps(
  mode: WorkflowMode,
  datasets: MlDatasetSummary[],
  labels: MlLabel[],
  featureSnapshots: MlFeatureSnapshotResponse[],
  trainingRuns: MlTrainingRun[],
  models: MlModelVersion[],
  policies: MlDeploymentPolicy[],
  prediction: PredictionResponse | null,
): DatasetPipelineStep[] {
  const datasetItems = Array.isArray(datasets) ? datasets : [];
  const labelItems = Array.isArray(labels) ? labels : [];
  const trainingItems = Array.isArray(trainingRuns) ? trainingRuns : [];
  const modelItems = Array.isArray(models) ? models : [];
  const policyItems = Array.isArray(policies) ? policies : [];
  const snapshotItems = Array.isArray(featureSnapshots) ? featureSnapshots : [];
  const readyDatasets = datasetItems.filter((dataset) => dataset.status === "READY");
  const matchingSnapshots = snapshotItems.filter((snapshot) => (snapshot.modelFamily ?? "ANOMALY_DETECTION") === mode);
  const matchingRuns = trainingItems.filter((run) => run.modelFamily === mode && ["QUEUED", "RUNNING", "SUCCEEDED"].includes(run.status));
  const approvedModels = modelItems.filter((model) => model.family === mode && ["APPROVED", "ACTIVE"].includes(model.status));
  const activePolicies = policyItems.filter((policy) => policy.mode !== "DISABLED" && Boolean(policy.modelVersionId));
  const hasPositiveFailureLabels = labelItems.some((label) => ["CONFIRMED_FAILURE", "CORRECTIVE_MAINTENANCE"].includes(label.label));
  const labelsRequired = mode === "FAILURE_PREDICTION";

  return [
    {
      id: "dataset",
      label: "Dataset",
      status: readyDatasets.length > 0 ? "ready" : datasetItems.length > 0 ? "review" : "blocked",
      detail: readyDatasets.length > 0 ? `${readyDatasets.length} ready` : "Create a previewed dataset",
    },
    {
      id: "labels",
      label: "Labels",
      status: labelsRequired ? (hasPositiveFailureLabels ? "ready" : labelItems.length > 0 ? "review" : "blocked") : "ready",
      detail: labelsRequired ? `${labelItems.length} loaded / failure positives ${hasPositiveFailureLabels ? "present" : "missing"}` : "Not required",
    },
    {
      id: "snapshot",
      label: "Feature snapshot",
      status: matchingSnapshots.length > 0 ? "ready" : snapshotItems.length > 0 ? "review" : "blocked",
      detail: matchingSnapshots.length > 0 ? `${matchingSnapshots.length} snapshots` : `Need ${humanizeKey(mode)} snapshot`,
    },
    {
      id: "training",
      label: "Training",
      status: matchingRuns.length > 0 ? "ready" : trainingItems.length > 0 ? "review" : "blocked",
      detail: matchingRuns.length > 0 ? `${matchingRuns.length} matching runs` : "Queue a matching run",
    },
    {
      id: "model",
      label: "Model",
      status: approvedModels.length > 0 ? "ready" : modelItems.some((model) => model.family === mode) ? "review" : "blocked",
      detail: approvedModels.length > 0 ? `${approvedModels.length} approved` : "Approve a matching model",
    },
    {
      id: "policy",
      label: "Policy",
      status: activePolicies.length > 0 ? "ready" : policyItems.length > 0 ? "review" : "blocked",
      detail: activePolicies.length > 0 ? `${activePolicies.length} active` : "Enable deployment policy",
    },
    {
      id: "monitoring",
      label: "Monitoring",
      status: prediction ? "ready" : "review",
      detail: prediction ? statusText(prediction.predictionStatus) : "Load device prediction",
    },
  ];
}

export function summarizeLabels(labels: MlLabel[]) {
  return labels.reduce(
    (summary, item) => {
      if (item.label === "CONFIRMED_FAILURE" || item.label === "CORRECTIVE_MAINTENANCE") {
        summary.positive += 1;
      }
      if (item.source === "MAINTENANCE_OUTCOME") {
        summary.outcome += 1;
      }
      if (item.label === "UNKNOWN" || item.label === "UNRESOLVED_ANOMALY") {
        summary.unresolved += 1;
      }
      if (item.label === "FALSE_POSITIVE") {
        summary.falsePositive += 1;
      }
      return summary;
    },
    { positive: 0, outcome: 0, unresolved: 0, falsePositive: 0 },
  );
}

export function upsertFeatureSnapshot(snapshots: MlFeatureSnapshotResponse[], snapshot: MlFeatureSnapshotResponse) {
  return [snapshot, ...snapshots.filter((item) => item.id !== snapshot.id)];
}

export function statusText(v: string) {
  return ({ UNAVAILABLE: "ML unavailable; rule analytics continue", INSUFFICIENT_DATA: "More telemetry required", SUPPRESSED_MAINTENANCE: "Suppressed during maintenance" } as Record<string, string>)[v] ?? v;
}

export function formatCanaryState(prediction: PredictionResponse) {
  const percent = prediction.canaryPercent === null || prediction.canaryPercent === undefined
    ? ""
    : ` / ${prediction.canaryPercent}%`;
  if (prediction.canaryIncluded === true) {
    return `Included${percent}`;
  }
  if (prediction.canaryIncluded === false) {
    return `Excluded${percent}`;
  }
  return percent ? percent.slice(3) : "N/A";
}

export function modelOriginLabel(model: MlModelVersion) {
  if (model.demoModel || model.modelOrigin === "DEMO") {
    return "Demo";
  }
  if (model.systemHeuristic || model.modelOrigin === "SYSTEM_HEURISTIC") {
    return "System heuristic";
  }
  return "Trained";
}

export type TrainingTemplate = {
  modelFamily: "ANOMALY_DETECTION" | "FAILURE_PREDICTION";
  title: string;
  badge: string;
  description: string;
  defaultAlgorithm: string;
  algorithms: string[];
  checklist: string[];
};

export type RiskDriverView = {
  feature: string;
  value: number | null;
  weight: number | null;
  contribution: number;
  direction: string;
};

export type ApprovalGateCriterion = {
  name: string;
  observed: number | null;
  threshold: number | null;
  passed: boolean;
  reason: string;
};

export type ApprovalGateSummary = {
  status: string;
  profileId: string | null;
  profileVersion: string | null;
  thresholdSource: string | null;
  promotionEligible: boolean;
  criteria: ApprovalGateCriterion[];
  failedCriteria: ApprovalGateCriterion[];
};

export const trainingTemplates: Record<TrainingTemplate["modelFamily"], TrainingTemplate> = {
  ANOMALY_DETECTION: {
    modelFamily: "ANOMALY_DETECTION",
    title: "Anomaly detection",
    badge: "Operational baseline",
    description: "Find unusual telemetry patterns and keep baseline fallback available.",
    defaultAlgorithm: "baseline-statistical-anomaly-v1",
    algorithms: ["baseline-statistical-anomaly-v1"],
    checklist: [
      "Use a READY dataset with normalized telemetry.",
      "Generate an anomaly feature snapshot for the selected dataset.",
      "Approve only after artifact load and model-only smoke inference pass.",
    ],
  },
  FAILURE_PREDICTION: {
    modelFamily: "FAILURE_PREDICTION",
    title: "Failure prediction",
    badge: "Outcome supervised",
    description: "Estimate failure or corrective-maintenance risk for a fixed future horizon.",
    defaultAlgorithm: "sklearn-logistic-regression-failure-v1",
    algorithms: ["sklearn-logistic-regression-failure-v1", "sklearn-random-forest-failure-v1"],
    checklist: [
      "Generate horizon labels for the same dataset and horizon.",
      "Confirm enough positive outcomes and label coverage before training.",
      "Review the model card and approval gates before promotion.",
    ],
  },
};

export function humanizeAlgorithm(value: string) {
  return value
    .replace(/-v\d+$/i, "")
    .split("-")
    .map((part) => part === "sklearn" ? "sklearn" : part.replace(/^./, (match) => match.toUpperCase()))
    .join(" ");
}

export const defaultMapping = JSON.stringify({
  timestampColumn: "ts",
  deviceKeyColumn: "device",
  metricNameColumn: "metric",
  metricValueColumn: "value",
  unitColumn: "unit",
  labelColumn: "label",
  existingDeviceIdColumn: "existingDeviceId",
  deviceTypeColumn: "deviceType",
  metadataColumns: ["firmware", "batch"],
  mappingVersion: "ui-mapping-v1",
  allowPartialAcceptance: true,
}, null, 2);

export const labelOptions: MlLabelType[] = [
  "NORMAL",
  "ANOMALY",
  "DEGRADATION",
  "MAINTENANCE",
  "CONFIRMED_FAILURE",
  "CORRECTIVE_MAINTENANCE",
  "PREVENTIVE_MAINTENANCE",
  "INSPECTION_NORMAL",
  "FALSE_POSITIVE",
  "SENSOR_FAULT",
  "UNRESOLVED_ANOMALY",
  "UNKNOWN",
];

export function deviceLabel(device: Device) {
  return `${device.name} / ${device.type} / ${device.status}`;
}

export function snapshotLabel(snapshot: MlFeatureSnapshotResponse) {
  const family = snapshot.modelFamily ?? "ANOMALY_DETECTION";
  const horizon = snapshot.horizonDays ? ` / h${snapshot.horizonDays}` : "";
  return `${family}${horizon} / ${snapshot.rowCount} rows / ${snapshot.featureSchemaHash} / ${snapshot.id}`;
}

export function defaultFeatureFamilies(modelFamily: "ANOMALY_DETECTION" | "FAILURE_PREDICTION") {
  if (modelFamily === "FAILURE_PREDICTION") {
    return ["rolling_stats", "baseline_deviation", "missing_data", "time", "device_context", "label"];
  }
  return ["rolling_stats", "baseline_deviation", "missing_data", "time", "label"];
}

export function toInstant(value: string) {
  return new Date(value).toISOString();
}

export function splitList(value: string) {
  return value
    .split(/[\n,]+/)
    .map((item) => item.trim())
    .filter(Boolean);
}

export function humanizeKey(value: string) {
  const text = value.replace(/([a-z])([A-Z])/g, "$1 $2").toLowerCase();
  return text.replace(/^./, (match) => match.toUpperCase());
}

export function formatQualityValue(value: unknown) {
  if (value === null || value === undefined) {
    return "N/A";
  }
  if (typeof value === "object") {
    return JSON.stringify(value);
  }
  return String(value);
}

export function metricNumber(metrics: Record<string, unknown>, key: string) {
  const value = metrics[key];
  if (typeof value === "number" && Number.isFinite(value)) {
    return value;
  }
  if (typeof value === "string" && value.trim()) {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
}

export function formatPercentMetric(value: number | null) {
  if (value === null) {
    return "N/A";
  }
  return `${Math.round(value * 100)}%`;
}

export function formatDecimalMetric(value: number | null) {
  if (value === null) {
    return "N/A";
  }
  return value.toLocaleString(undefined, { maximumFractionDigits: 2, minimumFractionDigits: value < 10 ? 1 : 0 });
}

export function formatSignedMetric(value: number | null) {
  if (value === null) {
    return "N/A";
  }
  const sign = value > 0 ? "+" : "";
  return `${sign}${formatDecimalMetric(value)}`;
}

export function approvalGateSummary(model: MlModelVersion): ApprovalGateSummary {
  const explicit = explicitApprovalGateSummary(model.evaluationMetrics ?? {});
  if (explicit) {
    return explicit;
  }

  const metrics = model.evaluationMetrics ?? {};
  const positiveLabels = metricNumber(metrics, "failurePositiveLabels");
  const minimumPositiveLabels = metricNumber(metrics, "minimumPositiveLabels") ?? metricNumber(metrics, "failureMinimumPositiveLabels") ?? 1;
  const labelCoverage = metricNumber(metrics, "labelCoverage") ?? metricNumber(metrics, "failureLabelCoverage");
  const minimumLabelCoverage = metricNumber(metrics, "minimumLabelCoverage") ?? metricNumber(metrics, "failureMinimumLabelCoverage") ?? 0.8;
  const validationSamples = metricNumber(metrics, "failureValidationSamples");
  const hasEvaluation = validationSamples !== null ? validationSamples > 0 : metricNumber(metrics, "failurePrAuc") !== null;
  const falseAlerts = metricNumber(metrics, "failureFalseAlertsPerDeviceMonth");
  const maxFalseAlerts = metricNumber(metrics, "maximumFalseAlertsPerDeviceMonth") ?? 30;
  const criteria: ApprovalGateCriterion[] = [
    {
      name: "minimum_positive_labels",
      observed: positiveLabels,
      threshold: minimumPositiveLabels,
      passed: positiveLabels !== null && positiveLabels >= minimumPositiveLabels,
      reason: "Requires enough confirmed failure or corrective maintenance labels.",
    },
    {
      name: "minimum_label_coverage",
      observed: labelCoverage,
      threshold: minimumLabelCoverage,
      passed: labelCoverage !== null && labelCoverage >= minimumLabelCoverage,
      reason: "Requires enough usable horizon labels for the dataset.",
    },
    {
      name: "time_based_evaluation_available",
      observed: hasEvaluation ? 1 : 0,
      threshold: 1,
      passed: hasEvaluation,
      reason: "Requires time-based holdout evaluation before promotion.",
    },
    {
      name: "maximum_false_alert_rate",
      observed: falseAlerts,
      threshold: maxFalseAlerts,
      passed: falseAlerts !== null && falseAlerts <= maxFalseAlerts,
      reason: "Limits false alerts per device-month before promotion.",
    },
    {
      name: "artifact_load_test",
      observed: model.artifactUri && model.artifactManifestUri && model.artifactHash && model.artifactSizeBytes ? 1 : 0,
      threshold: 1,
      passed: Boolean(model.artifactUri && model.artifactManifestUri && model.artifactHash && model.artifactSizeBytes),
      reason: "Requires a persisted, hashed, load-tested model bundle.",
    },
    {
      name: "feature_schema_compatibility",
      observed: model.featureSchemaHash ? 1 : 0,
      threshold: 1,
      passed: Boolean(model.featureSchemaHash),
      reason: "Requires a feature schema hash for runtime compatibility checks.",
    },
  ];
  const failedCriteria = criteria.filter((gate) => !gate.passed);
  return {
    status: failedCriteria.length === 0 ? "PASSED" : "NEEDS_REVIEW",
    profileId: "fallback-ui-derived",
    profileVersion: null,
    thresholdSource: "ui-derived-legacy-metrics",
    promotionEligible: failedCriteria.length === 0,
    criteria,
    failedCriteria,
  };
}

export function explicitApprovalGateSummary(metrics: Record<string, unknown>): ApprovalGateSummary | null {
  const raw = metrics.approvalGates;
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    return null;
  }
  const gateObject = raw as Record<string, unknown>;
  const criteria = Array.isArray(gateObject.criteria) ? gateObject.criteria : [];
  const normalizedCriteria = criteria
    .filter((item): item is Record<string, unknown> => Boolean(item) && typeof item === "object" && !Array.isArray(item))
    .map((item) => ({
      name: stringMetric(item, "name") ?? "approval_gate",
      observed: metricNumber(item, "observed"),
      threshold: metricNumber(item, "threshold"),
      passed: Boolean(item.passed),
      reason: stringMetric(item, "reason") ?? "Approval gate failed.",
    }));
  const failedCriteria = normalizedCriteria.filter((item) => !item.passed);
  const promotionEligible = typeof gateObject.promotionEligible === "boolean"
    ? gateObject.promotionEligible
    : failedCriteria.length === 0;
  return {
    status: stringMetric(gateObject, "status") ?? (promotionEligible ? "PASSED" : "NEEDS_REVIEW"),
    profileId: stringMetric(gateObject, "profileId") ?? stringMetric(metrics, "approvalGateProfileId"),
    profileVersion: stringMetric(gateObject, "profileVersion") ?? stringMetric(metrics, "approvalGateProfileVersion"),
    thresholdSource: stringMetric(gateObject, "thresholdSource") ?? stringMetric(metrics, "approvalGateThresholdSource"),
    promotionEligible,
    criteria: normalizedCriteria,
    failedCriteria,
  };
}

export function formatGateValue(gate: ApprovalGateCriterion) {
  if (gate.observed === null) {
    return "N/A";
  }
  if (gate.name.includes("coverage") || gate.name.includes("precision") || gate.name.includes("recall")) {
    return formatPercentMetric(gate.observed);
  }
  return formatGateNumber(gate.observed);
}

export function formatGateThreshold(gate: ApprovalGateCriterion) {
  if (gate.threshold === null) {
    return "N/A";
  }
  if (gate.name.includes("coverage") || gate.name.includes("precision") || gate.name.includes("recall")) {
    return formatPercentMetric(gate.threshold);
  }
  return formatGateNumber(gate.threshold);
}

export function formatGateNumber(value: number) {
  return Number.isInteger(value) ? String(value) : formatDecimalMetric(value);
}

export function normalizeRiskDrivers(drivers: Array<Record<string, unknown>>) {
  return drivers
    .map((driver) => {
      const feature = stringMetric(driver, "feature") ?? stringMetric(driver, "metric") ?? "unknown_feature";
      const contribution = metricNumber(driver, "contribution") ?? metricNumber(driver, "score") ?? 0;
      return {
        feature,
        value: metricNumber(driver, "value"),
        weight: metricNumber(driver, "weight"),
        contribution,
        direction: stringMetric(driver, "direction") ?? (contribution >= 0 ? "increases_risk" : "decreases_risk"),
      };
    })
    .sort((left, right) => Math.abs(right.contribution) - Math.abs(left.contribution));
}

export function stringMetric(metrics: Record<string, unknown>, key: string) {
  const value = metrics[key];
  return typeof value === "string" && value.trim() ? value : null;
}

export function readableDriverDirection(value: string) {
  if (value.toLowerCase().includes("decrease")) {
    return "Lowers risk";
  }
  if (value.toLowerCase().includes("increase")) {
    return "Raises risk";
  }
  return humanizeKey(value);
}

export function humanizeFeatureName(value: string) {
  return value
    .replace(/[_-]+/g, " ")
    .replace(/([a-z])([A-Z])/g, "$1 $2")
    .split(/\s+/)
    .filter(Boolean)
    .map((part) => part.length <= 2 ? part.toUpperCase() : part.replace(/^./, (match) => match.toUpperCase()))
    .join(" ");
}

export function formatDriverNumber(value: number | null) {
  if (value === null) {
    return "N/A";
  }
  return value.toLocaleString(undefined, { maximumFractionDigits: 4 });
}

export function formatTrainingWindow(value: Record<string, unknown> | undefined) {
  if (!value || Object.keys(value).length === 0) {
    return "Unavailable";
  }
  const start = stringMetric(value, "start") ?? stringMetric(value, "from") ?? stringMetric(value, "windowStart");
  const end = stringMetric(value, "end") ?? stringMetric(value, "to") ?? stringMetric(value, "windowEnd");
  if (start || end) {
    return `${start ?? "unknown start"} - ${end ?? "unknown end"}`;
  }
  return Object.entries(value).map(([key, item]) => `${humanizeKey(key)}: ${formatQualityValue(item)}`).join(" / ");
}
